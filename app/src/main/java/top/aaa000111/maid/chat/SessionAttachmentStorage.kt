package top.aaa000111.maid.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * 会话文件移入（导入到内部目录）或导出（从内部目录写出到目标位置）的执行结果。
 */
data class SessionFileTransferResult(
    val success: Boolean,
    val targetPath: String = "",
    val targetUri: String = "",
    val bytesTransferred: Long = 0L,
    val sourceDeleted: Boolean = false,
    val message: String = "",
)

/**
 * 负责会话内文件与图片附件（`id` / `fileName` / `description` / `uri`）的内部路径规划、文件名推断与生命周期清理，
 * 并将底层文件搬运统一委托给 [UriFileTransfer]（`uri -> uri` 传输层）：
 * - 当第一次访问某个附件文件时，如果其 `uri` 不在应用内部目录（`<internalRoot>/<sessionId>/<fileId>`），
 *   则通过 [ensureCopiedToInternal] 复制到内部目录并更新 `uri`；
 * - 通过 [moveSourceToInternal] 将外部 URI 移动到会话内部目录 `<internalRoot>/<sessionId>/<fileId>`；
 * - 通过 [exportFromInternal] 将会话内部文件导出到指定目标 URI（支持本地路径、`file://`、`content://` 或目标目录）。
 */
object SessionAttachmentStorage {

    private val imageExtensions: Set<String> = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif",
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var customInternalRootDir: File? = null

    @Volatile
    private var customFileTransfer: UriFileTransfer? = null

    private val builtInFileTransfer: UriFileTransfer = DefaultUriFileTransfer()

    private val builtInLocalCopyTransfer: UriFileTransfer = DefaultUriFileTransfer(
        privilegedShellRunner = null,
    )

    val defaultFileTransfer: UriFileTransfer
        get() = customFileTransfer ?: builtInFileTransfer

    fun init(context: Context) {
        val resolved = runCatching { context.applicationContext }.getOrNull() ?: context
        appContext = resolved
    }

    internal fun appContextOrNull(): Context? = appContext

    internal fun setCustomInternalRootDirForTest(rootDir: File?) {
        customInternalRootDir = rootDir
    }

    internal fun setCustomFileTransferForTest(transfer: UriFileTransfer?) {
        customFileTransfer = transfer
    }

    internal fun resetForTest() {
        appContext = null
        customInternalRootDir = null
        customFileTransfer = null
    }

    fun internalRootDirOrNull(): File? {
        customInternalRootDir?.let { return it }
        return runCatching { appContext?.filesDir }.getOrNull()
    }

    fun sessionDir(sessionId: String, rootDir: File? = internalRootDirOrNull()): File? {
        val base = rootDir ?: return null
        val safeSession = sanitizeSegment(sessionId).ifEmpty { "default_session" }
        return File(base, safeSession)
    }

    fun targetInternalFile(
        sessionId: String,
        fileId: String,
        rootDir: File? = internalRootDirOrNull(),
    ): File? {
        val dir = sessionDir(sessionId = sessionId, rootDir = rootDir) ?: return null
        val safeFileId = sanitizeSegment(fileId).ifEmpty { "attachment" }
        return File(dir, safeFileId)
    }

    /**
     * 判断给定 [rawUri] 是否已经位于当前会话的内部目录 `<internalRoot>/<sessionId>/` 下。
     */
    fun isAlreadyInInternalSessionDir(
        rawUri: String,
        sessionId: String,
        rootDir: File? = internalRootDirOrNull(),
    ): Boolean {
        val sessionDirectory = sessionDir(sessionId = sessionId, rootDir = rootDir) ?: return false
        val localFile = resolveLocalFileOrNull(rawUri) ?: return false
        val sessionCanonical = runCatching { sessionDirectory.canonicalPath }
            .getOrDefault(sessionDirectory.absolutePath)
            .trimEnd(File.separatorChar)
        val fileCanonical = runCatching { localFile.canonicalPath }
            .getOrDefault(localFile.absolutePath)
        return (fileCanonical == sessionCanonical) ||
            fileCanonical.startsWith("$sessionCanonical${File.separator}")
    }

    /**
     * 首次访问附件时确保其位于内部目录 `<internalRoot>/<sessionId>/<fileId>`：
     * - 若当前内部文件已存在且非空，直接返回内部 URI；
     * - 否则通过底层 [UriFileTransfer]（`COPY` 模式）从 `uri` 或 `originalUri` 复制到内部文件；
     * - 若当前运行环境未挂载文件系统或源 URI 无法复制（如远程 `https://` 或测试虚拟 URI），则保留原 URI 返回。
     */
    fun ensureCopiedToInternal(
        sessionId: String,
        attachment: SessionAttachment,
        rootDir: File? = internalRootDirOrNull(),
    ): SessionAttachment {
        val targetFile = targetInternalFile(
            sessionId = sessionId,
            fileId = attachment.normalizedId,
            rootDir = rootDir,
        ) ?: return attachment

        if (targetFile.isFile && targetFile.length() > 0L) {
            val internalUri = targetFile.toURI().toString()
            return if (attachment.uri == internalUri) attachment else attachment.copy(uri = internalUri)
        }

        if (isAlreadyInInternalSessionDir(attachment.normalizedUri, sessionId, rootDir)) {
            val existing = resolveLocalFileOrNull(attachment.normalizedUri)
            if (existing != null && existing.isFile) {
                return attachment.copy(uri = existing.toURI().toString())
            }
        }

        val candidates = listOf(attachment.normalizedUri, attachment.normalizedOriginalUri)
            .filter { it.isNotEmpty() }
            .distinct()
        val targetUri = targetFile.toURI().toString()

        for (candidate in candidates) {
            val result = runCatching {
                runBlocking {
                    builtInLocalCopyTransfer.transfer(
                        sourceUri = candidate,
                        targetUri = targetUri,
                        mode = TransferMode.COPY,
                        overwrite = true,
                    )
                }
            }.getOrNull()
            if (result != null && result.success) {
                return attachment.copy(uri = result.targetUri.ifBlank { targetUri })
            }
        }
        return attachment
    }

    /**
     * 通过统一 [UriFileTransfer]（默认 `MOVE`，可指定 `COPY`）将外部源 URI 写入会话内部目录
     * `<internalRoot>/<sessionId>/<fileId>`。
     */
    suspend fun moveSourceToInternal(
        sessionId: String,
        fileId: String,
        sourceUri: String,
        rootDir: File? = internalRootDirOrNull(),
        fileTransfer: UriFileTransfer = defaultFileTransfer,
        mode: TransferMode = TransferMode.MOVE,
    ): SessionFileTransferResult {
        val trimmedSource = sourceUri.trim()
        if (trimmedSource.isEmpty()) {
            return SessionFileTransferResult(
                success = false,
                message = "Source URI must not be blank",
            )
        }
        val targetFile = targetInternalFile(
            sessionId = sessionId,
            fileId = fileId,
            rootDir = rootDir,
        ) ?: return SessionFileTransferResult(
            success = false,
            message = "Internal session storage directory is not initialized",
        )
        return fileTransfer.transfer(
            sourceUri = trimmedSource,
            targetUri = targetFile.toURI().toString(),
            mode = mode,
            overwrite = true,
        )
    }

    /**
     * 通过统一 [UriFileTransfer]（`COPY` 模式）将会话内附件导出到指定目标 [targetUri]，
     * 并在目标为目录时自动使用附件文件名（[SessionAttachment.normalizedFileName]）。
     */
    suspend fun exportFromInternal(
        sessionId: String,
        attachment: SessionAttachment,
        targetUri: String,
        overwrite: Boolean = true,
        rootDir: File? = internalRootDirOrNull(),
        fileTransfer: UriFileTransfer = defaultFileTransfer,
    ): SessionFileTransferResult {
        val trimmedTarget = targetUri.trim()
        if (trimmedTarget.isEmpty()) {
            return SessionFileTransferResult(
                success = false,
                message = "Target URI must not be blank",
            )
        }
        val resolvedAttachment = ensureCopiedToInternal(
            sessionId = sessionId,
            attachment = attachment,
            rootDir = rootDir,
        )
        val defaultName = resolvedAttachment.normalizedFileName
            .ifBlank { resolvedAttachment.normalizedId.ifEmpty { "attachment" } }
        return fileTransfer.transfer(
            sourceUri = resolvedAttachment.normalizedUri,
            targetUri = trimmedTarget,
            mode = TransferMode.COPY,
            overwrite = overwrite,
            defaultFileName = defaultName,
        )
    }

    /**
     * 从 URI 中提取可读文件名（`content://` 优先查询 `OpenableColumns.DISPLAY_NAME`，否则从路径提取并做 URL 解码）。
     */
    fun inferDisplayName(rawUri: String): String {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val ctx = appContext
            if (ctx != null) {
                val queried = runCatching {
                    val parsed = Uri.parse(trimmed)
                    ctx.contentResolver.query(
                        parsed,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                        } else {
                            null
                        }
                    }
                }.getOrNull()
                if (!queried.isNullOrBlank()) {
                    return queried.trim()
                }
            }
        }
        return SessionAttachment.extractFileNameFromUri(trimmed)
    }

    /**
     * 根据文件名扩展名或 `content://` MIME 类型自动推断附件是否为图片。
     */
    fun inferIsImage(
        fileName: String = "",
        rawUri: String = "",
    ): Boolean {
        val trimmedUri = rawUri.trim()
        if (trimmedUri.startsWith("content://", ignoreCase = true)) {
            val mime = runCatching { appContext?.contentResolver?.getType(Uri.parse(trimmedUri)) }.getOrNull()
            if (!mime.isNullOrBlank()) {
                return mime.startsWith("image/", ignoreCase = true)
            }
        }
        if (trimmedUri.startsWith("data:image/", ignoreCase = true)) {
            return true
        }
        val candidateName = fileName.trim().ifEmpty { SessionAttachment.extractFileNameFromUri(trimmedUri) }
        val ext = candidateName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.isNotEmpty() && ext != candidateName.lowercase(Locale.ROOT) && ext in imageExtensions
    }

    /**
     * 清理文件名中的路径分隔符与非法字符，保留中文/Unicode 文件名与扩展名。
     */
    fun sanitizeFileName(raw: String, fallback: String = "attachment"): String {
        val base = raw.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F:*?\"<>|]"), "_")
            .trim()
            .trim('.')
        return if (base.isEmpty() || base == "." || base == "..") fallback else base
    }

    /**
     * 删除指定会话在内部目录 `<internalRoot>/<sessionId>` 下保存的所有附件文件。
     */
    fun deleteSessionInternalFiles(
        sessionId: String,
        rootDir: File? = internalRootDirOrNull(),
    ) {
        val dir = sessionDir(sessionId = sessionId, rootDir = rootDir) ?: return
        runCatching {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    /**
     * 删除所有会话列表对应的内部附件目录。
     */
    fun deleteAllSessionsInternalFiles(
        sessionIds: Iterable<String>,
        rootDir: File? = internalRootDirOrNull(),
    ) {
        sessionIds.forEach { id ->
            deleteSessionInternalFiles(sessionId = id, rootDir = rootDir)
        }
    }

    internal fun resolveLocalFileOrNull(rawUriOrPath: String): File? {
        val trimmed = rawUriOrPath.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true)
        ) {
            return null
        }
        if (trimmed.startsWith("file:", ignoreCase = true)) {
            return runCatching { File(URI(trimmed)) }.getOrElse {
                val pathPart = runCatching { Uri.parse(trimmed).path }.getOrNull()
                    ?: trimmed.removePrefix("file://").removePrefix("file:")
                File(pathPart)
            }
        }
        return File(trimmed)
    }

    internal fun sanitizeSegment(raw: String): String {
        return raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
