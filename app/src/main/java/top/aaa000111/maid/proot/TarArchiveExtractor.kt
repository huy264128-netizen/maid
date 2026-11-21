package top.aaa000111.maid.proot

import com.github.luben.zstd.ZstdInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * 纯 Kotlin 实现的 POSIX UStar / GNU Tar（`.tar` / `.tar.gz` / `.tgz` / `.tar.zst` / `.tzst`）解压器。
 *
 * 用于从 `assets/debian-rootfs.tar.zst`、`assets/rootfs.tar.gz` 或用户在设置中选择的本地 rootfs 归档文件解压初始化 `<filesDir>/rootfs`，
 * 支持自动识别 Zstandard 魔数（`0x28 0xB5 0x2F 0xFD`）、GZIP 魔数（`0x1F 0x8B`）与普通 `.tar`，
 * 支持普通文件、目录、符号链接（`2`）、硬链接（`1`）、GNU LongName（`L`）、GNU LongLink（`K`）、
 * POSIX PAX 扩展头（`x` / `g`）以及文件可执行权限位还原。
 */
object TarArchiveExtractor {

    private const val TAR_BLOCK_SIZE = 512

    data class ExtractStats(
        val extractedFiles: Int,
        val extractedDirectories: Int,
        val extractedLinks: Int,
    ) {
        val totalEntries: Int
            get() = extractedFiles + extractedDirectories + extractedLinks
    }

    /**
     * 解压 `.tar`、`.tar.gz` / `.tgz` 或 `.tar.zst` / `.tzst` 输入流到目标目录 [targetDir]
     *（自动检测 Zstandard 魔数 `0x28 0xB5 0x2F 0xFD` 与 GZIP 魔数 `0x1F 0x8B`）。
     */
    fun extractTarOrTarGz(
        rawInput: InputStream,
        targetDir: File,
    ): ExtractStats {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val canonicalTargetPath = targetDir.canonicalPath

        val buffered = if (rawInput is BufferedInputStream) rawInput else BufferedInputStream(rawInput, 64 * 1024)
        buffered.mark(8)
        val b0 = buffered.read()
        val b1 = buffered.read()
        val b2 = buffered.read()
        val b3 = buffered.read()
        buffered.reset()

        val isZstd = (b0 == 0x28 && b1 == 0xB5 && b2 == 0x2F && b3 == 0xFD)
        val isGzip = (b0 == 0x1F && b1 == 0x8B)

        val tarStream: InputStream = when {
            isZstd -> BufferedInputStream(ZstdInputStream(buffered), 64 * 1024)
            isGzip -> BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024)
            else -> buffered
        }

        val headerBlock = ByteArray(TAR_BLOCK_SIZE)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var pendingPaxPath: String? = null
        var pendingPaxLinkPath: String? = null

        var fileCount = 0
        var dirCount = 0
        var linkCount = 0

        data class DeferredHardLink(
            val linkFile: File,
            val targetRelativePath: String,
        )
        val deferredHardLinks = mutableListOf<DeferredHardLink>()

        while (true) {
            val bytesRead = readFullyOrEof(tarStream, headerBlock)
            if (bytesRead == 0 || isAllZeroBlock(headerBlock)) {
                break
            }
            if (bytesRead < TAR_BLOCK_SIZE) {
                throw IllegalArgumentException("Corrupted tar archive: incomplete 512-byte header block ($bytesRead bytes)")
            }

            val rawName = parseNullTerminatedString(headerBlock, 0, 100)
            val mode = parseOctalLong(headerBlock, 100, 8).toInt()
            val size = parseTarSize(headerBlock, 124, 12)
            val typeFlag = headerBlock[156].toInt().toChar()
            val rawLinkName = parseNullTerminatedString(headerBlock, 157, 100)
            val magic = parseNullTerminatedString(headerBlock, 257, 6)
            val prefix = if (magic.startsWith("ustar")) {
                parseNullTerminatedString(headerBlock, 345, 155)
            } else {
                ""
            }

            when (typeFlag) {
                'L' -> {
                    pendingLongName = readEntryPayloadAsString(tarStream, size).trimEnd('\u0000')
                    continue
                }

                'K' -> {
                    pendingLongLink = readEntryPayloadAsString(tarStream, size).trimEnd('\u0000')
                    continue
                }

                'x', 'g' -> {
                    val paxText = readEntryPayloadAsString(tarStream, size)
                    val paxMap = parsePaxRecords(paxText)
                    if (typeFlag == 'x') {
                        pendingPaxPath = paxMap["path"]
                        pendingPaxLinkPath = paxMap["linkpath"]
                    }
                    continue
                }
            }

            val effectiveEntryName = (
                pendingPaxPath
                    ?: pendingLongName
                    ?: combinePrefixAndName(prefix, rawName)
                ).also {
                    pendingPaxPath = null
                    pendingLongName = null
                }

            val effectiveLinkName = (
                pendingPaxLinkPath
                    ?: pendingLongLink
                    ?: rawLinkName
                ).also {
                    pendingPaxLinkPath = null
                    pendingLongLink = null
                }

            val normalizedRelPath = normalizeArchiveRelativePath(effectiveEntryName)
            if (normalizedRelPath.isEmpty()) {
                skipEntryPayload(tarStream, size)
                continue
            }

            val outFile = File(targetDir, normalizedRelPath)
            val canonicalOutPath = outFile.canonicalPath
            if (
                canonicalOutPath != canonicalTargetPath &&
                !canonicalOutPath.startsWith("$canonicalTargetPath${File.separator}")
            ) {
                skipEntryPayload(tarStream, size)
                throw SecurityException("Refusing to extract tar entry outside rootfs target directory: $effectiveEntryName")
            }

            when (typeFlag) {
                '5' -> {
                    if (!outFile.exists()) {
                        outFile.mkdirs()
                    }
                    applyFilePermissions(outFile, mode, isDirectory = true)
                    skipEntryPayload(tarStream, size)
                    dirCount += 1
                }

                '2' -> {
                    skipEntryPayload(tarStream, size)
                    outFile.parentFile?.mkdirs()
                    deleteExistingNonDirOrEmpty(outFile)
                    val linkTarget = effectiveLinkName.ifEmpty { "." }
                    val createdSymlink = runCatching {
                        Files.createSymbolicLink(outFile.toPath(), File(linkTarget).toPath())
                        true
                    }.getOrDefault(false)
                    if (!createdSymlink) {
                        // 若宿主文件系统不支持符号链接，回退记录后续或复制
                        deferredHardLinks += DeferredHardLink(
                            linkFile = outFile,
                            targetRelativePath = normalizeArchiveRelativePath(linkTarget),
                        )
                    }
                    linkCount += 1
                }

                '1' -> {
                    skipEntryPayload(tarStream, size)
                    deferredHardLinks += DeferredHardLink(
                        linkFile = outFile,
                        targetRelativePath = normalizeArchiveRelativePath(effectiveLinkName),
                    )
                    linkCount += 1
                }

                '0', '\u0000' -> {
                    if (effectiveEntryName.endsWith("/")) {
                        if (!outFile.exists()) {
                            outFile.mkdirs()
                        }
                        applyFilePermissions(outFile, mode, isDirectory = true)
                        skipEntryPayload(tarStream, size)
                        dirCount += 1
                    } else {
                        outFile.parentFile?.mkdirs()
                        deleteExistingNonDirOrEmpty(outFile)
                        writeEntryPayloadToFile(tarStream, size, outFile)
                        applyFilePermissions(outFile, mode, isDirectory = false)
                        fileCount += 1
                    }
                }

                else -> {
                    // 其他特殊节点（如字符设备/块设备/FIFO）在非 root 应用目录下跳过数据载荷
                    skipEntryPayload(tarStream, size)
                }
            }
        }

        // 处理硬链接与回退链接
        for (hardLink in deferredHardLinks) {
            if (hardLink.targetRelativePath.isEmpty()) continue
            val targetFile = resolveHardLinkTargetFile(
                targetDir = targetDir,
                linkFile = hardLink.linkFile,
                targetRelativePath = hardLink.targetRelativePath,
            )
            val canonicalTarget = runCatching { targetFile.canonicalPath }.getOrNull() ?: continue
            if (
                canonicalTarget != canonicalTargetPath &&
                !canonicalTarget.startsWith("$canonicalTargetPath${File.separator}")
            ) {
                continue
            }
            if (!targetFile.exists()) continue
            hardLink.linkFile.parentFile?.mkdirs()
            deleteExistingNonDirOrEmpty(hardLink.linkFile)
            val linked = runCatching {
                Files.createLink(hardLink.linkFile.toPath(), targetFile.toPath())
                true
            }.getOrDefault(false)
            if (!linked) {
                runCatching {
                    if (targetFile.isDirectory) {
                        targetFile.copyRecursively(hardLink.linkFile, overwrite = true)
                    } else if (targetFile.isFile) {
                        targetFile.copyTo(hardLink.linkFile, overwrite = true)
                        if (targetFile.canExecute()) {
                            hardLink.linkFile.setExecutable(true, false)
                        }
                    }
                }
            }
        }

        return ExtractStats(
            extractedFiles = fileCount,
            extractedDirectories = dirCount,
            extractedLinks = linkCount,
        )
    }

    private fun resolveHardLinkTargetFile(
        targetDir: File,
        linkFile: File,
        targetRelativePath: String,
    ): File {
        val fromRoot = File(targetDir, targetRelativePath)
        if (fromRoot.exists()) return fromRoot
        val parent = linkFile.parentFile
        if (parent != null) {
            val fromParent = File(parent, targetRelativePath)
            if (fromParent.exists()) return fromParent
        }
        return fromRoot
    }

    private fun normalizeArchiveRelativePath(rawPath: String): String {
        var cleaned = rawPath.trim().replace('\\', '/')
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.removePrefix("./")
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.removePrefix("/")
        }
        cleaned = cleaned.trimEnd('/')
        if (cleaned == "." || cleaned.isEmpty()) return ""

        val segments = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        val normalizedSegments = mutableListOf<String>()
        for (seg in segments) {
            if (seg == "..") {
                if (normalizedSegments.isNotEmpty()) {
                    normalizedSegments.removeAt(normalizedSegments.lastIndex)
                }
            } else {
                normalizedSegments += seg
            }
        }
        return normalizedSegments.joinToString("/")
    }

    /**
     * 如果归档文件顶层只有一个包裹目录（例如 `alpine-minirootfs/bin`, `alpine-minirootfs/etc`），
     * 自动将其内容提升至 [rootfsDir] 根目录。
     */
    fun flattenSingleTopLevelRootfsDirIfNeeded(rootfsDir: File) {
        val children = rootfsDir.listFiles()?.filter { it.name != TermuxProotRuntime.ROOTFS_INIT_MARKER_FILE_NAME }.orEmpty()
        if (children.size != 1) return
        val onlyChild = children.single()
        if (!onlyChild.isDirectory) return

        val standardTopNames = setOf("bin", "etc", "usr", "lib", "lib64", "sbin", "var", "root", "home", "tmp", "proc", "sys", "dev")
        if (onlyChild.name in standardTopNames) return

        val subChildren = onlyChild.listFiles().orEmpty()
        val looksLikeRootfsRoot = subChildren.any { it.name in standardTopNames }
        if (!looksLikeRootfsRoot) return

        for (sub in subChildren) {
            val dest = File(rootfsDir, sub.name)
            if (!sub.renameTo(dest)) {
                sub.copyRecursively(dest, overwrite = true)
                sub.deleteRecursively()
            }
        }
        onlyChild.deleteRecursively()
    }

    private fun deleteExistingNonDirOrEmpty(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || file.exists()) {
            if (file.isDirectory && !Files.isSymbolicLink(path)) {
                file.deleteRecursively()
            } else {
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    private fun applyFilePermissions(file: File, mode: Int, isDirectory: Boolean) {
        runCatching {
            file.setReadable(true, false)
            file.setWritable(true, true)
            val isExecutable = isDirectory || (mode and 0b001_001_001) != 0
            if (isExecutable) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun writeEntryPayloadToFile(stream: InputStream, size: Long, outFile: File) {
        val buffer = ByteArray(16 * 1024)
        var remaining = size
        FileOutputStream(outFile).use { out ->
            while (remaining > 0L) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = stream.read(buffer, 0, toRead)
                if (read < 0) {
                    throw IllegalArgumentException("Unexpected EOF while extracting tar entry: ${outFile.name}")
                }
                out.write(buffer, 0, read)
                remaining -= read.toLong()
            }
        }
        skipPaddingBytes(stream, size)
    }

    private fun readEntryPayloadAsString(stream: InputStream, size: Long): String {
        val byteSize = size.coerceAtLeast(0L).toInt()
        val data = ByteArray(byteSize)
        val read = readFullyOrEof(stream, data)
        if (read < byteSize) {
            throw IllegalArgumentException("Unexpected EOF while reading extended tar header")
        }
        skipPaddingBytes(stream, size)
        return String(data, StandardCharsets.UTF_8)
    }

    private fun skipEntryPayload(stream: InputStream, size: Long) {
        var remaining = size + computePaddingBytes(size)
        val discard = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val toRead = minOf(discard.size.toLong(), remaining).toInt()
            val read = stream.read(discard, 0, toRead)
            if (read < 0) break
            remaining -= read.toLong()
        }
    }

    private fun skipPaddingBytes(stream: InputStream, size: Long) {
        var padding = computePaddingBytes(size).toInt()
        while (padding > 0) {
            val skipped = stream.read()
            if (skipped < 0) break
            padding -= 1
        }
    }

    private fun computePaddingBytes(size: Long): Long {
        val rem = size % TAR_BLOCK_SIZE
        return if (rem == 0L) 0L else (TAR_BLOCK_SIZE - rem)
    }

    private fun readFullyOrEof(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = stream.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun isAllZeroBlock(block: ByteArray): Boolean {
        for (b in block) {
            if (b.toInt() != 0) return false
        }
        return true
    }

    private fun parseNullTerminatedString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = (offset + length).coerceAtMost(bytes.size)
        while (end < limit && bytes[end].toInt() != 0) {
            end += 1
        }
        return String(bytes, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun parseOctalLong(bytes: ByteArray, offset: Int, length: Int): Long {
        val raw = parseNullTerminatedString(bytes, offset, length).trim()
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull(8) ?: 0L
    }

    private fun parseTarSize(bytes: ByteArray, offset: Int, length: Int): Long {
        // 支持 GNU base-256 扩展大文件尺寸编码（最高位为 1）
        val firstByte = bytes[offset].toInt() and 0xFF
        if ((firstByte and 0x80) != 0) {
            var value = 0L
            for (i in 1 until length) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
            }
            return value
        }
        return parseOctalLong(bytes, offset, length)
    }

    private fun combinePrefixAndName(prefix: String, name: String): String {
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun parsePaxRecords(paxText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < paxText.length) {
            val spaceIdx = paxText.indexOf(' ', index)
            if (spaceIdx <= index) break
            val recordEnd = paxText.indexOf('\n', spaceIdx)
            val lineEnd = if (recordEnd >= 0) recordEnd else paxText.length
            val kv = paxText.substring(spaceIdx + 1, lineEnd)
            val eqIdx = kv.indexOf('=')
            if (eqIdx > 0) {
                val key = kv.substring(0, eqIdx).trim()
                val value = kv.substring(eqIdx + 1)
                result[key] = value
            }
            index = if (recordEnd >= 0) recordEnd + 1 else paxText.length
        }
        return result
    }
}
