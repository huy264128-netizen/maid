package top.aaa000111.maid.agent

import top.aaa000111.maid.prompt.PromptResources
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aaa000111.maid.chat.SessionAttachmentStorage
import top.aaa000111.maid.chat.currentChatSession
import top.aaa000111.maid.proot.TermuxProotRuntime
import top.aaa000111.maid.provider.ModelRole
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64

/**
 * 视觉理解工具集：基于已配置的快速视觉模型（[ModelRole.FAST_VISION]）对图片（通过附件 `id` 或 URI）进行识别与问答。
 *
 * 当 `FAST_CHEAP` 与 `FAST_VISION` 指向同一个模型时，[ChatAgent] 会自动跳过此工具并直接向对话模型传入图片输入。
 */
class VisionToolSet(
    private val visionBindingProvider: (suspend () -> ChatModelBinding)? = null,
    private val imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
) : ToolSet {

    override val name: String = TOOLSET_NAME

    @Tool
    @LLMDescription(
        "Analyze an image using the configured fast vision model and answer a question or describe its contents. " +
            "Pass the image attachment ID (e.g. 'img_1') from the session attachment pool, or a content://, file://, http(s)://, data:image/... URI or local file path.",
    )
    suspend fun analyzeImage(
        @LLMDescription("Image attachment ID (e.g. 'img_1') or image URI / local file path to analyze.")
        imageUri: String,
        @LLMDescription("Specific question or instruction about what to inspect or extract from the image.")
        question: String = DEFAULT_VISION_QUESTION,
    ): String {
        val trimmedInput = imageUri.trim()
        if (trimmedInput.isEmpty()) {
            return "图片 ID 或 URI 不能为空。"
        }

        val activeSession = currentChatSession()
        // 首次访问附件时，若其 URI 不在内部目录，自动复制到内部目录 <filesDir>/<session_id>/<file_id> 并返回内部 URI
        val resolvedTargetUri = activeSession?.accessAttachmentUri(trimmedInput) ?: trimmedInput

        val attachment = runCatching {
            val resolver = imageAttachmentResolver ?: ::resolveImageAttachment
            resolver(resolvedTargetUri)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return "读取图片失败：${error.message ?: "未知错误"}"
        } ?: return "无法读取或解析图片 URI：$trimmedInput"

        return analyzeAttachment(attachment, question)
    }

    internal suspend fun analyzeImageBytes(bytes: ByteArray, mimeType: String, question: String): String =
        analyzeAttachment(buildBinaryImageAttachment(bytes, mimeType, fileName = null), question)

    private suspend fun analyzeAttachment(attachment: Attachment.Image, question: String): String {
        val binding = runCatching {
            visionBindingProvider?.invoke()
                ?: ChatAgent.resolveDefaultModelBinding(listOf(ModelRole.FAST_VISION))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return "快速视觉模型不可用：${error.message ?: "尚未配置快速视觉模型或对应 API Key"}"
        }

        val effectiveQuestion = question.trim().ifEmpty { DEFAULT_VISION_QUESTION }
        val visionPrompt = prompt(
            id = PROMPT_ID,
            params = LLMParams(temperature = DEFAULT_TEMPERATURE),
        ) {
            system(VISION_SYSTEM_PROMPT)
            user(
                content = effectiveQuestion,
                attachments = listOf(attachment),
            )
        }

        val responses = runCatching {
            binding.executor.execute(
                prompt = visionPrompt,
                model = binding.model,
                tools = emptyList(),
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return "调用快速视觉模型失败：${error.message ?: "未知错误"}"
        }

        val answer = responses
            .mapNotNull { it.content.trim().takeIf(String::isNotEmpty) }
            .joinToString("\n")
        return answer.ifEmpty { "快速视觉模型未返回有效描述。" }
    }

    companion object {
        const val TOOLSET_NAME = "vision_tool"
        const val PROMPT_ID = "vision_tool_prompt"
        const val DEFAULT_TEMPERATURE = 0.2
        const val MAX_DIRECT_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_RESIZED_IMAGE_DIMENSION = 1920
        val DEFAULT_VISION_QUESTION: String
            get() = PromptResources.read("vision/default_question.md")
        val VISION_SYSTEM_PROMPT: String
            get() = PromptResources.read("vision/system.md")

        val default: VisionToolSet = VisionToolSet()

        @Volatile
        private var appContext: Context? = null

        fun init(context: Context) {
            if (appContext != null) return
            synchronized(this) {
                if (appContext == null) {
                    appContext = context.applicationContext
                }
            }
        }

        internal fun resetForTest() {
            synchronized(this) {
                appContext = null
            }
        }

        /**
         * 将图片附件 ID、内部路径、PRoot 路径或标准 URI（`http(s)://`、`data:`、`content://`、`file://`、`internal://`、`proot://` 或本地路径）
         * 解析为 Koog [Attachment.Image]。
         * 若当前协程上下文存在 [top.aaa000111.maid.chat.ChatSession]，会自动通过 `accessAttachmentUri` 触发首次内部目录复制。
         */
        suspend fun resolveImageAttachment(rawIdOrUri: String): Attachment.Image? =
            resolveImageAttachment(
                rawIdOrUri = rawIdOrUri,
                prootDefaultBaseDirPosix = null,
                rootfsDirOverride = null,
            )

        /**
         * 支持自定义 PRoot 默认工作目录与 rootfs 路径的完整图片解析入口（供 `DirectImageReadToolSet`、`ProotLinuxAgent` 与单元测试使用）。
         */
        suspend fun resolveImageAttachment(
            rawIdOrUri: String,
            prootDefaultBaseDirPosix: String? = null,
            rootfsDirOverride: File? = null,
        ): Attachment.Image? {
            val trimmed = rawIdOrUri.trim()
            if (trimmed.isEmpty()) return null

            val session = currentChatSession()
            val matchedAttachment = session?.let { s ->
                s.findAttachment(trimmed)
                    ?: s.attachments.firstOrNull { att ->
                        att.isImage && att.normalizedFileName.equals(trimmed, ignoreCase = true)
                    }
                    ?: s.attachments.firstOrNull { att ->
                        att.normalizedFileName.equals(trimmed, ignoreCase = true)
                    }
            }
            val effectiveUri = if (session != null && matchedAttachment != null) {
                session.accessAttachmentUri(matchedAttachment.id)
            } else {
                session?.accessAttachmentUri(trimmed) ?: trimmed
            }

            if (effectiveUri.startsWith("http://", ignoreCase = true) ||
                effectiveUri.startsWith("https://", ignoreCase = true)
            ) {
                val pathOnly = effectiveUri.substringBefore('?').substringBefore('#')
                val fileName = pathOnly.substringAfterLast('/').takeIf { it.isNotBlank() }
                val mimeType = inferImageMimeType(path = pathOnly, bytes = null)
                return Attachment.Image(
                    content = AttachmentContent.URL(effectiveUri),
                    format = formatFromMimeType(mimeType),
                    mimeType = mimeType,
                    fileName = fileName,
                )
            }

            if (effectiveUri.startsWith("data:", ignoreCase = true)) {
                return parseDataUriImage(effectiveUri)
            }

            if (effectiveUri.startsWith("internal://", ignoreCase = true)) {
                return withContext(Dispatchers.IO) {
                    val relativeSpec = effectiveUri.substring("internal://".length).trimStart('/')
                    if (relativeSpec.isEmpty()) return@withContext null
                    val internalRoot = SessionAttachmentStorage.internalRootDirOrNull()
                        ?: appContext?.filesDir
                        ?: return@withContext null
                    val candidateFromRoot = resolveContainedFile(internalRoot, relativeSpec)
                    val candidateFromSession = session?.id?.let { sid ->
                        SessionAttachmentStorage.sessionDir(sid, rootDir = internalRoot)?.let { dir ->
                            resolveContainedFile(dir, relativeSpec)
                        }
                    }
                    val target = listOfNotNull(candidateFromRoot, candidateFromSession)
                        .firstOrNull { it.exists() && it.isFile && it.canRead() }
                        ?: return@withContext null
                    readLocalImageFile(target)
                }
            }

            if (effectiveUri.startsWith("proot://", ignoreCase = true)) {
                return withContext(Dispatchers.IO) {
                    val rootfs = resolveRootfsDir(rootfsDirOverride) ?: return@withContext null
                    val rawProotPath = effectiveUri.substring("proot://".length)
                    val (_, hostFile) = ProotLinuxFileIoToolSet.resolveProotPath(
                        prootPath = rawProotPath,
                        rootfsDir = rootfs,
                        defaultBaseDirPosix = prootDefaultBaseDirPosix
                            ?: ProotLinuxAgentLifecycleManager.DEFAULT_PROOT_HOME,
                    )
                    if (!hostFile.exists() || !hostFile.isFile || !hostFile.canRead()) {
                        return@withContext null
                    }
                    readLocalImageFile(hostFile)
                }
            }

            if (effectiveUri.startsWith("content://", ignoreCase = true)) {
                val context = appContext ?: return null
                return withContext(Dispatchers.IO) {
                    val uri = Uri.parse(effectiveUri)
                    val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes()
                    } ?: return@withContext null
                    if (rawBytes.isEmpty()) return@withContext null

                    val resolvedMime = context.contentResolver.getType(uri)
                        ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                    val fileName = queryDisplayName(context, uri)
                    buildBinaryImageAttachment(
                        rawBytes = rawBytes,
                        preferredMimeType = resolvedMime,
                        fileName = fileName,
                    )
                }
            }

            return withContext(Dispatchers.IO) {
                val directFile = if (effectiveUri.startsWith("file:", ignoreCase = true)) {
                    runCatching { File(URI(effectiveUri)) }.getOrElse {
                        File(effectiveUri.removePrefix("file://").removePrefix("file:"))
                    }
                } else {
                    File(effectiveUri)
                }
                if (directFile.exists() && directFile.isFile && directFile.canRead()) {
                    return@withContext readLocalImageFile(
                        file = directFile,
                        preferredFileName = matchedAttachment?.normalizedFileName?.takeIf { it.isNotBlank() },
                    )
                }

                // 尝试当前会话内部目录相对路径（<filesDir>/<session_id>/<relative>）或应用内部目录相对路径
                if (!effectiveUri.startsWith("file:", ignoreCase = true) && !effectiveUri.startsWith("/")) {
                    val internalRoot = SessionAttachmentStorage.internalRootDirOrNull() ?: appContext?.filesDir
                    if (internalRoot != null) {
                        val sessionRelative = session?.id?.let { sid ->
                            SessionAttachmentStorage.sessionDir(sid, rootDir = internalRoot)?.let { dir ->
                                resolveContainedFile(dir, effectiveUri)
                            }
                        }
                        if (sessionRelative != null && sessionRelative.exists() && sessionRelative.isFile && sessionRelative.canRead()) {
                            return@withContext readLocalImageFile(sessionRelative)
                        }
                        val rootRelative = resolveContainedFile(internalRoot, effectiveUri)
                        if (rootRelative != null && rootRelative.exists() && rootRelative.isFile && rootRelative.canRead()) {
                            return@withContext readLocalImageFile(rootRelative)
                        }
                    }
                }

                // 尝试 PRoot 容器内路径回退（支持 ~/...、/... 以及 prootDefaultBaseDirPosix 下的相对路径）
                val looksLikeProotPath = !effectiveUri.startsWith("file:", ignoreCase = true) &&
                    (effectiveUri.startsWith("~") || effectiveUri.startsWith("/") || prootDefaultBaseDirPosix != null)
                if (looksLikeProotPath) {
                    val rootfs = resolveRootfsDir(rootfsDirOverride)
                    if (rootfs != null) {
                        val (_, prootHostFile) = ProotLinuxFileIoToolSet.resolveProotPath(
                            prootPath = effectiveUri,
                            rootfsDir = rootfs,
                            defaultBaseDirPosix = prootDefaultBaseDirPosix
                                ?: ProotLinuxAgentLifecycleManager.DEFAULT_PROOT_HOME,
                        )
                        if (prootHostFile.exists() && prootHostFile.isFile && prootHostFile.canRead()) {
                            return@withContext readLocalImageFile(prootHostFile)
                        }
                    }
                }

                null
            }
        }

        private fun resolveRootfsDir(override: File?): File? {
            if (override != null) return override
            val ctx = appContext ?: return null
            return runCatching { TermuxProotRuntime.getRootfsDir(ctx) }.getOrNull()
        }

        private fun resolveContainedFile(baseDir: File, relativePath: String): File? {
            val cleaned = relativePath.trim().trimStart('/')
            if (cleaned.isEmpty()) return null
            val candidate = File(baseDir, cleaned)
            val baseCanonical = runCatching { baseDir.canonicalPath }.getOrNull()?.trimEnd(File.separatorChar)
                ?: return null
            val candidateCanonical = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
            return if (candidateCanonical == baseCanonical ||
                candidateCanonical.startsWith("$baseCanonical${File.separator}")
            ) {
                candidate
            } else {
                null
            }
        }

        private fun readLocalImageFile(
            file: File,
            preferredFileName: String? = null,
        ): Attachment.Image? {
            val rawBytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            if (rawBytes.isEmpty()) return null
            return buildBinaryImageAttachment(
                rawBytes = rawBytes,
                preferredMimeType = null,
                fileName = preferredFileName ?: file.name,
            )
        }

        private fun parseDataUriImage(dataUri: String): Attachment.Image? {
            val commaIndex = dataUri.indexOf(',')
            if (commaIndex <= 5) return null
            val header = dataUri.substring(5, commaIndex)
            val payload = dataUri.substring(commaIndex + 1).trim()
            if (payload.isEmpty() || !header.contains(";base64", ignoreCase = true)) {
                return null
            }
            val mimeType = header.substringBefore(';').trim()
                .takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: "image/jpeg"
            val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return buildBinaryImageAttachment(
                rawBytes = bytes,
                preferredMimeType = mimeType,
                fileName = null,
            )
        }

        private fun buildBinaryImageAttachment(
            rawBytes: ByteArray,
            preferredMimeType: String?,
            fileName: String?,
        ): Attachment.Image {
            val detectedMime = preferredMimeType
                ?: inferImageMimeType(path = fileName, bytes = rawBytes)
            val compressed = maybeDownscaleLargeImage(rawBytes, detectedMime)
            val finalBytes = compressed?.first ?: rawBytes
            val finalMime = compressed?.second ?: detectedMime
            return Attachment.Image(
                content = AttachmentContent.Binary.Bytes(finalBytes),
                format = formatFromMimeType(finalMime),
                mimeType = finalMime,
                fileName = fileName,
            )
        }

        private fun maybeDownscaleLargeImage(
            rawBytes: ByteArray,
            mimeType: String,
        ): Pair<ByteArray, String>? {
            if (rawBytes.size <= MAX_DIRECT_IMAGE_BYTES) return null
            return runCatching {
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)
                val origWidth = boundsOptions.outWidth
                val origHeight = boundsOptions.outHeight
                if (origWidth <= 0 || origHeight <= 0) return@runCatching null

                var sampleSize = 1
                while ((origWidth / sampleSize) > MAX_RESIZED_IMAGE_DIMENSION ||
                    (origHeight / sampleSize) > MAX_RESIZED_IMAGE_DIMENSION
                ) {
                    sampleSize *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                    ?: return@runCatching null
                try {
                    val out = ByteArrayOutputStream()
                    val format = if (mimeType.equals("image/png", ignoreCase = true)) {
                        Bitmap.CompressFormat.PNG
                    } else {
                        Bitmap.CompressFormat.JPEG
                    }
                    val targetMime = if (format == Bitmap.CompressFormat.PNG) {
                        "image/png"
                    } else {
                        "image/jpeg"
                    }
                    bitmap.compress(format, 88, out)
                    out.toByteArray() to targetMime
                } finally {
                    bitmap.recycle()
                }
            }.getOrNull()
        }

        private fun queryDisplayName(context: Context, uri: Uri): String? {
            return runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }

        internal fun inferImageMimeType(path: String?, bytes: ByteArray?): String {
            if (bytes != null && bytes.size >= 4) {
                if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
                ) {
                    return "image/png"
                }
                if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                    return "image/jpeg"
                }
                if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) {
                    return "image/gif"
                }
                if (bytes.size >= 12 &&
                    bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                    bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                    bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
                ) {
                    return "image/webp"
                }
            }
            val ext = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "image/jpeg"
            }
        }

        internal fun formatFromMimeType(mimeType: String): String {
            val subtype = mimeType.substringAfter('/', "jpeg").substringBefore(';').lowercase()
            return when (subtype) {
                "jpg" -> "jpeg"
                "svg+xml" -> "svg"
                else -> subtype.ifBlank { "jpeg" }
            }
        }
    }
}
