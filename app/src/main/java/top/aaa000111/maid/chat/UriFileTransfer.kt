package top.aaa000111.maid.chat

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Base64
import top.aaa000111.maid.androidtool.PrivilegeBackend
import top.aaa000111.maid.shizuku.ShellCommandResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 文件传输操作模式：
 * - [COPY]：复制源文件内容到目标 URI，保留源文件；
 * - [MOVE]：将源文件移动到目标 URI，写入校验完成后删除源文件。
 */
enum class TransferMode {
    COPY,
    MOVE,
}

/**
 * 统一的 `uri -> uri` 底层文件传输接口：
 * 根据源与目标 URI 的协议与当前权限上下文自动决定传输方式（本地直连、Shizuku/Root 特权通道、`content://`、`data:` 等），
 * 与上层会话元数据（`id` / `fileName` / `description`）完全解耦。
 */
interface UriFileTransfer {
    suspend fun transfer(
        sourceUri: String,
        targetUri: String,
        mode: TransferMode = TransferMode.COPY,
        overwrite: Boolean = true,
        defaultFileName: String? = null,
    ): SessionFileTransferResult
}

/**
 * 按 URI 解析后的存储端点描述，用于将“协议解析”与“流式搬运/同后端快路径”正交解耦。
 */
internal sealed interface ResolvedUriEndpoint {
    val rawUri: String

    data class LocalFile(
        override val rawUri: String,
        val file: File,
        val explicitDirectory: Boolean,
    ) : ResolvedUriEndpoint

    data class PrivilegedFile(
        override val rawUri: String,
        val absolutePath: String,
        val explicitDirectory: Boolean,
    ) : ResolvedUriEndpoint

    data class ContentUri(
        override val rawUri: String,
    ) : ResolvedUriEndpoint

    data class DataUri(
        override val rawUri: String,
    ) : ResolvedUriEndpoint
}

/**
 * [UriFileTransfer] 的默认多后端实现：
 * - 自动解析 `file://`、本地路径、`content://`、`data:`，并按应用内部目录/存储权限自动路由到直连或 Shizuku/Root 通道；
 * - 同后端（`LocalFile -> LocalFile`、`PrivilegedFile -> PrivilegedFile`）走零拷贝/原生命令快路径，支持文件与目录；
 * - 跨后端（如 `PrivilegedFile -> LocalFile`、`ContentUri -> LocalFile`、`LocalFile -> PrivilegedFile`）走统一流式原子写入 + 移动删源。
 */
class DefaultUriFileTransfer(
    private val contextProvider: () -> Context? = { SessionAttachmentStorage.appContextOrNull() },
    private val internalRootProvider: () -> File? = { SessionAttachmentStorage.internalRootDirOrNull() },
    private val pathResolver: (String) -> File? = { SessionAttachmentStorage.resolveLocalFileOrNull(it) },
    private val explicitDirectFileAccessChecker: (() -> Boolean)? = null,
    private val privilegedShellRunner: (suspend (String) -> ShellCommandResult)? = { cmd ->
        PrivilegeBackend.execShizukuOrRootShell(cmd)
    },
) : UriFileTransfer {

    override suspend fun transfer(
        sourceUri: String,
        targetUri: String,
        mode: TransferMode,
        overwrite: Boolean,
        defaultFileName: String?,
    ): SessionFileTransferResult {
        val trimmedSource = sourceUri.trim()
        val trimmedTarget = targetUri.trim()
        if (trimmedSource.isEmpty()) {
            return SessionFileTransferResult(success = false, message = "Source URI must not be blank")
        }
        if (trimmedTarget.isEmpty()) {
            return SessionFileTransferResult(success = false, message = "Target URI must not be blank")
        }

        val sourceEndpoint = resolveEndpoint(trimmedSource)
            ?: return SessionFileTransferResult(
                success = false,
                message = "Unsupported source URI: $trimmedSource",
            )
        val targetEndpoint = resolveEndpoint(trimmedTarget)
            ?: return SessionFileTransferResult(
                success = false,
                message = "Unsupported target URI: $trimmedTarget",
            )

        if (targetEndpoint is ResolvedUriEndpoint.DataUri) {
            return SessionFileTransferResult(
                success = false,
                message = "Cannot write to a read-only data: URI",
            )
        }

        return runCatching {
            when {
                sourceEndpoint is ResolvedUriEndpoint.LocalFile &&
                    targetEndpoint is ResolvedUriEndpoint.LocalFile ->
                    transferLocalToLocal(
                        source = sourceEndpoint,
                        target = targetEndpoint,
                        mode = mode,
                        overwrite = overwrite,
                        defaultFileName = defaultFileName,
                    )

                sourceEndpoint is ResolvedUriEndpoint.PrivilegedFile &&
                    targetEndpoint is ResolvedUriEndpoint.PrivilegedFile ->
                    transferPrivilegedToPrivileged(
                        source = sourceEndpoint,
                        target = targetEndpoint,
                        mode = mode,
                        overwrite = overwrite,
                        defaultFileName = defaultFileName,
                    )

                else ->
                    transferAcrossBackends(
                        source = sourceEndpoint,
                        target = targetEndpoint,
                        mode = mode,
                        overwrite = overwrite,
                        defaultFileName = defaultFileName,
                    )
            }
        }.getOrElse { error ->
            SessionFileTransferResult(
                success = false,
                message = "File transfer failed: ${error.message ?: error::class.simpleName}",
            )
        }
    }

    internal fun resolveEndpoint(rawUri: String): ResolvedUriEndpoint? {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("content://", ignoreCase = true) ->
                ResolvedUriEndpoint.ContentUri(rawUri = trimmed)

            trimmed.startsWith("data:", ignoreCase = true) ->
                ResolvedUriEndpoint.DataUri(rawUri = trimmed)

            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ->
                null

            else -> {
                val localFile = pathResolver(trimmed) ?: return null
                val explicitDirectory = trimmed.endsWith("/") || trimmed.endsWith(File.separator)
                if (canAccessDirectly(localFile)) {
                    ResolvedUriEndpoint.LocalFile(
                        rawUri = trimmed,
                        file = localFile,
                        explicitDirectory = explicitDirectory,
                    )
                } else {
                    ResolvedUriEndpoint.PrivilegedFile(
                        rawUri = trimmed,
                        absolutePath = localFile.absolutePath,
                        explicitDirectory = explicitDirectory,
                    )
                }
            }
        }
    }

    private fun canAccessDirectly(file: File): Boolean {
        if (isInsideAppOwnedStorage(file)) return true
        explicitDirectFileAccessChecker?.let { return it() }
        if (privilegedShellRunner == null) return true
        val ctx = contextProvider() ?: return true
        if (isInsideContextDirs(file, ctx)) return true
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    private fun isInsideAppOwnedStorage(file: File): Boolean {
        val internalRoot = internalRootProvider() ?: return false
        return isSameOrChildPath(file, internalRoot)
    }

    private fun isInsideContextDirs(file: File, context: Context): Boolean {
        val ownedDirs = listOfNotNull(
            runCatching { context.filesDir }.getOrNull(),
            runCatching { context.cacheDir }.getOrNull(),
            runCatching { context.externalCacheDir }.getOrNull(),
            runCatching { context.getExternalFilesDir(null) }.getOrNull(),
        )
        return ownedDirs.any { dir -> isSameOrChildPath(file, dir) }
    }

    private fun isSameOrChildPath(candidate: File, rootDir: File): Boolean {
        val rootCanonical = runCatching { rootDir.canonicalPath }
            .getOrDefault(rootDir.absolutePath)
            .trimEnd(File.separatorChar)
        val fileCanonical = runCatching { candidate.canonicalPath }
            .getOrDefault(candidate.absolutePath)
        return fileCanonical == rootCanonical ||
            fileCanonical.startsWith("$rootCanonical${File.separator}")
    }

    /**
     * 同后端快路径：`LocalFile -> LocalFile`（支持普通文件与目录）。
     */
    private fun transferLocalToLocal(
        source: ResolvedUriEndpoint.LocalFile,
        target: ResolvedUriEndpoint.LocalFile,
        mode: TransferMode,
        overwrite: Boolean,
        defaultFileName: String?,
    ): SessionFileTransferResult {
        val srcFile = source.file
        if (!srcFile.exists()) {
            return SessionFileTransferResult(
                success = false,
                message = "Source path does not exist: ${srcFile.absolutePath}",
            )
        }
        if (srcFile.isFile && !srcFile.canRead()) {
            return SessionFileTransferResult(
                success = false,
                message = "Source file is not readable: ${srcFile.absolutePath}",
            )
        }

        val destFile = resolveLocalDestFile(
            rawDest = target.file,
            explicitDirectory = target.explicitDirectory,
            sourceFileName = defaultFileName?.takeIf { it.isNotBlank() } ?: srcFile.name,
            allowDirectoryAppend = !srcFile.isDirectory,
        )

        val srcCanonical = runCatching { srcFile.canonicalFile }.getOrDefault(srcFile)
        val destCanonical = runCatching { destFile.canonicalFile }.getOrDefault(destFile)
        if (srcCanonical == destCanonical) {
            return SessionFileTransferResult(
                success = true,
                targetPath = destFile.absolutePath,
                targetUri = destFile.toURI().toString(),
                bytesTransferred = if (destFile.isFile) destFile.length() else 0L,
                sourceDeleted = false,
                message = "Source and target refer to the same file: ${destFile.absolutePath}",
            )
        }

        if (destFile.exists() && !overwrite) {
            return SessionFileTransferResult(
                success = false,
                targetPath = destFile.absolutePath,
                targetUri = destFile.toURI().toString(),
                message = "Target already exists (set overwrite=true to replace): ${destFile.absolutePath}",
            )
        }

        destFile.parentFile?.mkdirs()

        if (srcFile.isDirectory) {
            if (overwrite && destFile.exists()) {
                if (destFile.isDirectory) destFile.deleteRecursively() else destFile.delete()
            }
            if (mode == TransferMode.MOVE) {
                val renamed = srcFile.renameTo(destFile)
                if (!renamed) {
                    srcFile.copyRecursively(destFile, overwrite = overwrite)
                    srcFile.deleteRecursively()
                }
                return SessionFileTransferResult(
                    success = true,
                    targetPath = destFile.absolutePath,
                    targetUri = destFile.toURI().toString(),
                    bytesTransferred = 0L,
                    sourceDeleted = !srcFile.exists(),
                    message = "Moved '${srcFile.absolutePath}' -> '${destFile.absolutePath}'",
                )
            } else {
                srcFile.copyRecursively(destFile, overwrite = overwrite)
                return SessionFileTransferResult(
                    success = true,
                    targetPath = destFile.absolutePath,
                    targetUri = destFile.toURI().toString(),
                    bytesTransferred = 0L,
                    sourceDeleted = false,
                    message = "Copied '${srcFile.absolutePath}' -> '${destFile.absolutePath}'",
                )
            }
        }

        // 普通文件处理
        if (mode == TransferMode.MOVE) {
            val movedDirectly = runCatching {
                Files.move(
                    srcFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)

            val bytes: Long
            val sourceDeleted: Boolean
            if (movedDirectly) {
                bytes = destFile.length()
                sourceDeleted = !srcFile.exists()
            } else {
                bytes = srcFile.inputStream().use { input ->
                    writeStreamAtomically(input, destFile.toPath())
                }
                sourceDeleted = runCatching { Files.deleteIfExists(srcFile.toPath()) }
                    .getOrElse { srcFile.delete() }
            }
            return SessionFileTransferResult(
                success = true,
                targetPath = destFile.absolutePath,
                targetUri = destFile.toURI().toString(),
                bytesTransferred = bytes,
                sourceDeleted = sourceDeleted,
                message = "Moved '${srcFile.absolutePath}' -> '${destFile.absolutePath}'",
            )
        }

        val bytes = srcFile.inputStream().use { input ->
            writeStreamAtomically(input, destFile.toPath())
        }
        return SessionFileTransferResult(
            success = true,
            targetPath = destFile.absolutePath,
            targetUri = destFile.toURI().toString(),
            bytesTransferred = bytes,
            sourceDeleted = false,
            message = "Copied '${srcFile.absolutePath}' -> '${destFile.absolutePath}'",
        )
    }

    /**
     * 同后端快路径：`PrivilegedFile -> PrivilegedFile`（通过 Shizuku/Root 原生 `mv` / `cp -r`）。
     */
    private suspend fun transferPrivilegedToPrivileged(
        source: ResolvedUriEndpoint.PrivilegedFile,
        target: ResolvedUriEndpoint.PrivilegedFile,
        mode: TransferMode,
        overwrite: Boolean,
        defaultFileName: String?,
    ): SessionFileTransferResult {
        val runner = privilegedShellRunner
            ?: return SessionFileTransferResult(
                success = false,
                message = "Privileged shell runner is not available",
            )

        val fallbackName = defaultFileName?.takeIf { it.isNotBlank() }
            ?: SessionAttachment.extractFileNameFromUri(source.absolutePath).ifEmpty { "attachment" }
        val safeName = SessionAttachmentStorage.sanitizeFileName(fallbackName)

        val quotedSource = PrivilegeBackend.shellQuote(source.absolutePath)
        val quotedTarget = PrivilegeBackend.shellQuote(target.absolutePath)
        val quotedSafeName = PrivilegeBackend.shellQuote(safeName)
        val dirModeFlag = if (target.explicitDirectory) "1" else "0"
        val overwriteFlag = if (overwrite) "1" else "0"
        val moveFlag = if (mode == TransferMode.MOVE) "1" else "0"

        val cmd = """
            SRC=$quotedSource
            DST=$quotedTarget
            SAFE_NAME=$quotedSafeName
            if [ ! -e "${'$'}SRC" ]; then echo '__ERR_SOURCE_MISSING__'; exit 0; fi
            if [ ! -d "${'$'}SRC" ] && { [ "$dirModeFlag" = "1" ] || [ -d "${'$'}DST" ]; }; then
                mkdir -p "${'$'}DST" && DST="${'$'}DST/${'$'}SAFE_NAME"
            else
                mkdir -p "$(dirname "${'$'}DST")"
            fi
            if [ "$overwriteFlag" != "1" ] && [ -e "${'$'}DST" ]; then
                echo "__ERR_TARGET_EXISTS__:${'$'}DST"
                exit 0
            fi
            if [ "$overwriteFlag" = "1" ] && [ -e "${'$'}DST" ]; then
                rm -rf "${'$'}DST"
            fi
            if [ "$moveFlag" = "1" ]; then
                mv -f "${'$'}SRC" "${'$'}DST" && echo "__OK__:${'$'}DST"
            else
                cp -r "${'$'}SRC" "${'$'}DST" && echo "__OK__:${'$'}DST"
            fi
        """.trimIndent()

        val res = runner(cmd)
        val out = res.stdout.trim()
        return when {
            out == "__ERR_SOURCE_MISSING__" -> SessionFileTransferResult(
                success = false,
                message = "Source path does not exist: ${source.absolutePath}",
            )
            out.startsWith("__ERR_TARGET_EXISTS__:") -> {
                val existingPath = out.removePrefix("__ERR_TARGET_EXISTS__:")
                SessionFileTransferResult(
                    success = false,
                    targetPath = existingPath,
                    targetUri = File(existingPath).toURI().toString(),
                    message = "Target already exists (set overwrite=true to replace): $existingPath",
                )
            }
            out.contains("__OK__:") -> {
                val finalPath = out.substringAfterLast("__OK__:")
                SessionFileTransferResult(
                    success = true,
                    targetPath = finalPath,
                    targetUri = File(finalPath).toURI().toString(),
                    bytesTransferred = 0L,
                    sourceDeleted = mode == TransferMode.MOVE,
                    message = if (mode == TransferMode.MOVE) {
                        "Moved '${source.absolutePath}' -> '$finalPath'"
                    } else {
                        "Copied '${source.absolutePath}' -> '$finalPath'"
                    },
                )
            }
            else -> SessionFileTransferResult(
                success = false,
                message = "Failed to ${if (mode == TransferMode.MOVE) "move" else "copy"} '${source.absolutePath}' -> '${target.absolutePath}': ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}",
            )
        }
    }

    /**
     * 跨后端通用流式传输路径：`source.openStream -> target.writeStream -> (MOVE 时 source.delete)`。
     */
    private suspend fun transferAcrossBackends(
        source: ResolvedUriEndpoint,
        target: ResolvedUriEndpoint,
        mode: TransferMode,
        overwrite: Boolean,
        defaultFileName: String?,
    ): SessionFileTransferResult {
        val streamResult = openSourceStream(source)
        val inputStream = streamResult.first
            ?: return SessionFileTransferResult(
                success = false,
                message = streamResult.second.ifBlank { "Unable to read source URI: ${source.rawUri}" },
            )

        val effectiveFileName = defaultFileName?.takeIf { it.isNotBlank() }
            ?: SessionAttachmentStorage.inferDisplayName(source.rawUri).ifEmpty { "attachment" }

        val writeResult = inputStream.use { input ->
            writeStreamToTarget(
                target = target,
                input = input,
                overwrite = overwrite,
                defaultFileName = effectiveFileName,
            )
        }
        if (!writeResult.success) {
            return writeResult
        }

        val sourceDeleted = if (mode == TransferMode.MOVE) {
            deleteSourceEndpoint(source)
        } else {
            false
        }

        val message = when {
            mode == TransferMode.MOVE && sourceDeleted ->
                "Moved '${source.rawUri}' -> '${writeResult.targetPath.ifEmpty { writeResult.targetUri }}'"
            mode == TransferMode.MOVE && !sourceDeleted ->
                "Copied '${source.rawUri}' -> '${writeResult.targetPath.ifEmpty { writeResult.targetUri }}' (source could not be deleted)"
            else ->
                "Copied '${source.rawUri}' -> '${writeResult.targetPath.ifEmpty { writeResult.targetUri }}'"
        }

        return writeResult.copy(
            sourceDeleted = sourceDeleted,
            message = message,
        )
    }

    private suspend fun openSourceStream(source: ResolvedUriEndpoint): Pair<InputStream?, String> {
        return when (source) {
            is ResolvedUriEndpoint.LocalFile -> {
                val file = source.file
                if (!file.exists()) {
                    null to "Source file does not exist: ${file.absolutePath}"
                } else if (!file.isFile) {
                    null to "Source path is not a regular file: ${file.absolutePath}"
                } else if (!file.canRead()) {
                    null to "Source file is not readable: ${file.absolutePath}"
                } else {
                    file.inputStream() to ""
                }
            }

            is ResolvedUriEndpoint.PrivilegedFile -> {
                val runner = privilegedShellRunner
                    ?: return null to "Privileged shell runner is not available"
                val quotedSrc = PrivilegeBackend.shellQuote(source.absolutePath)
                val cmd = """
                    if [ ! -e $quotedSrc ]; then echo '__ERR_NOT_FOUND__';
                    elif [ -d $quotedSrc ]; then echo '__ERR_IS_DIR__';
                    else base64 $quotedSrc; fi
                """.trimIndent()
                val res = runner(cmd)
                val trimmed = res.stdout.trim()
                when {
                    trimmed == "__ERR_NOT_FOUND__" ->
                        null to "Source file does not exist: ${source.absolutePath}"
                    trimmed == "__ERR_IS_DIR__" ->
                        null to "Source path is not a regular file: ${source.absolutePath}"
                    !res.isSuccess ->
                        null to "Failed to read source file '${source.absolutePath}': ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
                    else -> {
                        val bytes = runCatching { decodeBase64WhitespaceTolerant(res.stdout) }.getOrElse { error ->
                            return null to "Failed to decode source file '${source.absolutePath}': ${error.message ?: error::class.simpleName}"
                        }
                        ByteArrayInputStream(bytes) to ""
                    }
                }
            }

            is ResolvedUriEndpoint.ContentUri -> {
                val ctx = contextProvider()
                    ?: return null to "Application context is not initialized for content:// URI: ${source.rawUri}"
                val parsed = Uri.parse(source.rawUri)
                val stream = ctx.contentResolver.openInputStream(parsed)
                if (stream != null) {
                    stream to ""
                } else {
                    null to "Unable to open input stream for content URI: ${source.rawUri}"
                }
            }

            is ResolvedUriEndpoint.DataUri -> {
                val bytes = decodeBase64DataUri(source.rawUri)
                if (bytes != null) {
                    ByteArrayInputStream(bytes) to ""
                } else {
                    null to "Unsupported or invalid base64 data URI"
                }
            }
        }
    }

    private suspend fun writeStreamToTarget(
        target: ResolvedUriEndpoint,
        input: InputStream,
        overwrite: Boolean,
        defaultFileName: String,
    ): SessionFileTransferResult {
        return when (target) {
            is ResolvedUriEndpoint.LocalFile -> {
                val destFile = resolveLocalDestFile(
                    rawDest = target.file,
                    explicitDirectory = target.explicitDirectory,
                    sourceFileName = defaultFileName,
                    allowDirectoryAppend = true,
                )
                if (destFile.exists() && !overwrite) {
                    return SessionFileTransferResult(
                        success = false,
                        targetPath = destFile.absolutePath,
                        targetUri = destFile.toURI().toString(),
                        message = "Target already exists (set overwrite=true to replace): ${destFile.absolutePath}",
                    )
                }
                val bytes = writeStreamAtomically(input, destFile.toPath())
                SessionFileTransferResult(
                    success = true,
                    targetPath = destFile.absolutePath,
                    targetUri = destFile.toURI().toString(),
                    bytesTransferred = bytes,
                )
            }

            is ResolvedUriEndpoint.PrivilegedFile -> {
                val runner = privilegedShellRunner
                    ?: return SessionFileTransferResult(
                        success = false,
                        message = "Privileged shell runner is not available",
                    )
                val bytes = input.readBytes()
                val encoded = encodeBase64NoWrap(bytes)
                val safeName = SessionAttachmentStorage.sanitizeFileName(defaultFileName)
                val quotedTarget = PrivilegeBackend.shellQuote(target.absolutePath)
                val quotedSafeName = PrivilegeBackend.shellQuote(safeName)
                val quotedPayload = PrivilegeBackend.shellQuote(encoded)
                val dirModeFlag = if (target.explicitDirectory) "1" else "0"
                val overwriteFlag = if (overwrite) "1" else "0"
                val cmd = """
                    TARGET=$quotedTarget
                    SAFE_NAME=$quotedSafeName
                    if [ "$dirModeFlag" = "1" ] || [ -d "${'$'}TARGET" ]; then
                        mkdir -p "${'$'}TARGET" && TARGET="${'$'}TARGET/${'$'}SAFE_NAME"
                    else
                        mkdir -p "$(dirname "${'$'}TARGET")"
                    fi
                    if [ "$overwriteFlag" != "1" ] && [ -e "${'$'}TARGET" ]; then
                        echo "__ERR_TARGET_EXISTS__:${'$'}TARGET"
                        exit 0
                    fi
                    printf '%s' $quotedPayload | base64 -d > "${'$'}TARGET" && echo "__OK__:${'$'}TARGET"
                """.trimIndent()
                val res = runner(cmd)
                val out = res.stdout.trim()
                when {
                    out.startsWith("__ERR_TARGET_EXISTS__:") -> {
                        val existingPath = out.removePrefix("__ERR_TARGET_EXISTS__:")
                        SessionFileTransferResult(
                            success = false,
                            targetPath = existingPath,
                            targetUri = File(existingPath).toURI().toString(),
                            message = "Target already exists (set overwrite=true to replace): $existingPath",
                        )
                    }
                    out.contains("__OK__:") -> {
                        val finalPath = out.substringAfterLast("__OK__:")
                        SessionFileTransferResult(
                            success = true,
                            targetPath = finalPath,
                            targetUri = File(finalPath).toURI().toString(),
                            bytesTransferred = bytes.size.toLong(),
                        )
                    }
                    else -> SessionFileTransferResult(
                        success = false,
                        message = "Failed to write target '${target.absolutePath}': ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}",
                    )
                }
            }

            is ResolvedUriEndpoint.ContentUri -> {
                val ctx = contextProvider()
                    ?: return SessionFileTransferResult(
                        success = false,
                        message = "Application context is not initialized for content:// target URI",
                    )
                val parsed = Uri.parse(target.rawUri)
                val outputStream = openContentOutputStream(ctx, parsed, overwrite)
                    ?: return SessionFileTransferResult(
                        success = false,
                        message = "Unable to open output stream for target content URI: ${target.rawUri}",
                    )
                val bytes = outputStream.use { out -> copyStreams(input, out) }
                SessionFileTransferResult(
                    success = true,
                    targetPath = target.rawUri,
                    targetUri = target.rawUri,
                    bytesTransferred = bytes,
                )
            }

            is ResolvedUriEndpoint.DataUri -> SessionFileTransferResult(
                success = false,
                message = "Cannot write to a read-only data: URI",
            )
        }
    }

    private suspend fun deleteSourceEndpoint(source: ResolvedUriEndpoint): Boolean {
        return when (source) {
            is ResolvedUriEndpoint.LocalFile ->
                runCatching { Files.deleteIfExists(source.file.toPath()) }
                    .getOrElse { source.file.delete() }

            is ResolvedUriEndpoint.PrivilegedFile -> {
                val runner = privilegedShellRunner ?: return false
                val quoted = PrivilegeBackend.shellQuote(source.absolutePath)
                runner("rm -f $quoted").isSuccess
            }

            is ResolvedUriEndpoint.ContentUri -> {
                val ctx = contextProvider() ?: return false
                val parsed = Uri.parse(source.rawUri)
                tryDeleteContentUri(ctx, parsed)
            }

            is ResolvedUriEndpoint.DataUri -> false
        }
    }

    private fun resolveLocalDestFile(
        rawDest: File,
        explicitDirectory: Boolean,
        sourceFileName: String,
        allowDirectoryAppend: Boolean,
    ): File {
        if (allowDirectoryAppend && (explicitDirectory || rawDest.isDirectory)) {
            val safeName = SessionAttachmentStorage.sanitizeFileName(sourceFileName)
            return File(rawDest, safeName)
        }
        return rawDest
    }

    private fun openContentOutputStream(
        context: Context,
        uri: Uri,
        overwrite: Boolean,
    ): OutputStream? {
        val preferredMode = if (overwrite) "wt" else "w"
        return runCatching { context.contentResolver.openOutputStream(uri, preferredMode) }.getOrNull()
            ?: runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
            ?: runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
    }

    private fun tryDeleteContentUri(context: Context, uri: Uri): Boolean {
        val docDeleted = runCatching {
            DocumentsContract.isDocumentUri(context, uri) &&
                DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
        if (docDeleted) return true
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    companion object {
        internal fun decodeBase64DataUri(dataUri: String): ByteArray? {
            val commaIndex = dataUri.indexOf(',')
            if (commaIndex <= 5) return null
            val header = dataUri.substring(5, commaIndex)
            val payload = dataUri.substring(commaIndex + 1).trim()
            if (!header.contains(";base64", ignoreCase = true) || payload.isEmpty()) {
                return null
            }
            val bytes = java.util.Base64.getDecoder().decode(payload)
            return bytes.takeIf { it.isNotEmpty() }
        }

        internal fun encodeBase64NoWrap(bytes: ByteArray): String {
            return runCatching {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.getOrNull() ?: java.util.Base64.getEncoder().encodeToString(bytes)
        }

        internal fun decodeBase64WhitespaceTolerant(rawBase64: String): ByteArray {
            val compact = rawBase64.replace(Regex("\\s+"), "")
            if (compact.isEmpty()) return ByteArray(0)
            return runCatching {
                Base64.decode(compact, Base64.DEFAULT)
            }.getOrNull() ?: java.util.Base64.getDecoder().decode(compact)
        }

        internal fun copyStreams(input: InputStream, output: OutputStream): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.flush()
            return total
        }

        internal fun writeStreamAtomically(input: InputStream, targetPath: Path): Long {
            targetPath.parent?.let { Files.createDirectories(it) }
            val tmpPath = targetPath.parent.resolve("${targetPath.fileName}.tmp")
            var total = 0L
            try {
                Files.newOutputStream(tmpPath).use { out ->
                    total = copyStreams(input, out)
                }
                moveTmpIntoPlace(tmpPath, targetPath)
            } catch (t: Throwable) {
                runCatching { Files.deleteIfExists(tmpPath) }
                throw t
            }
            return total
        }

        private fun moveTmpIntoPlace(tmpPath: Path, targetPath: Path) {
            try {
                Files.move(
                    tmpPath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Throwable) {
                Files.move(
                    tmpPath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }
}
