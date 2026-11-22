package top.aaa000111.maid.chat

import kotlinx.coroutines.currentCoroutineContext
import top.aaa000111.maid.learning.TaskExecutionContext

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.agent.ChatAgentManager
import top.aaa000111.maid.agent.ChatAgentReply
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog
import top.aaa000111.maid.androidtool.ToolsetPermissionCheckResult
import top.aaa000111.maid.androidtool.ToolsetPermissionSpec
import top.aaa000111.maid.provider.LlmProvider
import java.util.UUID

/**
 * 保存其他模型或后台任务向用户发起的提问。
 *
 * @property content 向用户展示的提问文本（支持 Markdown）。
 * @property fileUris 提问附带的文件 URI 列表。
 * @property imageUris 提问附带的图片 URI 列表。
 * @property onInputAnswer 接收完整用户输入（文本、文件 URI、图片 URI）的可选回调函数。
 * @property onAnswer 接收用户回答文本的回调函数。
 */
data class Question(
    val content: String,
    val fileUris: List<String> = emptyList(),
    val imageUris: List<String> = emptyList(),
    val onInputAnswer: ((UserInput) -> Unit)? = null,
    val onAnswer: (String) -> Unit = {},
) {
    val normalizedFileUris: List<String>
        get() = fileUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val normalizedImageUris: List<String>
        get() = imageUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val hasAttachments: Boolean
        get() = normalizedFileUris.isNotEmpty() || normalizedImageUris.isNotEmpty()

    internal fun dispatchAnswer(userInput: UserInput) {
        val normalized = userInput.normalized()
        onInputAnswer?.invoke(normalized)
        onAnswer(normalized.text)
    }
}

/**
 * [ChatSession.input] 的处理结果。
 */
sealed interface ChatInputResult {
    data class QuestionAnswered(
        val question: Question,
        val answer: String,
        val input: UserInput = UserInput(text = answer),
    ) : ChatInputResult

    data class QueuedForPlanner(
        val input: UserInput,
        val checkpointIndex: Int,
        val queuedCount: Int,
    ) : ChatInputResult

    data class ModelReplied(
        val reply: ChatAgentReply,
    ) : ChatInputResult
}

/**
 * 将外部文件移入 [ChatSession] 内部目录后的返回结果，同时包含已注册的 [SessionAttachment] 与底层文件转移详情。
 */
data class SessionFileMoveResult(
    val attachment: SessionAttachment,
    val transfer: SessionFileTransferResult,
)

/**
 * 单个对话会话的统一管理类，并作为 Koog [ToolSet] 将 [output] 暴露为工具：
 * 1. 维护会话元信息（[id]、[title]、[createdAtMillis]、[updatedAtMillis]）；
 * 2. 维护用于界面展示的 [ChatItem] 消息列表并暴露响应式 [state] 快照流；
 * 3. 维护其他模型向用户发起的待处理 [Question] 队列；
 * 4. 实现统一 [input]/[output]：有待处理 [Question] 时优先响应提问，否则交给对话模型，
 *    同时允许模型在执行中途调用 [output] 工具将 Markdown 内容直接推送到 GUI；
 * 5. 维护供 Koog [ai.koog.agents.core.agent.AIAgent] 使用的短期对话历史（[Message]）与串行锁，
 *    支持多会话并发隔离与 Prompt 构建；
 * 6. 维护当前会话的文件与图片附件表（[SessionAttachment]，分为 `id`、`description`、`uri`），
 *    模型内部通信统一使用 `id`，且在首次访问附件时若其 `uri` 不在内部目录则自动复制到 `<filesDir>/<session_id>/<file_id>`。
 */
class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    title: String = DEFAULT_TITLE,
    val createdAtMillis: Long = System.currentTimeMillis(),
    updatedAtMillis: Long = createdAtMillis,
    items: List<ChatItem> = emptyList(),
    history: List<Message> = emptyList(),
    fileUriPool: List<String> = emptyList(),
    imageUriPool: List<String> = emptyList(),
    attachments: List<SessionAttachment> = emptyList(),
    chatAgent: ChatAgent? = null,
    chatManager: ChatAgentManager? = null,
    internal val defaultTitlePrefix: String = DEFAULT_TITLE,
    private val privilegeChecker: (suspend (Set<AndroidPrivilege>) -> Set<AndroidPrivilege>)? = null,
    private val onPersist: ((PersistedChatSession) -> Unit)? = null,
) : ToolSet {

    override val name: String = TOOLSET_NAME

    /**
     * 供 UI 状态与外部观察使用的不可变会话快照。
     */
    data class Snapshot(
        val id: String,
        val title: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long,
        val items: List<ChatItem>,
        val history: List<Message>,
        val fileUriPool: List<String> = emptyList(),
        val imageUriPool: List<String> = emptyList(),
        val attachments: List<SessionAttachment> = emptyList(),
        val pendingQuestions: List<Question> = emptyList(),
        val sessionState: ChatSessionState = ChatSessionState.Idle,
        val pendingPlannerInputs: List<UserInput> = emptyList(),
        val activeToolCalls: List<String> = emptyList(),
    ) {
        val itemCount: Int
            get() = items.size

        val historySize: Int
            get() = history.size

        /**
         * 当前会话的公用 URI 池（按暂存顺序汇总所有输入文件 URI 与图片 URI 并去重）。
         */
        val uriPool: List<String>
            get() = (fileUriPool + imageUriPool).distinct()

        /**
         * 当前会话中所有非图片文件附件列表。
         */
        val fileAttachments: List<SessionAttachment>
            get() = attachments.filterNot { it.isImage }

        /**
         * 当前会话中所有图片附件列表。
         */
        val imageAttachments: List<SessionAttachment>
            get() = attachments.filter { it.isImage }

        val pendingQuestion: Question?
            get() = pendingQuestions.firstOrNull()

        val hasPendingQuestion: Boolean
            get() = pendingQuestions.isNotEmpty()

        val isPlanning: Boolean
            get() = sessionState.isPlanningActive()

        val queuedPlannerInputCount: Int
            get() = pendingPlannerInputs.size

        /**
         * 当前正在调用的最内层工具名称（包含工具名与说明），无工具执行时为 `null`。
         */
        val activeToolName: String?
            get() = activeToolCalls.lastOrNull()
    }

    private data class QueuedQuestionEntry(
        val question: Question,
        val displayInGui: Boolean,
    )

    private data class PendingPermissionRequestEntry(
        val itemId: String,
        val spec: ToolsetPermissionSpec,
        val privilegeChecker: (suspend (Set<AndroidPrivilege>) -> Set<AndroidPrivilege>)?,
        val deferred: CompletableDeferred<ToolsetPermissionCheckResult>,
    )

    private sealed interface InputDispatchDecision {
        data class AnswerQuestion(
            val question: Question,
            val nextQuestionToDisplay: QueuedQuestionEntry?,
        ) : InputDispatchDecision

        data class QueueForPlanner(
            val checkpointIndex: Int,
            val queuedCount: Int,
        ) : InputDispatchDecision

        data object ForwardToChatAgent : InputDispatchDecision
    }

    private val stateLock = Any()
    private var explicitlyNamed = false
    private val turnMutex = Mutex()
    private val pendingQuestionDisplayFlags = ArrayDeque<Boolean>()
    private val pendingPermissionRequests = LinkedHashMap<String, PendingPermissionRequestEntry>()
    private var activeChatTurnDepth: Int = 0
    private var activePlanningState: ChatSessionState.Planning? = null

    @Volatile
    private var activeProvider: LlmProvider? = null

    @Volatile
    private var activeModelId: String? = null

    private val defaultChatManager: ChatAgentManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        chatManager ?: chatAgent?.manager ?: ChatAgent(defaultSession = this).manager
    }

    private val _state: MutableStateFlow<Snapshot>

    init {
        val initialFilePool = mergeNormalizedUris(
            fileUriPool,
            items.flatMap { extractFileUrisFromItem(it) },
        )
        val initialImagePool = mergeNormalizedUris(
            imageUriPool,
            items.flatMap { extractImageUrisFromItem(it) },
        )
        val initialAttachments = mergeAttachmentsWithUris(
            existing = attachments,
            fileUris = initialFilePool,
            imageUris = initialImagePool,
        )
        _state = MutableStateFlow(
            Snapshot(
                id = id,
                title = title,
                createdAtMillis = createdAtMillis,
                updatedAtMillis = updatedAtMillis,
                items = items.toList(),
                history = history.toList(),
                fileUriPool = mergeNormalizedUris(
                    initialFilePool,
                    initialAttachments.filterNot { it.isImage }.map { it.uri },
                ),
                imageUriPool = mergeNormalizedUris(
                    initialImagePool,
                    initialAttachments.filter { it.isImage }.map { it.uri },
                ),
                attachments = initialAttachments,
                sessionState = ChatSessionState.Idle,
                pendingPlannerInputs = emptyList(),
            ),
        )
    }

    val state: StateFlow<Snapshot> = _state.asStateFlow()

    val title: String
        get() = _state.value.title

    val updatedAtMillis: Long
        get() = _state.value.updatedAtMillis

    val items: List<ChatItem>
        get() = _state.value.items

    val itemCount: Int
        get() = _state.value.items.size

    val historySize: Int
        get() = _state.value.history.size

    /**
     * 当前会话的公用 URI 池（汇总本会话内所有已暂存的文件 URI 与图片 URI）。
     */
    val uriPool: List<String>
        get() = _state.value.uriPool

    /**
     * 当前会话公用 URI 池中已暂存的文件 URI 列表。
     */
    val fileUriPool: List<String>
        get() = _state.value.fileUriPool

    /**
     * 当前会话公用 URI 池中已暂存的图片 URI 列表。
     */
    val imageUriPool: List<String>
        get() = _state.value.imageUriPool

    /**
     * 当前会话已注册的全部附件（包含 `id`、`description` 与 `uri`）。
     */
    val attachments: List<SessionAttachment>
        get() = _state.value.attachments

    /**
     * 当前会话中所有非图片文件附件列表。
     */
    val fileAttachments: List<SessionAttachment>
        get() = _state.value.fileAttachments

    /**
     * 当前会话中所有图片附件列表。
     */
    val imageAttachments: List<SessionAttachment>
        get() = _state.value.imageAttachments

    val pendingQuestion: Question?
        get() = _state.value.pendingQuestion

    val pendingQuestions: List<Question>
        get() = _state.value.pendingQuestions

    val sessionState: ChatSessionState
        get() = _state.value.sessionState

    val pendingPlannerInputs: List<UserInput>
        get() = _state.value.pendingPlannerInputs

    val activeToolCalls: List<String>
        get() = _state.value.activeToolCalls

    val activeToolName: String?
        get() = _state.value.activeToolName

    fun hasPendingQuestion(): Boolean = _state.value.hasPendingQuestion

    fun isPlanning(): Boolean = _state.value.isPlanning

    fun snapshot(): Snapshot = _state.value

    fun historySnapshot(): List<Message> = _state.value.history

    /**
     * 按附件 `id` 或 `uri` / `originalUri` 查找附件元信息（不触发内部复制）。
     */
    fun findAttachment(idOrUri: String): SessionAttachment? {
        val needle = idOrUri.trim()
        if (needle.isEmpty()) return null
        val list = _state.value.attachments
        return list.firstOrNull { it.id == needle }
            ?: list.firstOrNull { it.uri == needle || it.originalUri == needle }
    }

    /**
     * 访问某个附件（支持传入 `id` 或 `uri`）：
     * - 如果该附件的 `uri` 尚未位于内部目录 `<filesDir>/<session_id>/<file_id>`，
     *   在第一次访问时自动将其复制粘贴到内部目录 `<filesDir>/<session_id>/<file_id>` 并持久化更新其 `uri`。
     */
    fun accessAttachment(
        idOrUri: String,
        touchTimestamp: Boolean = false,
    ): SessionAttachment? {
        val found = findAttachment(idOrUri) ?: return null
        val internalized = SessionAttachmentStorage.ensureCopiedToInternal(
            sessionId = id,
            attachment = found,
        )
        if (internalized != found) {
            updateState(touchTimestamp = touchTimestamp) { current ->
                val updatedList = current.attachments.map { existing ->
                    if (existing.id == found.id) internalized else existing
                }
                val updatedFilePool = if (!internalized.isImage) {
                    current.fileUriPool.map { if (it == found.uri || it == found.originalUri) internalized.uri else it }.distinct()
                } else {
                    current.fileUriPool
                }
                val updatedImagePool = if (internalized.isImage) {
                    current.imageUriPool.map { if (it == found.uri || it == found.originalUri) internalized.uri else it }.distinct()
                } else {
                    current.imageUriPool
                }
                current.copy(
                    attachments = updatedList,
                    fileUriPool = updatedFilePool,
                    imageUriPool = updatedImagePool,
                )
            }
        }
        return internalized
    }

    /**
     * 将模型或工具传入的附件 `id`（或原始 `uri`）解析为可访问的内部文件 URI：
     * - 若匹配到会话附件，会在首次访问时触发内部目录拷贝 `<filesDir>/<session_id>/<file_id>` 并返回更新后的 `uri`；
     * - 若未匹配到会话附件，则原样返回去除首尾空白的 [idOrUri]。
     */
    fun accessAttachmentUri(
        idOrUri: String,
        touchTimestamp: Boolean = false,
    ): String {
        val trimmed = idOrUri.trim()
        if (trimmed.isEmpty()) return ""
        return accessAttachment(trimmed, touchTimestamp = touchTimestamp)?.uri ?: trimmed
    }

    /**
     * 注册或获取单个文件/图片附件（分为 `id`、`fileName`、`description`、`uri`）。
     * 若相同 URI 或 `preferredId` 已存在则复用已有附件（如提供了非空 [description]/[fileName] 或更新了内部 [uri] 则一并同步）。
     */
    fun registerAttachment(
        uri: String,
        isImage: Boolean,
        description: String = "",
        preferredId: String? = null,
        touchTimestamp: Boolean = true,
        fileName: String = "",
        originalUri: String = uri,
    ): SessionAttachment {
        val trimmedUri = uri.trim()
        require(trimmedUri.isNotEmpty()) { "Attachment URI must not be blank" }
        val trimmedOriginalUri = originalUri.trim().ifEmpty { trimmedUri }
        var registered: SessionAttachment? = null
        updateState(touchTimestamp = touchTimestamp) { current ->
            val updated = upsertAttachmentInList(
                existing = current.attachments,
                uri = trimmedUri,
                isImage = isImage,
                description = description,
                preferredId = preferredId,
                fileName = fileName,
                originalUri = trimmedOriginalUri,
                replaceExistingUri = true,
            )
            registered = updated.second
            val newList = updated.first
            val nextFilePool = if (!isImage) {
                mergeNormalizedUris(current.fileUriPool, listOf(updated.second.uri))
            } else {
                current.fileUriPool
            }
            val nextImagePool = if (isImage) {
                mergeNormalizedUris(current.imageUriPool, listOf(updated.second.uri))
            } else {
                current.imageUriPool
            }
            current.copy(
                attachments = newList,
                fileUriPool = nextFilePool,
                imageUriPool = nextImagePool,
            )
        }
        return registered ?: findAttachment(trimmedUri) ?: findAttachment(trimmedOriginalUri)!!
    }

    /**
     * 更新指定附件 `id` 的描述信息并持久化。
     */
    fun updateAttachmentDescription(
        attachmentId: String,
        description: String,
        touchTimestamp: Boolean = true,
    ): SessionAttachment? {
        val targetId = attachmentId.trim()
        val trimmedDesc = description.trim()
        if (targetId.isEmpty() || trimmedDesc.isEmpty()) return findAttachment(targetId)
        var updatedAttachment: SessionAttachment? = null
        updateState(touchTimestamp = touchTimestamp) { current ->
            val nextList = current.attachments.map { existing ->
                if (existing.id == targetId) {
                    existing.copy(description = trimmedDesc).also { updatedAttachment = it }
                } else {
                    existing
                }
            }
            if (updatedAttachment == null) current else current.copy(attachments = nextList)
        }
        return updatedAttachment
    }

    /**
     * 更新指定附件 `id` 的文件名并持久化。
     */
    fun updateAttachmentFileName(
        attachmentId: String,
        fileName: String,
        touchTimestamp: Boolean = true,
    ): SessionAttachment? {
        val targetId = attachmentId.trim()
        val trimmedName = fileName.trim()
        if (targetId.isEmpty() || trimmedName.isEmpty()) return findAttachment(targetId)
        var updatedAttachment: SessionAttachment? = null
        updateState(touchTimestamp = touchTimestamp) { current ->
            val nextList = current.attachments.map { existing ->
                if (existing.id == targetId) {
                    existing.copy(fileName = trimmedName).also { updatedAttachment = it }
                } else {
                    existing
                }
            }
            if (updatedAttachment == null) current else current.copy(attachments = nextList)
        }
        return updatedAttachment
    }

    internal fun allocateNextAttachmentId(isImage: Boolean): String {
        return synchronized(stateLock) {
            generateNextAttachmentId(existing = _state.value.attachments, isImage = isImage)
        }
    }

    /**
     * 文件输入接口：提供文件 [uri] 与对文件的 [description]，通过统一的 [fileTransfer]（`uri -> uri`）
     * 将文件移动到当前会话内部目录 `<filesDir>/<session_id>/<file_id>` 并注册为 [SessionAttachment]
     * （包含 `id`、`fileName`、`description`、`uri`）。
     *
     * @throws IllegalArgumentException 当 [uri] 为空时抛出；
     * @throws IllegalStateException 当源文件不存在、不可读或内部存储未初始化导致移入失败时抛出。
     */
    suspend fun moveFileToSession(
        uri: String,
        description: String = "",
        fileName: String = "",
        isImage: Boolean? = null,
        preferredId: String? = null,
        touchTimestamp: Boolean = true,
        fileTransfer: UriFileTransfer = SessionAttachmentStorage.defaultFileTransfer,
    ): SessionAttachment {
        return moveFileToSessionWithResult(
            uri = uri,
            description = description,
            fileName = fileName,
            isImage = isImage,
            preferredId = preferredId,
            touchTimestamp = touchTimestamp,
            fileTransfer = fileTransfer,
        ).attachment
    }

    /**
     * 与 [moveFileToSession] 相同的移入逻辑，额外返回底层文件移动/拷贝详情（含 [SessionFileTransferResult.sourceDeleted] 与字节数）。
     */
    suspend fun moveFileToSessionWithResult(
        uri: String,
        description: String = "",
        fileName: String = "",
        isImage: Boolean? = null,
        preferredId: String? = null,
        touchTimestamp: Boolean = true,
        fileTransfer: UriFileTransfer = SessionAttachmentStorage.defaultFileTransfer,
        transferMode: TransferMode = TransferMode.MOVE,
    ): SessionFileMoveResult {
        val trimmedUri = uri.trim()
        require(trimmedUri.isNotEmpty()) { "Source URI must not be blank" }

        val existing = findAttachment(trimmedUri)
            ?: preferredId?.trim()?.takeIf { it.isNotEmpty() }?.let { findAttachment(it) }
        val inferredFileName = fileName.trim().ifEmpty {
            SessionAttachmentStorage.inferDisplayName(trimmedUri)
        }
        val resolvedIsImage = isImage
            ?: existing?.isImage
            ?: SessionAttachmentStorage.inferIsImage(fileName = inferredFileName, rawUri = trimmedUri)
        val targetId = preferredId?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing?.id
            ?: allocateNextAttachmentId(isImage = resolvedIsImage)
        val finalFileName = inferredFileName.ifEmpty {
            existing?.normalizedFileName?.takeIf { it.isNotEmpty() }
                ?: SessionAttachment.defaultFileNameFor(
                    uri = trimmedUri,
                    fallbackId = targetId,
                    isImage = resolvedIsImage,
                )
        }
        val finalDescription = description.trim().ifEmpty {
            existing?.description?.takeIf { it.isNotEmpty() }
                ?: SessionAttachment.defaultDescriptionFor(
                    uri = trimmedUri,
                    isImage = resolvedIsImage,
                    fileName = finalFileName,
                )
        }

        val transfer = SessionAttachmentStorage.moveSourceToInternal(
            sessionId = id,
            fileId = targetId,
            sourceUri = trimmedUri,
            fileTransfer = fileTransfer,
            mode = transferMode,
        )
        if (!transfer.success) {
            throw IllegalStateException(transfer.message.ifBlank { "Failed to move file into session" })
        }

        val registered = registerAttachment(
            uri = transfer.targetUri,
            isImage = resolvedIsImage,
            description = finalDescription,
            preferredId = targetId,
            touchTimestamp = touchTimestamp,
            fileName = finalFileName,
            originalUri = existing?.originalUri ?: trimmedUri,
        )
        return SessionFileMoveResult(
            attachment = registered,
            transfer = transfer,
        )
    }

    /**
     * 文件输出接口：根据会话内的附件 [attachmentId] 与目标 [targetUri]（支持本地文件路径、`file://`、`content://` 或目标目录），
     * 通过统一的 [fileTransfer]（`uri -> uri`）将会话内部文件导出到指定位置；当目标为目录时自动使用附件的 [SessionAttachment.normalizedFileName]。
     */
    suspend fun exportFileFromSession(
        attachmentId: String,
        targetUri: String,
        overwrite: Boolean = true,
        fileTransfer: UriFileTransfer = SessionAttachmentStorage.defaultFileTransfer,
    ): SessionFileTransferResult {
        val trimmedId = attachmentId.trim()
        if (trimmedId.isEmpty()) {
            return SessionFileTransferResult(
                success = false,
                message = "Attachment ID must not be blank",
            )
        }
        val trimmedTarget = targetUri.trim()
        if (trimmedTarget.isEmpty()) {
            return SessionFileTransferResult(
                success = false,
                message = "Target URI must not be blank",
            )
        }
        val resolved = accessAttachment(trimmedId)
            ?: return SessionFileTransferResult(
                success = false,
                message = "Attachment not found in session: $trimmedId",
            )
        return SessionAttachmentStorage.exportFromInternal(
            sessionId = id,
            attachment = resolved,
            targetUri = trimmedTarget,
            overwrite = overwrite,
            fileTransfer = fileTransfer,
        )
    }

    /**
     * 将当前会话作为 Koog [ToolSet] 注册为 [ToolRegistry]（暴露 `@Tool` 标注的 [output] 与 [sendFileCard]）。
     */
    fun asToolRegistry(): ToolRegistry = ToolRegistry {
        tools(this@ChatSession)
    }

    /**
     * 对同一会话内的多轮 Agent 执行进行串行保护，不同会话之间互不阻塞。
     * 同时维护状态机中的 [ChatSessionState.Chatting] 状态迁移。
     */
    suspend fun <T> withTurnLock(block: suspend () -> T): T = turnMutex.withLock {
        beginChatTurn()
        try {
            block()
        } finally {
            endChatTurn()
        }
    }

    /**
     * 显式将当前会话状态机迁入 [ChatSessionState.Planning]。
     * 规划期间外部用户输入将自动写入消息列表并暂存，待规划者进入下一个检查点时统一接收。
     */
    fun enterPlanning(
        checkpointIndex: Int = 1,
        totalCheckpoints: Int = 0,
        currentCheckpointTitle: String = "",
    ) {
        synchronized(stateLock) {
            activePlanningState = ChatSessionState.Planning(
                checkpointIndex = checkpointIndex.coerceAtLeast(1),
                totalCheckpoints = totalCheckpoints.coerceAtLeast(0),
                currentCheckpointTitle = currentCheckpointTitle.trim(),
                queuedExternalInputCount = _state.value.pendingPlannerInputs.size,
            )
            refreshSessionStateLocked()
        }
    }

    /**
     * 更新当前规划阶段的检查点进度信息。若会话当前未处于规划态则自动迁入规划态。
     */
    fun updatePlanningCheckpoint(
        checkpointIndex: Int,
        totalCheckpoints: Int,
        currentCheckpointTitle: String = "",
    ) {
        synchronized(stateLock) {
            activePlanningState = ChatSessionState.Planning(
                checkpointIndex = checkpointIndex.coerceAtLeast(1),
                totalCheckpoints = totalCheckpoints.coerceAtLeast(0),
                currentCheckpointTitle = currentCheckpointTitle.trim(),
                queuedExternalInputCount = _state.value.pendingPlannerInputs.size,
            )
            refreshSessionStateLocked()
        }
    }

    /**
     * 退出 [ChatSessionState.Planning] 状态，并按当前上下文自动回退到 [ChatSessionState.Chatting] 或 [ChatSessionState.Idle]。
     */
    fun exitPlanning() {
        synchronized(stateLock) {
            if (activePlanningState == null) return
            activePlanningState = null
            refreshSessionStateLocked()
        }
    }

    /**
     * 原子提取并清空在规划思考期间累积的全部外部用户输入，供规划者在新检查点中一并接收。
     */
    fun drainPendingPlannerInputs(): List<UserInput> {
        synchronized(stateLock) {
            val current = _state.value
            if (current.pendingPlannerInputs.isEmpty()) return emptyList()
            val drained = current.pendingPlannerInputs
            val updatedWithoutQueue = current.copy(pendingPlannerInputs = emptyList())
            val nextState = computeSessionStateLocked(updatedWithoutQueue)
            _state.value = updatedWithoutQueue.copy(
                updatedAtMillis = System.currentTimeMillis(),
                sessionState = nextState,
            )
            return drained
        }
    }

    /**
     * 向当前会话提交一个待用户回答的 [Question]。
     * 若队列原本为空且 [displayInGui] 为 true，会立即通过 Markdown 窗口向用户展示提问内容及其附带文件/图片。
     */
    fun askQuestion(
        question: Question,
        displayInGui: Boolean = true,
    ) {
        var shouldDisplayNow = false
        synchronized(stateLock) {
            val wasEmpty = _state.value.pendingQuestions.isEmpty()
            pendingQuestionDisplayFlags.addLast(displayInGui)
            val current = _state.value
            val updatedPendingQuestions = current.pendingQuestions + question
            val updatedBase = current.copy(
                updatedAtMillis = System.currentTimeMillis(),
                pendingQuestions = updatedPendingQuestions,
            )
            _state.value = updatedBase.copy(
                sessionState = computeSessionStateLocked(updatedBase),
            )
            shouldDisplayNow = wasEmpty &&
                displayInGui &&
                (question.content.isNotBlank() || question.hasAttachments)
        }
        if (shouldDisplayNow) {
            outputMarkdown(
                markdown = question.content,
                provider = null,
                modelId = null,
                senderLabel = DEFAULT_QUESTION_SENDER_LABEL,
                fileUris = question.normalizedFileUris,
                imageUris = question.normalizedImageUris,
            )
        }
    }

    /**
     * 挂起式向用户发起提问，直到用户在会话中输入回答后返回文本结果。
     */
    suspend fun askQuestionAndWait(
        content: String,
        fileUris: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        displayInGui: Boolean = true,
    ): String {
        return askQuestionAndWaitInput(
            content = content,
            fileUris = fileUris,
            imageUris = imageUris,
            displayInGui = displayInGui,
        ).text
    }

    /**
     * 挂起式向用户发起提问，直到用户在会话中输入回答后返回包含文本、文件 URI 与图片 URI 的完整 [UserInput]。
     */
    suspend fun askQuestionAndWaitInput(
        content: String,
        fileUris: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        displayInGui: Boolean = true,
    ): UserInput {
        val deferred = CompletableDeferred<UserInput>()
        askQuestion(
            question = Question(
                content = content,
                fileUris = fileUris,
                imageUris = imageUris,
                onInputAnswer = { answerInput -> deferred.complete(answerInput) },
            ),
            displayInGui = displayInGui,
        )
        return deferred.await()
    }

    fun clearPendingQuestions(touchTimestamp: Boolean = true) {
        synchronized(stateLock) {
            val current = _state.value
            if (current.pendingQuestions.isEmpty()) return
            pendingQuestionDisplayFlags.clear()
            val cleared = current.copy(
                updatedAtMillis = if (touchTimestamp) System.currentTimeMillis() else current.updatedAtMillis,
                pendingQuestions = emptyList(),
            )
            _state.value = cleared.copy(
                sessionState = computeSessionStateLocked(cleared),
            )
        }
    }

    /**
     * 向当前会话添加一张工具集权限请求卡片。
     */
    fun appendPermissionRequest(
        toolsetId: String,
        toolsetTitle: String,
        toolsetDescription: String = "",
        requiredPrivileges: List<AndroidPrivilege>,
        isCompleted: Boolean = false,
        isGranted: Boolean = false,
        matchedPrivileges: List<AndroidPrivilege> = emptyList(),
        availablePrivileges: List<AndroidPrivilege> = emptyList(),
        resultSummary: String = "",
    ): ChatItem.PermissionRequestItem {
        val item = ChatItem.PermissionRequestItem(
            toolsetId = toolsetId,
            toolsetTitle = toolsetTitle,
            toolsetDescription = toolsetDescription,
            requiredPrivileges = requiredPrivileges,
            isCompleted = isCompleted,
            isGranted = isGranted,
            matchedPrivileges = matchedPrivileges,
            availablePrivileges = availablePrivileges,
            resultSummary = resultSummary,
        )
        appendItem(item)
        return item
    }

    internal suspend fun checkCurrentPrivileges(
        requiredPrivileges: Set<AndroidPrivilege> = emptySet(),
        customPrivilegeChecker: (suspend (Set<AndroidPrivilege>) -> Set<AndroidPrivilege>)? = null,
    ): Set<AndroidPrivilege> {
        val effectiveChecker = customPrivilegeChecker
            ?: this.privilegeChecker
            ?: ToolsetPermissionCatalog::checkCurrentPrivileges
        return runCatching {
            effectiveChecker(requiredPrivileges)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptySet()
        }
    }

    /**
     * 发送工具集权限请求卡片并挂起等待用户在卡片上点击“确定”；
     * - 若在调用时该工具集所需权限**已经满足**，则直接短路返回已就绪结果（绝不向聊天界面发送重复的权限请求卡片，也不阻塞等待用户点击）；
     * - 若当前权限尚未满足，才向界面发送卡片并挂起等待用户点击“确定”后重检。
     */
    suspend fun requestToolsetPermissionAndWait(
        spec: ToolsetPermissionSpec,
        privilegeChecker: (suspend (Set<AndroidPrivilege>) -> Set<AndroidPrivilege>)? = null,
    ): ToolsetPermissionCheckResult {
        val currentPrivileges = checkCurrentPrivileges(
            requiredPrivileges = spec.requiredPrivileges.toSet(),
            customPrivilegeChecker = privilegeChecker,
        )
        if (spec.isSatisfiedBy(currentPrivileges)) {
            return ToolsetPermissionCatalog.buildAlreadySatisfiedSummary(
                spec = spec,
                availablePrivileges = currentPrivileges,
            )
        }

        val deferred = CompletableDeferred<ToolsetPermissionCheckResult>()
        val item = ChatItem.PermissionRequestItem(
            toolsetId = spec.toolsetId,
            toolsetTitle = spec.displayTitle,
            toolsetDescription = spec.description,
            requiredPrivileges = spec.requiredPrivileges,
            isCompleted = false,
        )
        synchronized(stateLock) {
            pendingPermissionRequests[item.id] = PendingPermissionRequestEntry(
                itemId = item.id,
                spec = spec,
                privilegeChecker = privilegeChecker ?: this.privilegeChecker,
                deferred = deferred,
            )
        }
        appendItem(item)
        return try {
            deferred.await()
        } finally {
            synchronized(stateLock) {
                pendingPermissionRequests.remove(item.id)
            }
        }
    }

    /**
     * 当用户在权限请求卡片（[itemId]）上点击“确定”时调用：
     * 重新检查当前可用权限，更新卡片完成状态与检查结果，并唤醒挂起等待的权限请求工具调用。
     */
    suspend fun confirmPermissionRequest(
        itemId: String,
        customPrivilegeChecker: (suspend (Set<AndroidPrivilege>) -> Set<AndroidPrivilege>)? = null,
    ): ToolsetPermissionCheckResult? {
        val pendingEntry = synchronized(stateLock) {
            pendingPermissionRequests.remove(itemId)
        }
        val existingItem = _state.value.items
            .filterIsInstance<ChatItem.PermissionRequestItem>()
            .firstOrNull { it.id == itemId }

        if (pendingEntry == null && existingItem == null) {
            return null
        }

        val spec = pendingEntry?.spec
            ?: existingItem?.let { item ->
                ToolsetPermissionCatalog.resolve(item.toolsetId) ?: ToolsetPermissionSpec(
                    toolsetId = item.toolsetId,
                    displayTitle = item.toolsetTitle,
                    description = item.toolsetDescription,
                    requiredPrivileges = item.requiredPrivileges,
                )
            }
            ?: return null

        val availablePrivileges = checkCurrentPrivileges(
            requiredPrivileges = spec.requiredPrivileges.toSet(),
            customPrivilegeChecker = customPrivilegeChecker ?: pendingEntry?.privilegeChecker,
        )

        val checkResult = ToolsetPermissionCatalog.buildResultSummary(
            spec = spec,
            availablePrivileges = availablePrivileges,
        ).copy(requestId = itemId)

        updateItem(itemId = itemId, touchTimestamp = true) { current ->
            if (current is ChatItem.PermissionRequestItem) {
                current.copy(
                    isCompleted = true,
                    isGranted = checkResult.isGranted,
                    matchedPrivileges = checkResult.matchedPrivileges,
                    availablePrivileges = checkResult.availablePrivileges.toList(),
                    resultSummary = checkResult.summaryText,
                )
            } else {
                current
            }
        }

        pendingEntry?.deferred?.complete(checkResult)
        return checkResult
    }

    /**
     * 接收用户输入（文本、文件 URI 与图片 URI）：
     * 1. 先写入 GUI 用户消息气泡并按需更新默认会话标题；
     * 2. 若存在待处理 [Question]，优先消费队首问题并调用其回调函数（不转发给对话模型）；
     * 3. 否则发送给 [chatAgent] 处理，并将模型最终回复通过 Markdown 窗口输出到界面。
     */
    suspend fun input(
        text: String,
        fileUris: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        chatAgent: ChatAgent? = null,
        chatManager: ChatAgentManager? = null,
    ): ChatInputResult {
        return input(
            input = UserInput(
                text = text,
                fileUris = fileUris,
                imageUris = imageUris,
            ),
            chatAgent = chatAgent,
            chatManager = chatManager,
        )
    }

    /**
     * 接收统一封装的 [UserInput]：
     * 1. 先写入 GUI 用户消息气泡并按需更新默认会话标题；
     * 2. 若存在待处理 [Question]，优先消费队首问题并调用其回调函数（不转发给对话模型）；
     * 3. 否则发送给 [chatAgent] 处理，并将模型最终回复通过 Markdown 窗口输出到界面。
     */
    suspend fun input(
        input: UserInput,
        chatAgent: ChatAgent? = null,
        chatManager: ChatAgentManager? = null,
    ): ChatInputResult {
        val normalizedInput = input.normalized()
        require(!normalizedInput.isBlank) { "Input text or attachments must not be empty" }

        val inputItem = appendUserItem(
            content = normalizedInput.text,
            fileUris = normalizedInput.fileUris,
            imageUris = normalizedInput.imageUris,
            fileDescriptions = normalizedInput.fileDescriptions,
            imageDescriptions = normalizedInput.imageDescriptions,
            fileNames = normalizedInput.fileNames,
            imageNames = normalizedInput.imageNames,
        )
        updateTitleIfDefault(
            input = normalizedInput,
            defaultTitlePrefix = defaultTitlePrefix,
        )

        val decision = routeInputLocked(normalizedInput)
        when (decision) {
            is InputDispatchDecision.AnswerQuestion -> {
                currentCoroutineContext()[TaskExecutionContext]?.recordInput("QUESTION_ANSWERED", inputItem.id)
                decision.question.dispatchAnswer(normalizedInput)
                val nextQuestionToDisplay = decision.nextQuestionToDisplay
                if (nextQuestionToDisplay != null &&
                    nextQuestionToDisplay.displayInGui &&
                    (nextQuestionToDisplay.question.content.isNotBlank() || nextQuestionToDisplay.question.hasAttachments)
                ) {
                    outputMarkdown(
                        markdown = nextQuestionToDisplay.question.content,
                        provider = null,
                        modelId = null,
                        senderLabel = DEFAULT_QUESTION_SENDER_LABEL,
                        fileUris = nextQuestionToDisplay.question.normalizedFileUris,
                        imageUris = nextQuestionToDisplay.question.normalizedImageUris,
                    )
                }
                return ChatInputResult.QuestionAnswered(
                    question = decision.question,
                    answer = normalizedInput.text,
                    input = normalizedInput,
                )
            }

            is InputDispatchDecision.QueueForPlanner -> {
                currentCoroutineContext()[TaskExecutionContext]?.recordInput("QUEUED_FOR_PLANNER", inputItem.id)
                return ChatInputResult.QueuedForPlanner(
                    input = normalizedInput,
                    checkpointIndex = decision.checkpointIndex,
                    queuedCount = decision.queuedCount,
                )
            }

            InputDispatchDecision.ForwardToChatAgent -> currentCoroutineContext()[TaskExecutionContext]?.recordInput("USER_REQUEST", inputItem.id)
        }

        val manager = chatManager ?: chatAgent?.manager ?: defaultChatManager
        val reply = manager.chatWithDetails(
            input = normalizedInput,
            session = this,
        )
        if (reply.content.isNotBlank()) {
            val outputItem = outputMarkdown(
                markdown = reply.content,
                provider = reply.provider,
                modelId = reply.modelId,
            )
            currentCoroutineContext()[TaskExecutionContext]?.recordOutput(outputItem.id)
        }
        return ChatInputResult.ModelReplied(reply = reply)
    }

    /**
     * 将 Markdown 内容直接输出到当前会话的 GUI（以支持 Markdown 渲染的消息窗口展示）。
     * 同时作为 Koog Tool 注册给对话模型，允许模型在执行中途向界面输出阶段性结果。
     */
    @Tool
    @LLMDescription(
        "Output a Markdown message directly to the user's chat GUI window immediately. " +
            "Use this tool to send intermediate updates, explanations, or formatted results mid-turn without waiting for the final reply.",
    )
    fun output(
        @LLMDescription("Markdown-formatted content to display in the chat GUI.")
        markdown: String,
    ): String {
        if (markdown.isBlank()) {
            return "Skipped empty output."
        }
        val (provider, modelId) = synchronized(stateLock) {
            activeProvider to activeModelId
        }
        outputMarkdown(
            markdown = markdown,
            provider = provider,
            modelId = modelId,
        )
        return "Output displayed in chat GUI."
    }

    /**
     * 向当前会话的 GUI 推送一张可点击打开文件的消息卡片。
     * 同时作为 Koog Tool 注册给对话模型，便于模型将文件结果以卡片形式展示给用户一键打开。
     */
    @Tool
    @LLMDescription(
        "Display a clickable file message card in the chat GUI so the user can tap it to open the file. " +
            "Pass an existing session attachment ID (e.g. 'file_1', 'img_1') or a local file path / URI, along with optional display file name and description.",
    )
    fun sendFileCard(
        @LLMDescription("Session attachment ID (e.g. 'file_1', 'img_1') or file path / URI to open.")
        attachmentIdOrUri: String,
        @LLMDescription("Optional display file name shown on the card (defaults to attachment fileName).")
        fileName: String = "",
        @LLMDescription("Optional description shown on the card (defaults to attachment description).")
        description: String = "",
    ): String {
        val trimmed = attachmentIdOrUri.trim()
        if (trimmed.isEmpty()) {
            return "Skipped empty file card target."
        }
        val card = appendFileCard(
            attachmentIdOrUri = trimmed,
            fileName = fileName,
            description = description,
        )
        return "File card displayed in chat GUI (id=${card.attachmentId.ifBlank { card.id }}, fileName=${card.fileName})."
    }

    /**
     * 向当前会话消息流添加一张可点击打开文件的 [ChatItem.FileCardItem]。
     * 支持传入已有附件 ID（如 `file_1`、`img_1`）或外部文件路径 / URI。
     */
    fun appendFileCard(
        attachmentIdOrUri: String,
        fileName: String = "",
        description: String = "",
        isImage: Boolean? = null,
    ): ChatItem.FileCardItem {
        val key = attachmentIdOrUri.trim()
        require(key.isNotEmpty()) { "File card target must not be blank" }

        val existing = accessAttachment(key)
        val resolvedAttachment = if (existing != null) {
            val withName = if (fileName.isNotBlank() && existing.fileName != fileName.trim()) {
                updateAttachmentFileName(existing.id, fileName.trim(), touchTimestamp = false) ?: existing
            } else {
                existing
            }
            if (description.isNotBlank() && withName.description != description.trim()) {
                updateAttachmentDescription(withName.id, description.trim(), touchTimestamp = false) ?: withName
            } else {
                withName
            }
        } else {
            val inferredName = fileName.trim().ifEmpty {
                SessionAttachmentStorage.inferDisplayName(key)
            }
            val inferredIsImage = isImage ?: SessionAttachmentStorage.inferIsImage(
                fileName = inferredName,
                rawUri = key,
            )
            val registered = registerAttachment(
                uri = key,
                isImage = inferredIsImage,
                description = description,
                fileName = inferredName,
                touchTimestamp = false,
            )
            accessAttachment(registered.id) ?: registered
        }

        val cardFileName = fileName.trim().ifEmpty { resolvedAttachment.normalizedFileName }
        val cardDescription = description.trim().ifEmpty { resolvedAttachment.normalizedDescription }
        val card = ChatItem.FileCardItem(
            attachmentId = resolvedAttachment.id,
            fileName = cardFileName,
            description = cardDescription,
            uri = resolvedAttachment.uri,
            isImage = isImage ?: resolvedAttachment.isImage,
        )
        appendItem(card)
        return card
    }

    /**
     * 将 Markdown 内容写入 GUI 消息窗口并附带可选模型元信息与附件 URI。
     */
    fun outputMarkdown(
        markdown: String,
        provider: LlmProvider? = activeProvider,
        modelId: String? = activeModelId,
        senderLabel: String? = null,
        fileUris: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        isStreaming: Boolean = false,
    ): ChatItem.AssistantMessage {
        return appendAssistantItem(
            markdown = markdown,
            provider = provider,
            modelId = modelId,
            senderLabel = senderLabel,
            fileUris = fileUris,
            imageUris = imageUris,
            isStreaming = isStreaming,
        )
    }

    internal inline fun <T> withActiveModel(
        provider: LlmProvider?,
        modelId: String?,
        block: () -> T,
    ): T {
        val previousProvider: LlmProvider?
        val previousModelId: String?
        synchronized(stateLock) {
            previousProvider = activeProvider
            previousModelId = activeModelId
            activeProvider = provider
            activeModelId = modelId
        }
        return try {
            block()
        } finally {
            synchronized(stateLock) {
                activeProvider = previousProvider
                activeModelId = previousModelId
            }
        }
    }

    fun appendItem(item: ChatItem, touchTimestamp: Boolean = true): ChatItem {
        val itemFileUris = extractFileUrisFromItem(item)
        val itemImageUris = extractImageUrisFromItem(item)
        updateState(touchTimestamp = touchTimestamp) { current ->
            val mergedAttachments = mergeAttachmentsWithUris(
                existing = current.attachments,
                fileUris = itemFileUris,
                imageUris = itemImageUris,
            )
            current.copy(
                items = current.items + item,
                fileUriPool = mergeNormalizedUris(current.fileUriPool, itemFileUris),
                imageUriPool = mergeNormalizedUris(current.imageUriPool, itemImageUris),
                attachments = mergedAttachments,
            )
        }
        return item
    }

    /**
     * 将输入的文件 URI 列表暂存到当前会话的公用 URI 池中，同时注册对应的 [SessionAttachment]（自动去重并保持首次入池顺序）。
     */
    fun stashFileUris(
        uris: Iterable<String>,
        descriptions: Map<String, String> = emptyMap(),
        fileNames: Map<String, String> = emptyMap(),
        touchTimestamp: Boolean = true,
    ): List<String> {
        updateState(touchTimestamp = touchTimestamp) { current ->
            val mergedFiles = mergeNormalizedUris(current.fileUriPool, uris)
            val mergedAttachments = mergeAttachmentsWithUris(
                existing = current.attachments,
                fileUris = uris,
                imageUris = emptyList(),
                fileDescriptions = descriptions,
                fileNames = fileNames,
            )
            if (mergedFiles == current.fileUriPool && mergedAttachments == current.attachments) {
                current
            } else {
                current.copy(
                    fileUriPool = mergedFiles,
                    attachments = mergedAttachments,
                )
            }
        }
        return _state.value.fileUriPool
    }

    /**
     * 将输入的图片 URI 列表暂存到当前会话的公用 URI 池中，同时注册对应的 [SessionAttachment]（自动去重并保持首次入池顺序）。
     */
    fun stashImageUris(
        uris: Iterable<String>,
        descriptions: Map<String, String> = emptyMap(),
        imageNames: Map<String, String> = emptyMap(),
        touchTimestamp: Boolean = true,
    ): List<String> {
        updateState(touchTimestamp = touchTimestamp) { current ->
            val mergedImages = mergeNormalizedUris(current.imageUriPool, uris)
            val mergedAttachments = mergeAttachmentsWithUris(
                existing = current.attachments,
                fileUris = emptyList(),
                imageUris = uris,
                imageDescriptions = descriptions,
                imageNames = imageNames,
            )
            if (mergedImages == current.imageUriPool && mergedAttachments == current.attachments) {
                current
            } else {
                current.copy(
                    imageUriPool = mergedImages,
                    attachments = mergedAttachments,
                )
            }
        }
        return _state.value.imageUriPool
    }

    /**
     * 将输入的文件 URI 与图片 URI 一并暂存到当前会话的公用 URI 池与附件表中，并返回更新后的完整公用 URI 池。
     */
    fun stashUris(
        fileUris: Iterable<String> = emptyList(),
        imageUris: Iterable<String> = emptyList(),
        fileDescriptions: Map<String, String> = emptyMap(),
        imageDescriptions: Map<String, String> = emptyMap(),
        fileNames: Map<String, String> = emptyMap(),
        imageNames: Map<String, String> = emptyMap(),
        touchTimestamp: Boolean = true,
    ): List<String> {
        updateState(touchTimestamp = touchTimestamp) { current ->
            val mergedFiles = mergeNormalizedUris(current.fileUriPool, fileUris)
            val mergedImages = mergeNormalizedUris(current.imageUriPool, imageUris)
            val mergedAttachments = mergeAttachmentsWithUris(
                existing = current.attachments,
                fileUris = fileUris,
                imageUris = imageUris,
                fileDescriptions = fileDescriptions,
                imageDescriptions = imageDescriptions,
                fileNames = fileNames,
                imageNames = imageNames,
            )
            if (mergedFiles == current.fileUriPool &&
                mergedImages == current.imageUriPool &&
                mergedAttachments == current.attachments
            ) {
                current
            } else {
                current.copy(
                    fileUriPool = mergedFiles,
                    imageUriPool = mergedImages,
                    attachments = mergedAttachments,
                )
            }
        }
        return _state.value.uriPool
    }

    /**
     * 将一组文件与图片 URI 注册为 [SessionAttachment]（包含 `id`、`fileName`、`description` 与 `uri`），
     * 并返回对应的文件附件列表与图片附件列表。
     */
    fun registerTurnAttachments(
        fileUris: Iterable<String> = emptyList(),
        imageUris: Iterable<String> = emptyList(),
        fileDescriptions: Map<String, String> = emptyMap(),
        imageDescriptions: Map<String, String> = emptyMap(),
        fileNames: Map<String, String> = emptyMap(),
        imageNames: Map<String, String> = emptyMap(),
        touchTimestamp: Boolean = false,
    ): Pair<List<SessionAttachment>, List<SessionAttachment>> {
        val cleanedFiles = mergeNormalizedUris(emptyList(), fileUris)
        val cleanedImages = mergeNormalizedUris(emptyList(), imageUris)
        stashUris(
            fileUris = cleanedFiles,
            imageUris = cleanedImages,
            fileDescriptions = fileDescriptions,
            imageDescriptions = imageDescriptions,
            fileNames = fileNames,
            imageNames = imageNames,
            touchTimestamp = touchTimestamp,
        )
        val resolvedFiles = cleanedFiles.mapNotNull { findAttachment(it) }
        val resolvedImages = cleanedImages.mapNotNull { findAttachment(it) }
        return resolvedFiles to resolvedImages
    }

    /**
     * 清空当前会话的公用 URI 池与附件表。
     */
    fun clearUriPool(touchTimestamp: Boolean = true) {
        updateState(touchTimestamp = touchTimestamp) { current ->
            if (current.fileUriPool.isEmpty() &&
                current.imageUriPool.isEmpty() &&
                current.attachments.isEmpty()
            ) {
                current
            } else {
                current.copy(
                    fileUriPool = emptyList(),
                    imageUriPool = emptyList(),
                    attachments = emptyList(),
                )
            }
        }
    }

    fun appendUserItem(
        content: String,
        fileUris: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        fileDescriptions: Map<String, String> = emptyMap(),
        imageDescriptions: Map<String, String> = emptyMap(),
        fileNames: Map<String, String> = emptyMap(),
        imageNames: Map<String, String> = emptyMap(),
    ): ChatItem.UserMessage {
        val cleanedFiles = fileUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val cleanedImages = imageUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val (registeredFiles, registeredImages) = registerTurnAttachments(
            fileUris = cleanedFiles,
            imageUris = cleanedImages,
            fileDescriptions = fileDescriptions,
            imageDescriptions = imageDescriptions,
            fileNames = fileNames,
            imageNames = imageNames,
            touchTimestamp = false,
        )
        val attachmentIds = (registeredFiles.map { it.id } + registeredImages.map { it.id }).distinct()
        val item = ChatItem.UserMessage(
            content = content,
            fileUris = cleanedFiles,
            imageUris = cleanedImages,
            attachmentIds = attachmentIds,
        )
        appendItem(item)
        return item
    }

    fun appendAssistantItem(
        markdown: String,
        provider: LlmProvider? = null,
        modelId: String? = null,
        senderLabel: String? = null,
        fileUris: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        isStreaming: Boolean = false,
    ): ChatItem.AssistantMessage {
        val cleanedFiles = fileUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val cleanedImages = imageUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val (registeredFiles, registeredImages) = if (cleanedFiles.isNotEmpty() || cleanedImages.isNotEmpty()) {
            registerTurnAttachments(
                fileUris = cleanedFiles,
                imageUris = cleanedImages,
                touchTimestamp = false,
            )
        } else {
            emptyList<SessionAttachment>() to emptyList()
        }
        val attachmentIds = (registeredFiles.map { it.id } + registeredImages.map { it.id }).distinct()
        val item = ChatItem.AssistantMessage(
            markdown = markdown,
            provider = provider,
            modelId = modelId,
            senderLabel = senderLabel,
            fileUris = cleanedFiles,
            imageUris = cleanedImages,
            attachmentIds = attachmentIds,
            isStreaming = isStreaming,
        )
        appendItem(item)
        return item
    }

    fun appendToolStatus(
        title: String,
        detail: String,
        isCompleted: Boolean = true,
    ): ChatItem.ToolStatusItem {
        val item = ChatItem.ToolStatusItem(
            title = title,
            detail = detail,
            isCompleted = isCompleted,
        )
        appendItem(item)
        return item
    }

    /**
     * 记录一次工具调用开始：向消息流插入“执行中”的 [ChatItem.ToolStatusItem] 并将工具压入 [activeToolCalls] 栈。
     *
     * @return 新插入的 [ChatItem.ToolStatusItem.id]，供 [endToolCall] 在工具执行完成时更新状态。
     */
    fun beginToolCall(
        toolDisplayName: String,
        title: String,
        detail: String,
    ): String {
        val item = ChatItem.ToolStatusItem(
            title = title,
            detail = detail,
            isCompleted = false,
        )
        updateState(touchTimestamp = true) { current ->
            current.copy(
                items = current.items + item,
                activeToolCalls = current.activeToolCalls + toolDisplayName,
            )
        }
        return item.id
    }

    /**
     * 记录一次工具调用结束：将对应 [ChatItem.ToolStatusItem] 更新为“已完成”并从 [activeToolCalls] 栈移除该工具。
     */
    fun endToolCall(
        itemId: String,
        toolDisplayName: String,
        completedDetail: String,
    ) {
        updateState(touchTimestamp = true) { current ->
            val updatedItems = current.items.map { existing ->
                if (existing.id == itemId && existing is ChatItem.ToolStatusItem) {
                    existing.copy(
                        detail = completedDetail,
                        isCompleted = true,
                    )
                } else {
                    existing
                }
            }
            val removeIdx = current.activeToolCalls.lastIndexOf(toolDisplayName)
            val updatedActiveTools = if (removeIdx >= 0) {
                current.activeToolCalls.toMutableList().also { it.removeAt(removeIdx) }
            } else {
                current.activeToolCalls
            }
            current.copy(
                items = updatedItems,
                activeToolCalls = updatedActiveTools,
            )
        }
    }

    fun appendSystemNotice(
        message: String,
        isError: Boolean = false,
    ): ChatItem.SystemNoticeItem {
        val item = ChatItem.SystemNoticeItem(
            message = message,
            isError = isError,
        )
        appendItem(item)
        return item
    }

    fun updateItem(
        itemId: String,
        touchTimestamp: Boolean = false,
        transform: (ChatItem) -> ChatItem,
    ) {
        updateState(touchTimestamp = touchTimestamp) { current ->
            var modified = false
            val updatedItems = current.items.map { existing ->
                if (existing.id == itemId) {
                    val transformed = transform(existing)
                    if (transformed != existing) {
                        modified = true
                    }
                    transformed
                } else {
                    existing
                }
            }
            if (modified) current.copy(items = updatedItems) else current
        }
    }

    fun rename(newTitle: String, touchTimestamp: Boolean = true) {
        val normalized = newTitle.trim()
        if (normalized.isEmpty()) return
        updateState(touchTimestamp = touchTimestamp) { current ->
            explicitlyNamed = true
            if (current.title == normalized) current else current.copy(title = normalized)
        }
    }

    internal fun canGenerateTitle(): Boolean = synchronized(stateLock) {
        val current = _state.value
        !explicitlyNamed && isDefaultTitle(current.title, defaultTitlePrefix) &&
            current.items.none { it is ChatItem.UserMessage }
    }

    internal fun hasExplicitTitle(): Boolean = synchronized(stateLock) { explicitlyNamed }

    internal fun applyGeneratedTitle(title: String) {
        val normalized = title.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('"', '“', '”')?.trim().orEmpty()
        if (normalized.isEmpty()) return
        updateState(touchTimestamp = false) { current ->
            if (explicitlyNamed) return@updateState current
            explicitlyNamed = true
            val end = normalized.offsetByCodePoints(0, normalized.codePointCount(0, normalized.length).coerceAtMost(40))
            current.copy(title = normalized.substring(0, end))
        }
    }

    private fun isDefaultTitle(title: String, prefix: String): Boolean =
        title == prefix || (title.startsWith("$prefix ") && title.removePrefix("$prefix ").toIntOrNull() != null)

    /**
     * 若当前会话仍使用默认标题前缀，则根据首条用户输入内容（或附带文件/图片占位提示）生成简短标题。
     */
    fun updateTitleIfDefault(
        input: UserInput,
        defaultTitlePrefix: String = this.defaultTitlePrefix,
        maxPreviewLength: Int = DEFAULT_TITLE_PREVIEW_LENGTH,
        touchTimestamp: Boolean = true,
    ): Boolean {
        val normalized = input.normalized()
        val previewSource = when {
            normalized.text.isNotEmpty() -> normalized.text
            normalized.imageUris.isNotEmpty() -> "[图片] ${normalized.imageUris.first().substringAfterLast('/')}"
            normalized.fileUris.isNotEmpty() -> "[文件] ${normalized.fileUris.first().substringAfterLast('/')}"
            else -> return false
        }
        return updateTitleIfDefault(
            prompt = previewSource,
            defaultTitlePrefix = defaultTitlePrefix,
            maxPreviewLength = maxPreviewLength,
            touchTimestamp = touchTimestamp,
        )
    }

    /**
     * 若当前会话仍使用默认标题前缀，则根据首条用户消息内容自动生成简短标题。
     */
    fun updateTitleIfDefault(
        prompt: String,
        defaultTitlePrefix: String = this.defaultTitlePrefix,
        maxPreviewLength: Int = DEFAULT_TITLE_PREVIEW_LENGTH,
        touchTimestamp: Boolean = true,
    ): Boolean {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isEmpty()) return false
        var updated = false
        updateState(touchTimestamp = touchTimestamp) { current ->
            if (explicitlyNamed || !isDefaultTitle(current.title, defaultTitlePrefix)) {
                return@updateState current
            }
            val singleLine = normalizedPrompt.replace('\n', ' ')
            val preview = singleLine.take(maxPreviewLength)
            val candidateTitle = if (singleLine.length > maxPreviewLength) "$preview…" else preview
            updated = true
            current.copy(title = candidateTitle)
        }
        return updated
    }

    fun touch(timestampMillis: Long = System.currentTimeMillis()) {
        val persisted = synchronized(stateLock) {
            val current = _state.value
            val updated = current.copy(updatedAtMillis = timestampMillis)
            _state.value = updated
            toPersistedSnapshot(updated)
        }
        onPersist?.invoke(persisted)
    }

    fun seedHistory(messages: List<Message>, touchTimestamp: Boolean = true) {
        updateState(touchTimestamp = touchTimestamp) { current ->
            current.copy(history = messages.toList())
        }
    }

    fun appendHistory(messages: List<Message>, touchTimestamp: Boolean = true) {
        if (messages.isEmpty()) return
        updateState(touchTimestamp = touchTimestamp) { current ->
            current.copy(history = current.history + messages)
        }
    }

    fun recordExchange(
        userMessage: String,
        assistantReply: String,
        userAttachments: List<Attachment> = emptyList(),
        touchTimestamp: Boolean = true,
    ) {
        updateState(touchTimestamp = touchTimestamp) { current ->
            current.copy(
                history = current.history + listOf(
                    Message.User(
                        content = userMessage,
                        metaInfo = RequestMetaInfo.Empty,
                        attachments = userAttachments,
                    ),
                    Message.Assistant(
                        content = assistantReply,
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                ),
            )
        }
    }

    fun clearHistory(touchTimestamp: Boolean = true) {
        updateState(touchTimestamp = touchTimestamp) { current ->
            if (current.history.isEmpty()) current else current.copy(history = emptyList())
        }
    }

    fun clearItems(touchTimestamp: Boolean = true) {
        updateState(touchTimestamp = touchTimestamp) { current ->
            if (current.items.isEmpty()) current else current.copy(items = emptyList())
        }
    }

    fun clear(touchTimestamp: Boolean = true) {
        var persistedToEmit: PersistedChatSession? = null
        val cancelledPermissionDeferreds = mutableListOf<CompletableDeferred<ToolsetPermissionCheckResult>>()
        synchronized(stateLock) {
            val current = _state.value
            if (current.items.isEmpty() &&
                current.history.isEmpty() &&
                current.fileUriPool.isEmpty() &&
                current.imageUriPool.isEmpty() &&
                current.attachments.isEmpty() &&
                current.pendingQuestions.isEmpty() &&
                current.pendingPlannerInputs.isEmpty() &&
                pendingPermissionRequests.isEmpty()
            ) {
                return
            }
            pendingQuestionDisplayFlags.clear()
            cancelledPermissionDeferreds.addAll(pendingPermissionRequests.values.map { it.deferred })
            pendingPermissionRequests.clear()
            val cleared = current.copy(
                updatedAtMillis = if (touchTimestamp) System.currentTimeMillis() else current.updatedAtMillis,
                items = emptyList(),
                history = emptyList(),
                fileUriPool = emptyList(),
                imageUriPool = emptyList(),
                attachments = emptyList(),
                pendingQuestions = emptyList(),
                pendingPlannerInputs = emptyList(),
                activeToolCalls = emptyList(),
            )
            val updated = cleared.copy(
                sessionState = computeSessionStateLocked(cleared),
            )
            _state.value = updated
            if (hasPersistentChange(current, updated)) {
                persistedToEmit = toPersistedSnapshot(updated)
            }
        }
        SessionAttachmentStorage.deleteSessionInternalFiles(id)
        cancelledPermissionDeferreds.forEach { it.cancel() }
        persistedToEmit?.let { onPersist?.invoke(it) }
    }

    /**
     * 基于当前会话的短期对话历史构建 Koog [Prompt]。
     */
    fun buildPrompt(
        systemPrompt: String,
        temperature: Double,
        promptId: String = DEFAULT_PROMPT_ID,
    ): Prompt {
        val historySnapshot = historySnapshot()
        return prompt(
            id = promptId,
            params = LLMParams(temperature = temperature),
        ) {
            if (systemPrompt.isNotBlank()) {
                system(systemPrompt)
            }
            if (historySnapshot.isNotEmpty()) {
                messages(historySnapshot)
            }
        }
    }

    private fun beginChatTurn() {
        synchronized(stateLock) {
            activeChatTurnDepth += 1
            refreshSessionStateLocked()
        }
    }

    private fun endChatTurn() {
        synchronized(stateLock) {
            activeChatTurnDepth = (activeChatTurnDepth - 1).coerceAtLeast(0)
            refreshSessionStateLocked()
        }
    }

    private fun routeInputLocked(normalizedInput: UserInput): InputDispatchDecision {
        synchronized(stateLock) {
            val current = _state.value
            val head = current.pendingQuestions.firstOrNull()
            if (head != null) {
                if (pendingQuestionDisplayFlags.isNotEmpty()) {
                    pendingQuestionDisplayFlags.removeFirst()
                }
                val remaining = current.pendingQuestions.drop(1)
                val updated = current.copy(
                    updatedAtMillis = System.currentTimeMillis(),
                    pendingQuestions = remaining,
                )
                _state.value = updated.copy(
                    sessionState = computeSessionStateLocked(updated),
                )
                val next = remaining.firstOrNull()?.let { nextQuestion ->
                    QueuedQuestionEntry(
                        question = nextQuestion,
                        displayInGui = pendingQuestionDisplayFlags.firstOrNull() ?: true,
                    )
                }
                return InputDispatchDecision.AnswerQuestion(
                    question = head,
                    nextQuestionToDisplay = next,
                )
            }

            val planning = activePlanningState
            if (planning != null) {
                val updatedQueue = current.pendingPlannerInputs + normalizedInput
                val updated = current.copy(
                    updatedAtMillis = System.currentTimeMillis(),
                    pendingPlannerInputs = updatedQueue,
                )
                _state.value = updated.copy(
                    sessionState = computeSessionStateLocked(updated),
                )
                return InputDispatchDecision.QueueForPlanner(
                    checkpointIndex = planning.checkpointIndex,
                    queuedCount = updatedQueue.size,
                )
            }

            return InputDispatchDecision.ForwardToChatAgent
        }
    }

    private fun refreshSessionStateLocked() {
        val current = _state.value
        val nextState = computeSessionStateLocked(current)
        if (nextState == current.sessionState) return
        _state.value = current.copy(
            updatedAtMillis = System.currentTimeMillis(),
            sessionState = nextState,
        )
    }

    private fun computeSessionStateLocked(snapshot: Snapshot): ChatSessionState {
        val planning = activePlanningState
        val baseState = when {
            planning != null -> planning.copy(
                queuedExternalInputCount = snapshot.pendingPlannerInputs.size,
            )
            activeChatTurnDepth > 0 -> ChatSessionState.Chatting
            else -> ChatSessionState.Idle
        }
        val headQuestion = snapshot.pendingQuestions.firstOrNull()
        return if (headQuestion != null) {
            ChatSessionState.WaitingAnswer(
                questionPreview = headQuestion.content.trim().take(48),
                pendingCount = snapshot.pendingQuestions.size,
                resumeState = baseState,
            )
        } else {
            baseState
        }
    }

    internal fun toPersistedSnapshot(snapshot: Snapshot = _state.value): PersistedChatSession =
        PersistedChatSession(
            id = snapshot.id,
            title = snapshot.title,
            defaultTitlePrefix = defaultTitlePrefix,
            createdAtMillis = snapshot.createdAtMillis,
            updatedAtMillis = snapshot.updatedAtMillis,
            items = snapshot.items,
            history = snapshot.history,
            fileUriPool = snapshot.fileUriPool,
            imageUriPool = snapshot.imageUriPool,
            attachments = snapshot.attachments,
        )

    private fun hasPersistentChange(previous: Snapshot, next: Snapshot): Boolean {
        return previous.title != next.title ||
            previous.items != next.items ||
            previous.history != next.history ||
            previous.fileUriPool != next.fileUriPool ||
            previous.imageUriPool != next.imageUriPool ||
            previous.attachments != next.attachments
    }

    private inline fun updateState(
        touchTimestamp: Boolean,
        transform: (Snapshot) -> Snapshot,
    ) {
        var persistedToEmit: PersistedChatSession? = null
        synchronized(stateLock) {
            val current = _state.value
            val transformed = transform(current)
            if (transformed == current) return
            val nextState = computeSessionStateLocked(transformed)
            val updated = transformed.copy(
                updatedAtMillis = if (touchTimestamp) System.currentTimeMillis() else transformed.updatedAtMillis,
                sessionState = nextState,
            )
            _state.value = updated
            if (hasPersistentChange(current, updated)) {
                persistedToEmit = toPersistedSnapshot(updated)
            }
        }
        persistedToEmit?.let { onPersist?.invoke(it) }
    }

    companion object {
        const val TOOLSET_NAME = "chat_session"
        const val DEFAULT_TITLE = "新对话"
        const val DEFAULT_TITLE_PREVIEW_LENGTH = 18
        const val DEFAULT_PROMPT_ID = "chat_session_prompt"
        const val DEFAULT_QUESTION_SENDER_LABEL = "模型提问"

        internal fun mergeNormalizedUris(
            existing: Iterable<String>,
            incoming: Iterable<String>,
        ): List<String> {
            val result = LinkedHashSet<String>()
            existing.forEach { raw ->
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) result.add(trimmed)
            }
            incoming.forEach { raw ->
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) result.add(trimmed)
            }
            return result.toList()
        }

        internal fun mergeAttachmentsWithUris(
            existing: Iterable<SessionAttachment>,
            fileUris: Iterable<String> = emptyList(),
            imageUris: Iterable<String> = emptyList(),
            fileDescriptions: Map<String, String> = emptyMap(),
            imageDescriptions: Map<String, String> = emptyMap(),
            fileNames: Map<String, String> = emptyMap(),
            imageNames: Map<String, String> = emptyMap(),
        ): List<SessionAttachment> {
            var currentList = existing.map { it.normalized() }.filter { it.id.isNotEmpty() && it.uri.isNotEmpty() }
            imageUris.forEach { rawUri ->
                val trimmed = rawUri.trim()
                if (trimmed.isNotEmpty()) {
                    currentList = upsertAttachmentInList(
                        existing = currentList,
                        uri = trimmed,
                        isImage = true,
                        description = imageDescriptions[trimmed].orEmpty(),
                        fileName = imageNames[trimmed].orEmpty(),
                    ).first
                }
            }
            fileUris.forEach { rawUri ->
                val trimmed = rawUri.trim()
                if (trimmed.isNotEmpty()) {
                    currentList = upsertAttachmentInList(
                        existing = currentList,
                        uri = trimmed,
                        isImage = false,
                        description = fileDescriptions[trimmed].orEmpty(),
                        fileName = fileNames[trimmed].orEmpty(),
                    ).first
                }
            }
            return currentList
        }

        private fun upsertAttachmentInList(
            existing: List<SessionAttachment>,
            uri: String,
            isImage: Boolean,
            description: String = "",
            preferredId: String? = null,
            fileName: String = "",
            originalUri: String = uri,
            replaceExistingUri: Boolean = false,
        ): Pair<List<SessionAttachment>, SessionAttachment> {
            val trimmedUri = uri.trim()
            val trimmedOriginal = originalUri.trim().ifEmpty { trimmedUri }
            val trimmedDesc = description.trim()
            val trimmedName = fileName.trim()
            val matchedIndex = existing.indexOfFirst {
                it.uri == trimmedUri ||
                    it.originalUri == trimmedUri ||
                    it.uri == trimmedOriginal ||
                    it.originalUri == trimmedOriginal ||
                    (preferredId != null && it.id == preferredId)
            }
            if (matchedIndex >= 0) {
                val current = existing[matchedIndex]
                val nextUri = if (replaceExistingUri && trimmedUri != current.originalUri) {
                    trimmedUri
                } else {
                    current.uri
                }
                val updated = current.copy(
                    uri = nextUri,
                    originalUri = current.originalUri.ifBlank { trimmedOriginal },
                    description = trimmedDesc.ifEmpty { current.description },
                    fileName = trimmedName.ifEmpty { current.normalizedFileName },
                )
                return if (updated == current) {
                    existing to current
                } else {
                    val mutable = existing.toMutableList()
                    mutable[matchedIndex] = updated
                    mutable to updated
                }
            }

            val nextId = preferredId?.trim()?.takeIf { it.isNotEmpty() }
                ?: generateNextAttachmentId(existing = existing, isImage = isImage)
            val resolvedFileName = trimmedName.ifEmpty {
                SessionAttachmentStorage.inferDisplayName(trimmedOriginal).ifEmpty {
                    SessionAttachment.defaultFileNameFor(
                        uri = trimmedOriginal,
                        fallbackId = nextId,
                        isImage = isImage,
                    )
                }
            }
            val resolvedDesc = trimmedDesc.ifEmpty {
                SessionAttachment.defaultDescriptionFor(
                    uri = trimmedOriginal,
                    isImage = isImage,
                    fileName = resolvedFileName,
                )
            }
            val created = SessionAttachment(
                id = nextId,
                description = resolvedDesc,
                uri = trimmedUri,
                originalUri = trimmedOriginal,
                isImage = isImage,
                fileName = resolvedFileName,
            )
            return (existing + created) to created
        }

        private fun generateNextAttachmentId(
            existing: List<SessionAttachment>,
            isImage: Boolean,
        ): String {
            val prefix = if (isImage) "img_" else "file_"
            val usedIds = existing.mapTo(HashSet()) { it.id }
            var index = existing.count { it.isImage == isImage } + 1
            while ("$prefix$index" in usedIds) {
                index++
            }
            return "$prefix$index"
        }

        private fun extractFileUrisFromItem(item: ChatItem): List<String> = when (item) {
            is ChatItem.UserMessage -> item.fileUris
            is ChatItem.AssistantMessage -> item.fileUris
            is ChatItem.FileCardItem -> if (!item.isImage && item.uri.isNotBlank()) listOf(item.uri) else emptyList()
            else -> emptyList()
        }

        private fun extractImageUrisFromItem(item: ChatItem): List<String> = when (item) {
            is ChatItem.UserMessage -> item.imageUris
            is ChatItem.AssistantMessage -> item.imageUris
            is ChatItem.FileCardItem -> if (item.isImage && item.uri.isNotBlank()) listOf(item.uri) else emptyList()
            else -> emptyList()
        }
    }
}
