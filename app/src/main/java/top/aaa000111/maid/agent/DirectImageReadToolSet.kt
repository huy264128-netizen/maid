package top.aaa000111.maid.agent

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.Message
import kotlinx.coroutines.CancellationException

/**
 * 一次 [DirectImageReadToolSet.readImage] 调用成功解析后、等待注入当前多模态模型上下文的图片载荷。
 */
data class PendingDirectImageInjection(
    val sourceRef: String,
    val focus: String = "",
    val attachment: Attachment.Image,
)

/**
 * 面向**原生多模态模型**的直接读图工具集（`readImage`）：
 * - 不额外调用二级视觉模型，而是将目标图片解析为 Koog [Attachment.Image] 并暂存到当前运行队列；
 * - 随后由策略节点 [nodeLLMSendToolResultWithDirectImages] 在写入 `Message.Tool.Result` 后，
 *   紧接着追加一条携带该 `Attachment.Image` 的多模态 `Message.User`，让主模型用自身原生视觉能力直接看图；
 * - 同时支持内部来源与标准 URI：
 *   - 会话内部附件 ID（如 `img_1`）或内部文件名 / 会话内部目录路径；
 *   - 应用内部路径（如 `captureScreenshot` 返回的 `filePath`、`internal://...`）；
 *   - PRoot Linux 容器内路径（如 `~/session_id/chart.png`、`/root/...`、`proot:///...`）；
 *   - 标准 URI（`content://`、`file://`、`http(s)://`、`data:image/...;base64,...`）。
 */
@LLMDescription("面向支持原生多模态视觉模型的直接读图工具集：将内部图片文件、PRoot 容器图片或外部 URI 直接载入当前模型视觉上下文")
class DirectImageReadToolSet(
    private val imageAttachmentResolver: suspend (String) -> Attachment.Image? = { ref ->
        VisionToolSet.resolveImageAttachment(ref)
    },
    private val maxPendingImagesPerStep: Int = DEFAULT_MAX_PENDING_IMAGES,
) : ToolSet {

    override val name: String = TOOLSET_NAME

    private val pendingLock = Any()
    private val pendingImages = ArrayDeque<PendingDirectImageInjection>()

    @Tool
    @LLMDescription(
        "Load an image directly into your own native multimodal visual context (no secondary vision model call). " +
            "Supports both internal sources and URIs: session attachment IDs (e.g. 'img_1'), internal app/session paths " +
            "(e.g. captureScreenshot filePath, 'internal://...'), PRoot container paths (e.g. '~/session_id/out.png', '/root/...', 'proot:///...'), " +
            "and standard URIs ('content://...', 'file://...', 'http(s)://...', 'data:image/...').",
    )
    suspend fun readImage(
        @LLMDescription(
            "Image reference to load: session attachment ID (e.g. 'img_1'), internal/local/PRoot file path, " +
                "or URI (content://, file://, http(s)://, data:image/..., internal://, proot://).",
        )
        imageUri: String,
        @LLMDescription("Optional focus note indicating what you want to inspect in this image.")
        focus: String = "",
    ): String {
        val trimmedRef = imageUri.trim()
        if (trimmedRef.isEmpty()) {
            return "读取图片失败：imageUri 不能为空。"
        }

        val attachment = runCatching {
            imageAttachmentResolver(trimmedRef)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return "读取图片失败（$trimmedRef）：${error.message ?: error::class.simpleName}"
        } ?: return "无法找到或解析图片：$trimmedRef（请检查会话附件 ID、应用内部路径、PRoot 路径或 URI 是否有效且可读）"

        synchronized(pendingLock) {
            if (pendingImages.size >= maxPendingImagesPerStep) {
                return "本步已达到单次直接读图上限（$maxPendingImagesPerStep 张），请先基于已载入的图片继续处理。"
            }
            pendingImages.addLast(
                PendingDirectImageInjection(
                    sourceRef = trimmedRef,
                    focus = focus.trim(),
                    attachment = attachment,
                ),
            )
        }

        val contentSummary = when (val content = attachment.content) {
            is AttachmentContent.Binary.Bytes -> "${content.data.size} bytes"
            is AttachmentContent.Binary.Base64 -> "base64"
            is AttachmentContent.URL -> "url"
            is AttachmentContent.PlainText -> "${content.text.length} chars"
        }
        val filePart = attachment.fileName?.takeIf { it.isNotBlank() }?.let { ", fileName=$it" }.orEmpty()
        return "已加载图片到原生多模态上下文（source=$trimmedRef, mimeType=${attachment.mimeType}, format=${attachment.format}, content=$contentSummary$filePart）。图片将在紧随本工具结果的多模态消息中直接呈现，请直接观察画面并继续完成任务。"
    }

    /** 取出并清空当前等待注入的图片列表。 */
    fun drainPendingImages(): List<PendingDirectImageInjection> = synchronized(pendingLock) {
        if (pendingImages.isEmpty()) return emptyList()
        val snapshot = pendingImages.toList()
        pendingImages.clear()
        snapshot
    }

    /** 当前待注入图片数量（主要用于测试验证）。 */
    internal fun pendingCount(): Int = synchronized(pendingLock) { pendingImages.size }

    companion object {
        const val TOOLSET_NAME = "direct_image_read"
        const val TOOL_NAME = "readImage"
        const val DEFAULT_MAX_PENDING_IMAGES = 6
    }
}

/**
 * 替代默认 `nodeLLMSendToolResult` 的多模态增强节点：
 * - 不增加额外的策略图节点步数（与 `nodeLLMSendToolResult` 完全等价的 1 步开销）；
 * - 严格先写入匹配 `tool_call_id` 的 `Message.Tool.Result`，随后若 [directImageToolSet] 有待注入图片，
 *   立即追加一条附带 [Attachment.Image] 的 `Message.User`，兼容 OpenAI / Anthropic / Gemini 原生多模态协议；
 * - 若本次调用的不是 `readImage`（或 [directImageToolSet] 为空），行为与 `nodeLLMSendToolResult` 完全一致。
 */
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeLLMSendToolResultWithDirectImages(
    name: String,
    directImageToolSet: DirectImageReadToolSet?,
    allowToolCalls: Boolean = true,
): AIAgentNodeDelegate<ReceivedToolResult, Message.Response> =
    if (directImageToolSet == null) {
        nodeLLMSendToolResult(name = name)
    } else {
        node(name = name) { toolResult ->
            val pending = directImageToolSet.drainPendingImages()
            llm.writeSession {
                updatePrompt {
                    tool {
                        result(
                            id = toolResult.id,
                            tool = toolResult.tool,
                            content = toolResult.content,
                        )
                    }
                    if (pending.isNotEmpty()) {
                        val caption = buildString {
                            append("[readImage 已将以下图片直接载入你的原生多模态视觉上下文，请直接观察图片内容并继续任务]")
                            pending.forEachIndexed { index, item ->
                                appendLine()
                                append("- 图片 ${index + 1}：${item.sourceRef}")
                                if (item.focus.isNotBlank()) {
                                    append("（关注点：${item.focus}）")
                                }
                            }
                        }
                        user(
                            content = caption,
                            attachments = pending.map { it.attachment },
                        )
                    }
                }
                if (allowToolCalls) {
                    requestLLM()
                } else {
                    requestLLMWithoutTools()
                }
            }
        }
    }
