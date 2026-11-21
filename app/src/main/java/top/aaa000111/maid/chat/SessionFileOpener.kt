package top.aaa000111.maid.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

data class FileOpenResult(
    val success: Boolean,
    val message: String = "",
)

/**
 * 负责安全地通过系统应用打开会话内部文件或外部 URI：
 * - 对应用内部 `<filesDir>/<session_id>/<file_id>` 这类无扩展名文件，会在 `<cacheDir>/open_files/` 下
 *   按原始文件名建立可读副本并通过 `FileProvider` 授权，确保第三方应用正确识别文件名与扩展名；
 * - 对 `content://` URI 直接携带 `FLAG_GRANT_READ_URI_PERMISSION` 发起 `ACTION_VIEW`；
 * - 若具体 MIME 类型无匹配应用，自动回退到通配类型系统选择器。
 */
object SessionFileOpener {

    private const val OPEN_CACHE_DIR_NAME = "open_files"

    private val fallbackMimeTypes: Map<String, String> = mapOf(
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "json" to "application/json",
        "xml" to "application/xml",
        "yaml" to "text/plain",
        "yml" to "text/plain",
        "csv" to "text/csv",
        "html" to "text/html",
        "htm" to "text/html",
        "kt" to "text/plain",
        "java" to "text/plain",
        "py" to "text/plain",
        "sh" to "text/plain",
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
    )

    fun resolveMimeType(
        context: Context? = null,
        fileName: String = "",
        rawUri: String = "",
    ): String {
        val trimmedUri = rawUri.trim()
        if (context != null && trimmedUri.startsWith("content://", ignoreCase = true)) {
            val queried = runCatching { context.contentResolver.getType(Uri.parse(trimmedUri)) }.getOrNull()
            if (!queried.isNullOrBlank() && queried != "application/octet-stream") {
                return queried
            }
        }
        val candidateName = fileName.trim().ifEmpty {
            SessionAttachment.extractFileNameFromUri(trimmedUri)
        }
        val ext = candidateName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isNotEmpty() && ext != candidateName.lowercase(Locale.ROOT)) {
            val fromAndroid = runCatching {
                MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
            }.getOrNull()
            if (!fromAndroid.isNullOrBlank()) {
                return fromAndroid
            }
            fallbackMimeTypes[ext]?.let { return it }
        }
        return "*/*"
    }

    fun openFile(
        context: Context,
        uri: String,
        fileName: String = "",
        fallbackUri: String = uri,
    ): FileOpenResult {
        val candidates = listOf(uri.trim(), fallbackUri.trim())
            .filter { it.isNotEmpty() }
            .distinct()
        if (candidates.isEmpty()) {
            return FileOpenResult(success = false, message = "文件 URI 为空")
        }

        val authority = "${context.packageName}.fileprovider"
        var resolvedShareUri: Uri? = null
        var resolvedMime = "*/*"

        for (candidate in candidates) {
            if (candidate.startsWith("content://", ignoreCase = true)) {
                val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: continue
                resolvedShareUri = parsed
                resolvedMime = resolveMimeType(context = context, fileName = fileName, rawUri = candidate)
                break
            }

            if (candidate.startsWith("http://", ignoreCase = true) ||
                candidate.startsWith("https://", ignoreCase = true)
            ) {
                val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: continue
                resolvedShareUri = parsed
                resolvedMime = resolveMimeType(context = null, fileName = fileName, rawUri = candidate)
                break
            }

            val localFile = SessionAttachmentStorage.resolveLocalFileOrNull(candidate) ?: continue
            if (!localFile.exists() || !localFile.isFile) continue

            val effectiveFileName = SessionAttachmentStorage.sanitizeFileName(
                raw = fileName.trim().ifEmpty { localFile.name },
                fallback = localFile.name.ifBlank { "attachment" },
            )
            val fileToShare = prepareShareableFile(
                context = context,
                sourceFile = localFile,
                desiredFileName = effectiveFileName,
                authority = authority,
            )
            val providerUri = runCatching {
                FileProvider.getUriForFile(context, authority, fileToShare)
            }.getOrNull() ?: continue

            resolvedShareUri = providerUri
            resolvedMime = resolveMimeType(
                context = context,
                fileName = effectiveFileName,
                rawUri = candidate,
            )
            break
        }

        val shareUri = resolvedShareUri
            ?: return FileOpenResult(success = false, message = "找不到可打开的文件")

        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shareUri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(baseIntent)
            FileOpenResult(success = true)
        }.recoverCatching { firstError ->
            if (firstError is ActivityNotFoundException && resolvedMime != "*/*") {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(shareUri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(fallbackIntent, fileName.ifBlank { null }).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                FileOpenResult(success = true)
            } else {
                throw firstError
            }
        }.getOrElse { error ->
            val reason = when (error) {
                is ActivityNotFoundException -> "没有找到可打开此类型文件的应用"
                else -> error.message ?: error::class.simpleName.orEmpty()
            }
            FileOpenResult(success = false, message = reason)
        }
    }

    private fun prepareShareableFile(
        context: Context,
        sourceFile: File,
        desiredFileName: String,
        authority: String,
    ): File {
        val needsRenameCopy = sourceFile.name != desiredFileName
        if (!needsRenameCopy) {
            val directSupported = runCatching {
                FileProvider.getUriForFile(context, authority, sourceFile)
                true
            }.getOrDefault(false)
            if (directSupported) {
                return sourceFile
            }
        }

        val keyHash = Integer.toHexString(sourceFile.absolutePath.hashCode())
        val cacheFolder = File(File(context.cacheDir, OPEN_CACHE_DIR_NAME), keyHash)
        cacheFolder.mkdirs()
        val cachedCopy = File(cacheFolder, desiredFileName)
        val isUpToDate = cachedCopy.isFile &&
            cachedCopy.length() == sourceFile.length() &&
            cachedCopy.lastModified() >= sourceFile.lastModified()
        if (!isUpToDate) {
            Files.copy(
                sourceFile.toPath(),
                cachedCopy.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return cachedCopy
    }
}
