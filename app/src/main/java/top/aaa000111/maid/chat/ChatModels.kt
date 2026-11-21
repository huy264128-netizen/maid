package top.aaa000111.maid.chat

import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.settings.ModelOutputDisplayMode
import java.util.UUID

/**
 * 单个会话内的文件或图片附件元信息：
 * - [id]：模型内部通信与会话索引使用的唯一 ID；
 * - [fileName]：文件或图片的展示/原始文件名（含扩展名，供界面卡片展示、打开文件识别类型与导出默认文件名）；
 * - [description]：文件或图片的描述（如来源、类型、文件名与用途说明）；
 * - [uri]：访问该文件的 URI（首次访问或移入会话时若不在应用内部目录，会自动写入内部目录 `<filesDir>/<session_id>/<file_id>` 并更新为内部 URI）；
 * - [originalUri]：用户或工具最初传入的原始 URI；
 * - [isImage]：是否为图片附件。
 */
data class SessionAttachment(
    val id: String,
    val description: String = "",
    val uri: String,
    val originalUri: String = uri,
    val isImage: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val fileName: String = defaultFileNameFor(uri = originalUri, fallbackId = id, isImage = isImage),
) {
    val normalizedId: String
        get() = id.trim()

    val normalizedUri: String
        get() = uri.trim()

    val normalizedOriginalUri: String
        get() = originalUri.trim().ifEmpty { normalizedUri }

    val normalizedFileName: String
        get() = fileName.trim().ifEmpty {
            defaultFileNameFor(
                uri = normalizedOriginalUri,
                fallbackId = normalizedId,
                isImage = isImage,
            )
        }

    val normalizedDescription: String
        get() = description.trim().ifEmpty {
            defaultDescriptionFor(
                uri = normalizedOriginalUri,
                isImage = isImage,
                fileName = normalizedFileName,
            )
        }

    fun normalized(): SessionAttachment = copy(
        id = normalizedId,
        description = normalizedDescription,
        uri = normalizedUri,
        originalUri = normalizedOriginalUri,
        fileName = normalizedFileName,
    )

    companion object {
        fun extractFileNameFromUri(uri: String): String {
            val trimmed = uri.trim()
            if (trimmed.isEmpty()) return ""
            val rawSegment = trimmed
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('/')
                .substringAfterLast('/')
                .substringAfterLast(':')
                .trim()
            if (rawSegment.isEmpty()) return ""
            return if ('%' in rawSegment) {
                runCatching {
                    java.net.URLDecoder.decode(rawSegment, "UTF-8").trim()
                }.getOrDefault(rawSegment)
            } else {
                rawSegment
            }
        }

        fun defaultFileNameFor(
            uri: String,
            fallbackId: String = "",
            isImage: Boolean = false,
        ): String {
            val extracted = extractFileNameFromUri(uri)
            if (extracted.isNotEmpty()) return extracted
            val cleanId = fallbackId.trim()
            if (cleanId.isNotEmpty()) return cleanId
            return if (isImage) "image" else "file"
        }

        fun defaultDescriptionFor(
            uri: String,
            isImage: Boolean,
            fileName: String = "",
        ): String {
            val label = fileName.trim().ifEmpty { extractFileNameFromUri(uri) }
            val prefix = if (isImage) "用户发送的图片" else "用户发送的文件"
            return if (label.isNotEmpty()) "$prefix ($label)" else prefix
        }
    }
}

/**
 * 统一的用户输入载荷，支持文本、文件 URI 列表与图片 URI 列表。
 */
data class UserInput(
    val text: String = "",
    val fileUris: List<String> = emptyList(),
    val imageUris: List<String> = emptyList(),
    val fileDescriptions: Map<String, String> = emptyMap(),
    val imageDescriptions: Map<String, String> = emptyMap(),
    val fileNames: Map<String, String> = emptyMap(),
    val imageNames: Map<String, String> = emptyMap(),
) {
    val normalizedText: String
        get() = text.trim()

    val normalizedFileUris: List<String>
        get() = fileUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val normalizedImageUris: List<String>
        get() = imageUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val hasAttachments: Boolean
        get() = normalizedFileUris.isNotEmpty() || normalizedImageUris.isNotEmpty()

    val isBlank: Boolean
        get() = normalizedText.isEmpty() && !hasAttachments

    fun normalized(): UserInput = UserInput(
        text = normalizedText,
        fileUris = normalizedFileUris,
        imageUris = normalizedImageUris,
        fileDescriptions = fileDescriptions,
        imageDescriptions = imageDescriptions,
        fileNames = fileNames,
        imageNames = imageNames,
    )
}

sealed interface ChatItem {
    val id: String
    val createdAtMillis: Long

    data class UserMessage(
        override val id: String = UUID.randomUUID().toString(),
        val content: String,
        val fileUris: List<String> = emptyList(),
        val imageUris: List<String> = emptyList(),
        val attachmentIds: List<String> = emptyList(),
        override val createdAtMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    data class AssistantMessage(
        override val id: String = UUID.randomUUID().toString(),
        val markdown: String,
        val provider: LlmProvider? = null,
        val modelId: String? = null,
        val senderLabel: String? = null,
        val fileUris: List<String> = emptyList(),
        val imageUris: List<String> = emptyList(),
        val attachmentIds: List<String> = emptyList(),
        val isStreaming: Boolean = false,
        override val createdAtMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    data class ToolStatusItem(
        override val id: String = UUID.randomUUID().toString(),
        val title: String,
        val detail: String,
        val isCompleted: Boolean = true,
        override val createdAtMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    /**
     * 工具集权限请求卡片：
     * 告知用户当前操作需要某个 `toolset`（[toolsetId] / [toolsetTitle]），
     * 因此需要开启 [requiredPrivileges] 列表中的任意一项权限；
     * 用户点击“确定”后重新检查权限并将结果更新至 [isCompleted]、[isGranted]、[matchedPrivileges] 与 [resultSummary]。
     */
    data class PermissionRequestItem(
        override val id: String = UUID.randomUUID().toString(),
        val toolsetId: String,
        val toolsetTitle: String,
        val toolsetDescription: String = "",
        val requiredPrivileges: List<AndroidPrivilege>,
        val isCompleted: Boolean = false,
        val isGranted: Boolean = false,
        val matchedPrivileges: List<AndroidPrivilege> = emptyList(),
        val availablePrivileges: List<AndroidPrivilege> = emptyList(),
        val resultSummary: String = "",
        override val createdAtMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    /**
     * 文件消息卡片：用于在聊天消息流中展示可点击打开的文件/图片卡片，
     * 包含文件名（[fileName]）、描述（[description]）、会话附件 ID（[attachmentId]）与目标 [uri]。
     */
    data class FileCardItem(
        override val id: String = UUID.randomUUID().toString(),
        val attachmentId: String = "",
        val fileName: String,
        val description: String = "",
        val uri: String,
        val isImage: Boolean = false,
        override val createdAtMillis: Long = System.currentTimeMillis(),
    ) : ChatItem

    data class SystemNoticeItem(
        override val id: String = UUID.randomUUID().toString(),
        val message: String,
        val isError: Boolean = false,
        override val createdAtMillis: Long = System.currentTimeMillis(),
    ) : ChatItem
}

/**
 * 单个 [ChatSession] 的显式状态机状态：
 * - [Idle]：空闲等待用户输入；
 * - [Chatting]：对话模型（`ChatAgent`）正在处理当前轮次；
 * - [Planning]：规划智能体（`PlannerAgent`）正在多检查点规划思考中，期间外部用户输入归属规划器并在下个检查点接收；
 * - [WaitingAnswer]：存在待用户明确回答的 [Question]，优先路由用户输入给提问回调。
 */
sealed interface ChatSessionState {
    data object Idle : ChatSessionState

    data object Chatting : ChatSessionState

    data class Planning(
        val checkpointIndex: Int = 1,
        val totalCheckpoints: Int = 0,
        val currentCheckpointTitle: String = "",
        val queuedExternalInputCount: Int = 0,
    ) : ChatSessionState

    data class WaitingAnswer(
        val questionPreview: String = "",
        val pendingCount: Int = 1,
        val resumeState: ChatSessionState = Idle,
    ) : ChatSessionState

    fun isPlanningActive(): Boolean = when (this) {
        is Planning -> true
        is WaitingAnswer -> resumeState.isPlanningActive()
        Idle, Chatting -> false
    }
}

data class ChatUiState(
    val sessions: List<ChatSession.Snapshot> = emptyList(),
    val currentSessionId: String = "",
    val inputText: String = "",
    val pendingFileUris: List<String> = emptyList(),
    val pendingImageUris: List<String> = emptyList(),
    val isModelConfigured: Boolean = false,
    val isGenerating: Boolean = false,
    val modelOutputDisplayMode: ModelOutputDisplayMode = ModelOutputDisplayMode.SHOW_ALL,
) {
    val currentSession: ChatSession.Snapshot?
        get() = sessions.firstOrNull { it.id == currentSessionId }

    val currentMessages: List<ChatItem>
        get() = currentSession?.items.orEmpty()

    val visibleMessages: List<ChatItem>
        get() = filterChatItemsForDisplay(
            items = currentMessages,
            mode = modelOutputDisplayMode,
        )

    val pendingQuestion: Question?
        get() = currentSession?.pendingQuestion

    val currentSessionState: ChatSessionState
        get() = currentSession?.sessionState ?: ChatSessionState.Idle

    val isPlanning: Boolean
        get() = currentSession?.isPlanning == true

    val activeToolName: String?
        get() = currentSession?.activeToolName
}

/**
 * 判断某条聊天消息项是否属于受「模型输出显示」设置管制的单轮工具调用过程输出（[ChatItem.ToolStatusItem]）。
 * 模型通过 `output` 工具发出的分段 Markdown、最终正式回复、向用户提问、权限请求卡片与系统提示均始终保留展示。
 */
fun ChatItem.isIntermediateProcessOutput(): Boolean = when (this) {
    is ChatItem.ToolStatusItem -> true
    is ChatItem.AssistantMessage,
    is ChatItem.UserMessage,
    is ChatItem.PermissionRequestItem,
    is ChatItem.FileCardItem,
    is ChatItem.SystemNoticeItem,
    -> false
}

/**
 * 按 [ModelOutputDisplayMode] 对聊天消息列表进行展示过滤（不修改底层持久化记录）：
 * - [ModelOutputDisplayMode.SHOW_ALL]：完整展示所有消息项；
 * - [ModelOutputDisplayMode.LAST_ONLY]：以每条 [ChatItem.UserMessage] 为单轮起点分段，每轮仅保留最后一条工具调用过程输出及所有非工具项；
 * - [ModelOutputDisplayMode.HIDE]：隐藏全部工具调用过程输出，保留用户消息、分段 `output` 消息、最终回复、提问、权限请求与系统提示。
 */
fun filterChatItemsForDisplay(
    items: List<ChatItem>,
    mode: ModelOutputDisplayMode,
): List<ChatItem> {
    if (mode == ModelOutputDisplayMode.SHOW_ALL || items.isEmpty()) {
        return items
    }
    if (mode == ModelOutputDisplayMode.HIDE) {
        return items.filterNot { it.isIntermediateProcessOutput() }
    }
    val result = ArrayList<ChatItem>(items.size)
    var turnStart = 0
    while (turnStart < items.size) {
        var turnEnd = turnStart + 1
        while (turnEnd < items.size && items[turnEnd] !is ChatItem.UserMessage) {
            turnEnd++
        }
        var lastIntermediateIndex = -1
        for (i in turnStart until turnEnd) {
            if (items[i].isIntermediateProcessOutput()) {
                lastIntermediateIndex = i
            }
        }
        for (i in turnStart until turnEnd) {
            val item = items[i]
            if (!item.isIntermediateProcessOutput() || i == lastIntermediateIndex) {
                result.add(item)
            }
        }
        turnStart = turnEnd
    }
    return result
}
