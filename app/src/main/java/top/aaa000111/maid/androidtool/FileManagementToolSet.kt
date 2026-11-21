package top.aaa000111.maid.androidtool

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.chat.DefaultUriFileTransfer
import top.aaa000111.maid.chat.SessionAttachment
import top.aaa000111.maid.chat.TransferMode
import top.aaa000111.maid.chat.UriFileTransfer
import top.aaa000111.maid.chat.currentChatSession
import top.aaa000111.maid.shizuku.ShellCommandResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件管理与存储操作工具集：
 * 支持通过普通 Android 所有文件访问权限（[AndroidPrivilege.FILE_MANAGEMENT]，即 `MANAGE_EXTERNAL_STORAGE`）、
 * [AndroidPrivilege.SHIZUKU] 或 [AndroidPrivilege.ROOT] 进行目录浏览、文本文件读写、创建目录、复制/移动/删除文件、
 * 按名称搜索文件及读取 `content://` URI。
 *
 * 当未授予 `FILE_MANAGEMENT` 但持有 `SHIZUKU` 或 `ROOT` 时，将直接通过 Shizuku / Root 通道执行文件操作（不会私自给自己授予系统权限）。
 *
 * 支持权限：`FILE_MANAGEMENT`、`SHIZUKU`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class FileManagementToolSet(
    context: Context,
    private val defaultRootDirProvider: (() -> File)? = null,
    private val directFileAccessChecker: (() -> Boolean)? = null,
    private val privilegedShellRunner: (suspend (String) -> ShellCommandResult)? = null,
) : AndroidToolSet() {

    private val appContext by lazy { context.applicationContext }

    override val name: String = "file_management"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.FILE_MANAGEMENT,
        AndroidPrivilege.SHIZUKU,
        AndroidPrivilege.ROOT,
    )

    private fun canUseDirectFileAccess(): Boolean {
        directFileAccessChecker?.let { return it() }
        if (defaultRootDirProvider != null) return true
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    private suspend fun execPrivileged(command: String): ShellCommandResult {
        privilegedShellRunner?.let { return it(command) }
        return PrivilegeBackend.execShizukuOrRootShell(command)
    }

    private val fileTransfer: UriFileTransfer by lazy {
        DefaultUriFileTransfer(
            contextProvider = { runCatching { appContext }.getOrNull() },
            pathResolver = ::resolveFilePath,
            explicitDirectFileAccessChecker = ::canUseDirectFileAccess,
            privilegedShellRunner = ::execPrivileged,
        )
    }

    @Tool
    @LLMDescription(
        "List files and subdirectories in a directory. " +
            "Pass an absolute path (e.g. '/sdcard/Download') or a relative path under external storage root (leave empty for external storage root).",
    )
    suspend fun listDirectory(
        @LLMDescription("Target directory path (empty or '/sdcard' for external storage root).")
        path: String = "",
        @LLMDescription("Whether to include hidden dot-files (default false).")
        includeHidden: Boolean = false,
        @LLMDescription("Maximum number of entries to return (default 100).")
        maxEntries: Int = 100,
    ): String {
        val dir = resolveFilePath(path)
        val safeLimit = maxEntries.coerceIn(1, 500)

        if (canUseDirectFileAccess()) {
            if (!dir.exists()) {
                return "Directory does not exist: ${dir.absolutePath}"
            }
            if (!dir.isDirectory) {
                return "Path is not a directory: ${dir.absolutePath}"
            }
            val children = dir.listFiles()?.toList()
            if (children != null) {
                val filtered = children
                    .filter { includeHidden || !it.name.startsWith(".") }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))

                val shown = filtered.take(safeLimit)
                return buildString {
                    appendLine("directory=${dir.absolutePath}")
                    appendLine("totalEntries=${filtered.size} (showing=${shown.size})")
                    shown.forEach { entry ->
                        val typeTag = if (entry.isDirectory) "[DIR]" else "[FILE]"
                        val sizeInfo = if (entry.isFile) " (${formatBytes(entry.length())})" else ""
                        val timeInfo = formatTimestamp(entry.lastModified())
                        appendLine("$typeTag ${entry.name}$sizeInfo · $timeInfo")
                    }
                }.trimEnd()
            }
        }

        // 通过 Shizuku / Root 执行目录列表
        val quotedDir = PrivilegeBackend.shellQuote(dir.absolutePath)
        val lsFlags = if (includeHidden) "-1Ap" else "-1p"
        val cmd = """
            if [ ! -e $quotedDir ]; then echo "__ERR_NOT_FOUND__";
            elif [ ! -d $quotedDir ]; then echo "__ERR_NOT_DIR__";
            else ls $lsFlags $quotedDir 2>&1; fi
        """.trimIndent()
        val res = execPrivileged(cmd)
        if (!res.isSuccess && res.stdout.isBlank()) {
            return "Unable to list directory '${dir.absolutePath}': ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
        }
        val out = res.stdout.trim()
        if (out == "__ERR_NOT_FOUND__") {
            return "Directory does not exist: ${dir.absolutePath}"
        }
        if (out == "__ERR_NOT_DIR__") {
            return "Path is not a directory: ${dir.absolutePath}"
        }
        val rawNames = if (out.isEmpty()) {
            emptyList()
        } else {
            out.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "./" && it != "../" }
        }
        val sorted = rawNames.sortedWith(
            compareBy<String>({ !it.endsWith("/") }, { it.removeSuffix("/").lowercase(Locale.ROOT) }),
        )
        val shown = sorted.take(safeLimit)
        return buildString {
            appendLine("directory=${dir.absolutePath}")
            appendLine("totalEntries=${sorted.size} (showing=${shown.size})")
            shown.forEach { rawName ->
                if (rawName.endsWith("/")) {
                    appendLine("[DIR] ${rawName.removeSuffix("/")}")
                } else {
                    appendLine("[FILE] $rawName")
                }
            }
        }.trimEnd()
    }

    @Tool
    @LLMDescription(
        "Read UTF-8 text content from a session file attachment ID (e.g. 'file_1'), a file path, 'file://' URI, or 'content://' URI.",
    )
    suspend fun readTextFile(
        @LLMDescription("Session attachment ID (e.g. 'file_1'), file path (e.g. '/sdcard/Download/notes.txt'), or URI ('content://...' / 'file://...').")
        pathOrUri: String,
        @LLMDescription("Maximum number of characters to return (default 16000).")
        maxChars: Int = 16_000,
    ): String {
        val rawTrimmed = pathOrUri.trim()
        if (rawTrimmed.isEmpty()) {
            return "File path or URI must not be blank"
        }
        val trimmed = currentChatSession()?.accessAttachmentUri(rawTrimmed)
            ?: rawTrimmed
        val safeMaxChars = maxChars.coerceIn(256, 100_000)

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                val uri = Uri.parse(trimmed)
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return "Unable to open content URI: $trimmed"
                truncateText(String(bytes, StandardCharsets.UTF_8), safeMaxChars)
            }.getOrElse { error ->
                "Failed to read '$trimmed': ${error.message ?: error::class.simpleName}"
            }
        }

        val file = resolveFilePath(trimmed)
        if (canUseDirectFileAccess()) {
            if (!file.exists()) {
                return "File does not exist: ${file.absolutePath}"
            }
            if (!file.isFile) {
                return "Path is not a regular file: ${file.absolutePath}"
            }
            val directRead = runCatching {
                String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
            }.getOrNull()
            if (directRead != null) {
                return truncateText(directRead, safeMaxChars)
            }
        }

        // 通过 Shizuku / Root 读取文件
        val quotedPath = PrivilegeBackend.shellQuote(file.absolutePath)
        val cmd = """
            if [ ! -e $quotedPath ]; then echo "__ERR_NOT_FOUND__";
            elif [ -d $quotedPath ]; then echo "__ERR_IS_DIR__";
            else cat $quotedPath; fi
        """.trimIndent()
        val res = execPrivileged(cmd)
        val stdout = res.stdout
        if (stdout.trim() == "__ERR_NOT_FOUND__") {
            return "File does not exist: ${file.absolutePath}"
        }
        if (stdout.trim() == "__ERR_IS_DIR__") {
            return "Path is not a regular file: ${file.absolutePath}"
        }
        if (!res.isSuccess) {
            return "Failed to read '${file.absolutePath}': ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
        }
        return truncateText(stdout, safeMaxChars)
    }

    private fun truncateText(rawText: String, safeMaxChars: Int): String {
        return if (rawText.length <= safeMaxChars) {
            rawText
        } else {
            rawText.take(safeMaxChars) +
                "\n\n... [Truncated: total ${rawText.length} chars, showing first $safeMaxChars chars]"
        }
    }

    @Tool
    @LLMDescription("Write or append UTF-8 text content to a file path, creating parent directories if needed.")
    suspend fun writeTextFile(
        @LLMDescription("Target file path (absolute or relative to external storage root).")
        path: String,
        @LLMDescription("Text content to write.")
        content: String,
        @LLMDescription("If true, append to existing file; if false (default), overwrite.")
        append: Boolean = false,
    ): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            return "Target file path must not be blank"
        }
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            return "writeTextFile expects a file system path rather than a read-only content:// URI"
        }
        val file = resolveFilePath(trimmed)

        if (canUseDirectFileAccess()) {
            val directResult = runCatching {
                file.parentFile?.let { parent ->
                    if (!parent.exists()) {
                        parent.mkdirs()
                    }
                }
                if (append && file.exists()) {
                    file.appendText(content, StandardCharsets.UTF_8)
                } else {
                    Files.write(file.toPath(), content.toByteArray(StandardCharsets.UTF_8))
                }
                "Wrote ${content.length} chars (${file.length()} bytes) to ${file.absolutePath} (append=$append)"
            }.getOrNull()
            if (directResult != null) {
                return directResult
            }
        }

        // 通过 Shizuku / Root 写入文件（Base64 解码避免转义与特殊字符问题）
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val encoded = encodeBase64NoWrap(bytes)
        val parentPath = file.parentFile?.absolutePath ?: "/"
        val quotedParent = PrivilegeBackend.shellQuote(parentPath)
        val quotedTarget = PrivilegeBackend.shellQuote(file.absolutePath)
        val redirectOp = if (append) ">>" else ">"
        val cmd = "mkdir -p $quotedParent && printf '%s' ${PrivilegeBackend.shellQuote(encoded)} | base64 -d $redirectOp $quotedTarget"
        val res = execPrivileged(cmd)
        return if (res.isSuccess) {
            "Wrote ${content.length} chars (${bytes.size} bytes) to ${file.absolutePath} (append=$append)"
        } else {
            "Failed to write file '${file.absolutePath}': ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
        }
    }

    private fun encodeBase64NoWrap(bytes: ByteArray): String {
        return runCatching {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull() ?: java.util.Base64.getEncoder().encodeToString(bytes)
    }

    @Tool
    @LLMDescription("Create a directory (including any missing parent directories).")
    suspend fun createDirectory(
        @LLMDescription("Directory path to create.")
        path: String,
    ): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            return "Directory path must not be blank"
        }
        val dir = resolveFilePath(trimmed)

        if (canUseDirectFileAccess()) {
            if (dir.exists()) {
                return if (dir.isDirectory) {
                    "Directory already exists: ${dir.absolutePath}"
                } else {
                    "A file with the same path already exists: ${dir.absolutePath}"
                }
            }
            if (dir.mkdirs() || dir.isDirectory) {
                return "Created directory: ${dir.absolutePath}"
            }
        }

        val quotedDir = PrivilegeBackend.shellQuote(dir.absolutePath)
        val cmd = """
            if [ -d $quotedDir ]; then echo "__ALREADY_DIR__";
            elif [ -e $quotedDir ]; then echo "__EXISTS_FILE__";
            else mkdir -p $quotedDir && echo "__CREATED__"; fi
        """.trimIndent()
        val res = execPrivileged(cmd)
        return when (res.stdout.trim()) {
            "__ALREADY_DIR__" -> "Directory already exists: ${dir.absolutePath}"
            "__EXISTS_FILE__" -> "A file with the same path already exists: ${dir.absolutePath}"
            "__CREATED__" -> "Created directory: ${dir.absolutePath}"
            else -> "Failed to create directory '${dir.absolutePath}': ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Copy or move/rename a file or directory to a new path or URI.")
    suspend fun copyOrMoveFile(
        @LLMDescription("Source file, directory path, or URI.")
        sourcePath: String,
        @LLMDescription("Destination file, directory path, or URI.")
        targetPath: String,
        @LLMDescription("If true, move/rename the source; if false (default), copy.")
        move: Boolean = false,
        @LLMDescription("Whether to overwrite destination if it already exists (default false).")
        overwrite: Boolean = false,
    ): String {
        val result = fileTransfer.transfer(
            sourceUri = sourcePath.trim(),
            targetUri = targetPath.trim(),
            mode = if (move) TransferMode.MOVE else TransferMode.COPY,
            overwrite = overwrite,
        )
        return result.message.ifBlank {
            if (result.success) {
                "${if (move) "Moved" else "Copied"} '$sourcePath' -> '${result.targetPath.ifBlank { targetPath }}'"
            } else {
                "Failed to ${if (move) "move" else "copy"} '$sourcePath' -> '$targetPath'"
            }
        }
    }

    @Tool
    @LLMDescription("Delete a file or directory.")
    suspend fun deleteFileOrDirectory(
        @LLMDescription("File or directory path to delete.")
        path: String,
        @LLMDescription("If true, allow deleting non-empty directories recursively (default false).")
        recursive: Boolean = false,
    ): String {
        val target = resolveFilePath(path)
        if (isProtectedRootDirectory(target)) {
            return "Refusing to delete protected root storage directory: ${target.absolutePath}"
        }

        if (canUseDirectFileAccess()) {
            if (!target.exists()) {
                return "Path does not exist: ${target.absolutePath}"
            }
            if (target.isDirectory) {
                val children = target.listFiles()
                if (!children.isNullOrEmpty() && !recursive) {
                    return "Directory is not empty (set recursive=true to delete recursively): ${target.absolutePath}"
                }
            }
            val deleted = if (target.isDirectory && recursive) {
                target.deleteRecursively()
            } else {
                target.delete()
            }
            if (deleted) {
                return "Deleted: ${target.absolutePath}"
            }
        }

        // 通过 Shizuku / Root 删除
        val quotedTarget = PrivilegeBackend.shellQuote(target.absolutePath)
        val deleteAction = if (recursive) {
            "rm -rf $quotedTarget"
        } else {
            "if [ -d $quotedTarget ]; then rmdir $quotedTarget; else rm -f $quotedTarget; fi"
        }
        val cmd = """
            if [ ! -e $quotedTarget ]; then echo '__ERR_NOT_FOUND__'; exit 0; fi
            $deleteAction && echo '__DELETED__'
        """.trimIndent()
        val res = execPrivileged(cmd)
        val out = res.stdout.trim()
        return when {
            out == "__ERR_NOT_FOUND__" -> "Path does not exist: ${target.absolutePath}"
            out.endsWith("__DELETED__") -> "Deleted: ${target.absolutePath}"
            !recursive && res.stderr.contains("not empty", ignoreCase = true) ->
                "Directory is not empty (set recursive=true to delete recursively): ${target.absolutePath}"

            else -> "Failed to delete '${target.absolutePath}': ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Search for files or directories under a directory whose name contains the given keyword (case-insensitive).")
    suspend fun searchFiles(
        @LLMDescription("Substring to match against file or folder names.")
        nameQuery: String,
        @LLMDescription("Root directory to search from (empty for external storage root).")
        directoryPath: String = "",
        @LLMDescription("Maximum directory depth to traverse (default 4, max 10).")
        maxDepth: Int = 4,
        @LLMDescription("Maximum number of matched entries to return (default 50).")
        maxResults: Int = 50,
    ): String {
        val query = nameQuery.trim()
        if (query.isEmpty()) {
            return "nameQuery must not be blank"
        }
        val rootDir = resolveFilePath(directoryPath)
        val safeDepth = maxDepth.coerceIn(1, 10)
        val safeMaxResults = maxResults.coerceIn(1, 200)

        if (canUseDirectFileAccess()) {
            if (!rootDir.exists() || !rootDir.isDirectory) {
                return "Search directory does not exist or is not a directory: ${rootDir.absolutePath}"
            }

            val matches = mutableListOf<File>()
            fun traverse(current: File, depth: Int) {
                if (matches.size >= safeMaxResults || depth > safeDepth) return
                val children = current.listFiles() ?: return
                for (child in children) {
                    if (child.name.contains(query, ignoreCase = true)) {
                        matches += child
                        if (matches.size >= safeMaxResults) return
                    }
                    if (child.isDirectory && depth < safeDepth) {
                        traverse(child, depth + 1)
                    }
                }
            }
            traverse(rootDir, 1)

            return buildString {
                appendLine("searchRoot=${rootDir.absolutePath} query='$query' matched=${matches.size}")
                matches.forEach { file ->
                    val tag = if (file.isDirectory) "[DIR]" else "[FILE]"
                    val sizeStr = if (file.isFile) " (${formatBytes(file.length())})" else ""
                    appendLine("$tag ${file.absolutePath}$sizeStr")
                }
            }.trimEnd()
        }

        // 通过 Shizuku / Root 使用 find 搜索
        val quotedRoot = PrivilegeBackend.shellQuote(rootDir.absolutePath)
        val quotedPattern = PrivilegeBackend.shellQuote("*$query*")
        val cmd = """
            if [ ! -d $quotedRoot ]; then echo '__ERR_NOT_DIR__'; exit 0; fi
            find $quotedRoot -maxdepth $safeDepth -iname $quotedPattern 2>/dev/null | head -n ${safeMaxResults + 1}
        """.trimIndent()
        val res = execPrivileged(cmd)
        val out = res.stdout.trim()
        if (out == "__ERR_NOT_DIR__") {
            return "Search directory does not exist or is not a directory: ${rootDir.absolutePath}"
        }
        val lines = out.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != rootDir.absolutePath }
            .take(safeMaxResults)
        return buildString {
            appendLine("searchRoot=${rootDir.absolutePath} query='$query' matched=${lines.size}")
            lines.forEach { matchedPath ->
                appendLine("[MATCH] $matchedPath")
            }
        }.trimEnd()
    }

    @Tool
    @LLMDescription(
        "Move a file from a file path, 'file://' URI, or 'content://' URI into the current chat session's internal storage " +
            "with a human-readable description and optional file name. Returns the newly registered session attachment ID, fileName, description, and internal URI.",
    )
    suspend fun importFileToSession(
        @LLMDescription("Source file path (e.g. '/sdcard/Download/report.pdf'), 'file://' URI, or 'content://' URI.")
        uri: String,
        @LLMDescription("Human-readable description of the file's content and purpose.")
        description: String = "",
        @LLMDescription("Optional display file name (including extension); inferred from URI if left blank.")
        fileName: String = "",
    ): String {
        val rawUri = uri.trim()
        if (rawUri.isEmpty()) {
            return "Source uri must not be blank"
        }
        val session = currentChatSession()
            ?: return "No active chat session available to move file into"

        val normalizedSourceUri = if (
            rawUri.startsWith("content://", ignoreCase = true) ||
            rawUri.startsWith("data:", ignoreCase = true) ||
            rawUri.startsWith("file:", ignoreCase = true)
        ) {
            rawUri
        } else {
            resolveFilePath(rawUri).toURI().toString()
        }

        return runCatching {
            val moved = session.moveFileToSessionWithResult(
                uri = normalizedSourceUri,
                description = description,
                fileName = fileName,
                fileTransfer = fileTransfer,
            )
            formatSessionImportResult(
                attachment = moved.attachment,
                sourceDeleted = moved.transfer.sourceDeleted,
                bytesTransferred = moved.transfer.bytesTransferred,
            )
        }.getOrElse { error ->
            "Failed to move file into session: ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription(
        "Export a file stored in the current chat session (by its attachment ID such as 'file_1' or 'img_1') " +
            "to a specified destination path, 'file://' URI, 'content://' URI, or directory (which automatically appends the attachment's fileName).",
    )
    suspend fun exportFileFromSession(
        @LLMDescription("Session attachment ID (e.g. 'file_1', 'img_1').")
        attachmentId: String,
        @LLMDescription("Destination file path, directory path, 'file://' URI, or 'content://' URI.")
        targetUri: String,
        @LLMDescription("Whether to overwrite if destination file already exists (default true).")
        overwrite: Boolean = true,
    ): String {
        val trimmedId = attachmentId.trim()
        val trimmedTarget = targetUri.trim()
        if (trimmedId.isEmpty()) {
            return "attachmentId must not be blank"
        }
        if (trimmedTarget.isEmpty()) {
            return "targetUri must not be blank"
        }
        val session = currentChatSession()
            ?: return "No active chat session available to export file from"

        val result = session.exportFileFromSession(
            attachmentId = trimmedId,
            targetUri = trimmedTarget,
            overwrite = overwrite,
            fileTransfer = fileTransfer,
        )
        return if (result.success) {
            val dest = result.targetPath.ifBlank { result.targetUri }
            "Exported '$trimmedId' -> '$dest' (${result.bytesTransferred} bytes)"
        } else {
            result.message.ifBlank { "Failed to export '$trimmedId'" }
        }
    }

    private fun formatSessionImportResult(
        attachment: SessionAttachment,
        sourceDeleted: Boolean,
        bytesTransferred: Long,
    ): String = buildString {
        appendLine("Imported file into session:")
        appendLine("id=${attachment.id}")
        appendLine("fileName=${attachment.normalizedFileName}")
        appendLine("description=${attachment.normalizedDescription}")
        appendLine("uri=${attachment.uri}")
        appendLine("sourceDeleted=$sourceDeleted")
        append("bytes=$bytesTransferred (${formatBytes(bytesTransferred)})")
    }

    @Tool
    @LLMDescription("Inspect metadata (existence, type, size, permissions, MIME type, last modified time) of a session attachment ID (e.g. 'file_1'), file path, or content:// URI.")
    suspend fun getFileDetails(
        @LLMDescription("Session attachment ID (e.g. 'file_1'), file path, or content:// / file:// URI.")
        pathOrUri: String,
    ): String {
        val rawTrimmed = pathOrUri.trim()
        if (rawTrimmed.isEmpty()) {
            return "pathOrUri must not be blank"
        }
        val activeSession = currentChatSession()
        val matchedAttachment = activeSession?.accessAttachment(rawTrimmed)
        val trimmed = matchedAttachment?.uri ?: rawTrimmed
        val attachmentHeader = matchedAttachment?.let { att ->
            buildString {
                appendLine("attachmentId=${att.id}")
                appendLine("fileName=${att.normalizedFileName}")
                appendLine("description=${att.normalizedDescription}")
            }
        }.orEmpty()
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            return attachmentHeader + inspectContentUri(trimmed)
        }
        val file = resolveFilePath(trimmed)

        if (canUseDirectFileAccess()) {
            if (!file.exists()) {
                return "${attachmentHeader}path=${file.absolutePath}\nexists=false"
            }
            val type = when {
                file.isDirectory -> "directory"
                file.isFile -> "file"
                else -> "other"
            }
            return buildString {
                append(attachmentHeader)
                appendLine("path=${file.absolutePath}")
                appendLine("exists=true")
                appendLine("type=$type")
                appendLine("sizeBytes=${file.length()} (${formatBytes(file.length())})")
                appendLine("canRead=${file.canRead()}")
                appendLine("canWrite=${file.canWrite()}")
                append("lastModified=${formatTimestamp(file.lastModified())}")
            }
        }

        // 通过 Shizuku / Root 查询 stat
        val quotedPath = PrivilegeBackend.shellQuote(file.absolutePath)
        val cmd = """
            if [ ! -e $quotedPath ]; then echo '__NOT_FOUND__';
            elif [ -d $quotedPath ]; then echo 'type=directory'; ls -ld $quotedPath;
            else echo 'type=file'; ls -l $quotedPath; fi
        """.trimIndent()
        val res = execPrivileged(cmd)
        val out = res.stdout.trim()
        if (out == "__NOT_FOUND__") {
            return "${attachmentHeader}path=${file.absolutePath}\nexists=false"
        }
        return buildString {
            append(attachmentHeader)
            appendLine("path=${file.absolutePath}")
            appendLine("exists=true")
            append(out)
        }
    }

    private fun inspectContentUri(rawUri: String): String {
        return runCatching {
            val uri = Uri.parse(rawUri)
            val resolver = appContext.contentResolver
            val mimeType = resolver.getType(uri) ?: "unknown"
            var displayName = uri.lastPathSegment.orEmpty()
            var sizeBytes = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx)
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            sizeBytes = cursor.getLong(sizeIdx)
                        }
                    }
                }
            buildString {
                appendLine("uri=$rawUri")
                appendLine("displayName=$displayName")
                appendLine("mimeType=$mimeType")
                if (sizeBytes >= 0L) {
                    append("sizeBytes=$sizeBytes (${formatBytes(sizeBytes)})")
                } else {
                    append("sizeBytes=unknown")
                }
            }
        }.getOrElse { error ->
            "Failed to inspect content URI '$rawUri': ${error.message}"
        }
    }

    internal fun resolveFilePath(rawPath: String): File {
        val trimmed = rawPath.trim()
        val rootDir = resolveDefaultRootDir()
        if (trimmed.isEmpty() || trimmed == "/sdcard" || trimmed == "/sdcard/") {
            return rootDir
        }
        if (trimmed.startsWith("file:", ignoreCase = true)) {
            return runCatching { File(java.net.URI(trimmed)) }.getOrElse {
                val pathPart = runCatching { Uri.parse(trimmed).path }.getOrNull()
                    ?: trimmed.removePrefix("file://").removePrefix("file:")
                File(pathPart)
            }
        }
        if (trimmed.startsWith("/sdcard/")) {
            val relative = trimmed.removePrefix("/sdcard/")
            return File(rootDir, relative)
        }
        val candidate = File(trimmed)
        return if (candidate.isAbsolute) {
            candidate
        } else {
            File(rootDir, trimmed)
        }
    }

    private fun resolveDefaultRootDir(): File {
        defaultRootDirProvider?.invoke()?.let { return it }
        val ext = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (ext != null) return ext
        val appFiles = runCatching { appContext.filesDir }.getOrNull()
        return appFiles ?: File("/storage/emulated/0")
    }

    private fun isProtectedRootDirectory(target: File): Boolean {
        val canonical = runCatching { target.canonicalPath }.getOrDefault(target.absolutePath)
            .trimEnd('/')
        val defaultRoot = runCatching { resolveDefaultRootDir().canonicalPath }
            .getOrDefault(resolveDefaultRootDir().absolutePath)
            .trimEnd('/')
        val protectedPaths = setOf(
            "",
            "/",
            "/sdcard",
            "/storage",
            "/storage/emulated",
            "/storage/emulated/0",
            defaultRoot,
        )
        return canonical in protectedPaths
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private fun formatTimestamp(epochMillis: Long): String {
        if (epochMillis <= 0L) return "unknown"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
    }
}
