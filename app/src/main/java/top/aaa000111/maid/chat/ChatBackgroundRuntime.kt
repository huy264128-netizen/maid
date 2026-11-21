package top.aaa000111.maid.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import top.aaa000111.maid.learning.LearningRuntime
import top.aaa000111.maid.learning.TaskRecorder
import top.aaa000111.maid.learning.TaskExecutionContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.aaa000111.maid.agent.ChatAgentManager
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.LlmProviderRegistry
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.ProviderAvailability
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 负责验证对话模型（[ModelRole.FAST_CHEAP]）及 API Key 是否已就绪的前置检查结果。
 */
sealed interface ChatModelAvailability {
    data object Ready : ChatModelAvailability
    data class Unavailable(val noticeMessage: String) : ChatModelAvailability
}

fun interface ChatModelAvailabilityChecker {
    suspend fun checkAvailability(): ChatModelAvailability
}

/**
 * 独立的后台对话任务调度器：
 * 1. 持有独立线程池调度器与 [SupervisorJob] 协程作用域，完全脱离 `Activity` / `ChatViewModel.viewModelScope` 生命周期；
 * 2. 统一管理应用级 [ChatSession] 列表及每个会话的后台运行状态；
 * 3. 输入框确认后的模型信息处理提交到此处异步执行，并通过 `session.input` / `session.output` 与 GUI 交互；
 * 4. 切换或新建 GUI 会话不会中断任何正在后台执行的会话任务。
 */
class ChatTaskDispatcher(
    chatAgent: ChatAgent? = null,
    private val workerDispatcher: CoroutineDispatcher = createDefaultWorkerDispatcher(),
    private val ownsWorkerDispatcher: Boolean = true,
    private val availabilityChecker: ChatModelAvailabilityChecker = DefaultChatModelAvailabilityChecker(),
    private val sessionStore: ChatSessionStore? = null,
    private val taskRecorder: TaskRecorder? = null,
    chatManager: ChatAgentManager? = null,
) {
    val agentManager = chatManager ?: ChatAgentManager(chatAgent ?: ChatAgent(), workerDispatcher)
    private val scopeJob = SupervisorJob()
    private val executionScope = CoroutineScope(scopeJob + workerDispatcher)

    private val sessionLock = Any()
    private val activeTurnJobs = ConcurrentHashMap<String, Job>()
    private val sessionCounter = AtomicInteger(0)

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _runningSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val runningSessionIds: StateFlow<Set<String>> = _runningSessionIds.asStateFlow()

    init {
        restorePersistedSessionsLocked()
    }

    fun ensureInitialSession(defaultTitlePrefix: String): ChatSession {
        synchronized(sessionLock) {
            val existing = _sessions.value.firstOrNull()
            if (existing != null) return existing
            val created = createManagedSession(
                title = defaultTitlePrefix,
                defaultTitlePrefix = defaultTitlePrefix,
            )
            sessionCounter.compareAndSet(0, 1)
            _sessions.value = listOf(created)
            persistSession(created.toPersistedSnapshot())
            return created
        }
    }

    fun preferredInitialSession(defaultTitlePrefix: String): ChatSession {
        synchronized(sessionLock) {
            val currentSessions = _sessions.value
            if (currentSessions.isEmpty()) {
                val created = ensureInitialSession(defaultTitlePrefix)
                sessionStore?.setActiveSessionId(created.id)
                return created
            }
            val savedActiveId = sessionStore?.getActiveSessionId()
            val matched = savedActiveId?.let { targetId ->
                currentSessions.firstOrNull { it.id == targetId }
            }
            val selected = matched ?: currentSessions.first()
            if (matched == null) {
                sessionStore?.setActiveSessionId(selected.id)
            }
            return selected
        }
    }

    fun markActiveSession(sessionId: String) {
        if (sessionId.isBlank()) return
        sessionStore?.setActiveSessionId(sessionId)
    }

    fun createNewSession(defaultTitlePrefix: String): ChatSession {
        synchronized(sessionLock) {
            val nextIndex = (_sessions.value.size + 1).coerceAtLeast(sessionCounter.incrementAndGet())
            val title = if (_sessions.value.isEmpty()) {
                defaultTitlePrefix
            } else {
                "$defaultTitlePrefix $nextIndex"
            }
            val newSession = createManagedSession(
                title = title,
                defaultTitlePrefix = defaultTitlePrefix,
            )
            _sessions.value = listOf(newSession) + _sessions.value
            persistSession(newSession.toPersistedSnapshot())
            sessionStore?.setActiveSessionId(newSession.id)
            return newSession
        }
    }

    fun deleteSession(
        sessionId: String,
        defaultTitlePrefix: String = ChatSession.DEFAULT_TITLE,
    ): ChatSession {
        stopSession(sessionId)
        synchronized(sessionLock) {
            val remaining = _sessions.value.filterNot { it.id == sessionId }
            sessionStore?.deleteSession(sessionId)
            SessionAttachmentStorage.deleteSessionInternalFiles(sessionId)
            if (remaining.isEmpty()) {
                val fallback = createManagedSession(
                    title = defaultTitlePrefix,
                    defaultTitlePrefix = defaultTitlePrefix,
                )
                sessionCounter.compareAndSet(0, 1)
                _sessions.value = listOf(fallback)
                persistSession(fallback.toPersistedSnapshot())
                sessionStore?.setActiveSessionId(fallback.id)
                return fallback
            }
            _sessions.value = remaining
            val currentActiveId = sessionStore?.getActiveSessionId()
            val nextPreferred = remaining.first()
            if (currentActiveId == null || currentActiveId == sessionId) {
                sessionStore?.setActiveSessionId(nextPreferred.id)
            }
            return nextPreferred
        }
    }

    /**
     * 清空所有历史对话会话与对应的内部附件目录，并创建一个干净的初始会话。
     */
    fun clearAllSessions(
        defaultTitlePrefix: String = ChatSession.DEFAULT_TITLE,
    ): ChatSession {
        val existingIds: List<String>
        synchronized(sessionLock) {
            existingIds = _sessions.value.map { it.id }
        }
        existingIds.forEach { id -> stopSession(id) }
        synchronized(sessionLock) {
            SessionAttachmentStorage.deleteAllSessionsInternalFiles(existingIds)
            sessionStore?.clearAllSessions()
            sessionCounter.set(1)
            val fresh = createManagedSession(
                title = defaultTitlePrefix,
                defaultTitlePrefix = defaultTitlePrefix,
            )
            _sessions.value = listOf(fresh)
            persistSession(fresh.toPersistedSnapshot())
            sessionStore?.setActiveSessionId(fresh.id)
            return fresh
        }
    }

    private fun restorePersistedSessionsLocked() {
        val store = sessionStore ?: return
        val persisted = runCatching { store.loadAllSessions() }.getOrDefault(emptyList())
        if (persisted.isEmpty()) return
        synchronized(sessionLock) {
            if (_sessions.value.isNotEmpty()) return
            val restored = persisted.map { entry ->
                ChatSession(
                    id = entry.id,
                    title = entry.title,
                    createdAtMillis = entry.createdAtMillis,
                    updatedAtMillis = entry.updatedAtMillis,
                    items = entry.items,
                    history = entry.history,
                    fileUriPool = entry.fileUriPool,
                    imageUriPool = entry.imageUriPool,
                    attachments = entry.attachments,
                    chatManager = agentManager,
                    defaultTitlePrefix = entry.defaultTitlePrefix,
                    onPersist = ::persistSession,
                )
            }
            _sessions.value = restored
            sessionCounter.set(restored.size)
        }
    }

    private fun createManagedSession(
        title: String,
        defaultTitlePrefix: String,
    ): ChatSession = ChatSession(
        title = title,
        chatManager = agentManager,
        defaultTitlePrefix = defaultTitlePrefix,
        onPersist = ::persistSession,
    )

    private fun persistSession(snapshot: PersistedChatSession) {
        sessionStore?.enqueueSave(snapshot)
    }

    fun findSession(sessionId: String): ChatSession? {
        return _sessions.value.firstOrNull { it.id == sessionId }
    }

    fun isSessionGenerating(sessionId: String): Boolean {
        return sessionId in _runningSessionIds.value
    }

    fun isAnyGenerating(): Boolean {
        return _runningSessionIds.value.isNotEmpty()
    }

    /**
     * 将输入框确认的用户输入提交至独立后台线程调度器处理。
     *
     * - 若当前会话存在待用户回答的 [Question] 或处于 [ChatSessionState.Planning] 规划态，
     *   会直接在独立调度器上投递输入（不受当前会话生成中状态阻塞：提问直接响应回调，规划态则暂存并在下个检查点接收）；
     * - 否则为该会话启动独立后台执行任务，通过 [ChatSession.input] 与 [ChatSession.output] / `outputMarkdown` 完成交互。
     */
    fun submitInput(
        session: ChatSession,
        input: UserInput,
    ): Boolean {
        val normalized = input.normalized()
        if (normalized.isBlank) return false

        if (session.hasPendingQuestion() || session.isPlanning()) {
            executionScope.launch {
                runCatching {
                    val recorder = taskRecorder ?: LearningRuntime.recorderOrNull()
                    if (recorder != null) {
                        recorder.withActiveTask(session.id) { session.input(normalized, chatManager = agentManager) }
                    } else {
                        session.input(normalized, chatManager = agentManager)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    session.appendSystemNotice(
                        message = "处理输入失败：${error.message ?: "未知错误"}",
                        isError = true,
                    )
                }
            }
            return true
        }

        synchronized(sessionLock) {
            val existingJob = activeTurnJobs[session.id]
            if (existingJob != null && existingJob.isActive) {
                return false
            }

            lateinit var launchedJob: Job
            launchedJob = executionScope.launch(start = CoroutineStart.LAZY) {
                try {
                    withTaskEvidence(session.id) {
                        when (val availability = availabilityChecker.checkAvailability()) {
                            is ChatModelAvailability.Unavailable -> {
                                val inputItem = session.appendUserItem(
                                    content = normalized.text,
                                    fileUris = normalized.fileUris,
                                    imageUris = normalized.imageUris,
                                    fileDescriptions = normalized.fileDescriptions,
                                    imageDescriptions = normalized.imageDescriptions,
                                )
                                currentCoroutineContext()[TaskExecutionContext]?.recordInput("USER_REQUEST", inputItem.id)
                                session.updateTitleIfDefault(input = normalized)
                                session.appendSystemNotice(
                                    message = availability.noticeMessage,
                                    isError = true,
                                )
                                (taskRecorder ?: LearningRuntime.recorderOrNull())?.rejectCurrent("model_unavailable")
                                return@withTaskEvidence
                            }

                            ChatModelAvailability.Ready -> Unit
                        }

                        session.input(normalized, chatManager = agentManager)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    session.appendSystemNotice(
                        message = "对话请求失败：${e.message ?: "未知错误"}",
                        isError = true,
                    )
                } finally {
                    val removed = activeTurnJobs.remove(session.id, launchedJob)
                    if (removed) {
                        _runningSessionIds.update { it - session.id }
                    }
                }
            }

            activeTurnJobs[session.id] = launchedJob
            _runningSessionIds.update { it + session.id }
            launchedJob.start()
            return true
        }
    }

    private suspend fun <T> withTaskEvidence(sessionId: String, block: suspend () -> T): T {
        val recorder = taskRecorder ?: LearningRuntime.recorderOrNull()
        return if (recorder != null) recorder.run(sessionId, block = block) else block()
    }

    /**
     * 仅取消指定会话正在进行的后台模型任务，不影响其他会话。
     */
    fun stopSession(sessionId: String) {
        val job = activeTurnJobs.remove(sessionId)
        _runningSessionIds.update { it - sessionId }
        job?.cancel()
    }

    fun shutdown() {
        activeTurnJobs.values.forEach { it.cancel() }
        activeTurnJobs.clear()
        _runningSessionIds.value = emptySet()
        executionScope.cancel()
        if (ownsWorkerDispatcher) {
            val closeable = workerDispatcher as? ExecutorCoroutineDispatcher
            if (closeable != null) {
                runCatching { closeable.close() }
            }
        }
    }

    companion object {
        private val workerThreadId = AtomicInteger(1)

        fun createDefaultWorkerDispatcher(threadCount: Int = 4): ExecutorCoroutineDispatcher {
            val executor = Executors.newFixedThreadPool(threadCount) { runnable ->
                Thread(runnable, "maid-chat-worker-${workerThreadId.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
            return executor.asCoroutineDispatcher()
        }
    }
}

internal class DefaultChatModelAvailabilityChecker(
    private val providerDisplayNameResolver: (LlmProvider) -> String = { it.name },
) : ChatModelAvailabilityChecker {
    override suspend fun checkAvailability(): ChatModelAvailability {
        val configs = ModelConfigRepository.getAllConfigs()
        val chatSelection = configs[ModelRole.FAST_CHEAP]?.takeIf { it.isComplete }
            ?: return ChatModelAvailability.Unavailable(
                "尚未配置对话模型（快速廉价应答模型），请先前往设置页面的「模型选择」完成配置。",
            )

        val provider = chatSelection.provider!!
        val availability = LlmProviderRegistry.checkAvailability(
            provider = provider,
            customProviderId = chatSelection.normalizedCustomProviderId,
        )
        if (availability is ProviderAvailability.Unavailable) {
            val providerName = providerDisplayNameResolver(provider)
            val message = if (provider.usesApiKey) {
                "对话模型所属服务商（$providerName）尚未配置 API Key，请先前往设置页面添加密钥。"
            } else {
                availability.reason
            }
            return ChatModelAvailability.Unavailable(message)
        }

        return ChatModelAvailability.Ready
    }
}

/**
 * 全局应用级后台对话调度器单例，生命周期与进程绑定，后续可直接配合前台服务保活。
 */
object ChatBackgroundRuntime {

    @Volatile
    private var dispatcherInstance: ChatTaskDispatcher = createRuntimeDispatcher(providerNameResolver = null)

    val current: ChatTaskDispatcher
        get() = dispatcherInstance

    fun init(context: android.content.Context) {
        val appContext = context.applicationContext
        ChatSessionRepository.init(appContext)
        synchronized(this) {
            // 仅在尚未存在任何会话或调度器时初始化带本地化服务商名称的实例
            if (dispatcherInstance.sessions.value.isEmpty() && !dispatcherInstance.isAnyGenerating()) {
                dispatcherInstance.shutdown()
                dispatcherInstance = createRuntimeDispatcher { provider ->
                    appContext.getString(provider.displayNameRes)
                }
            }
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            dispatcherInstance.shutdown()
            dispatcherInstance = createRuntimeDispatcher(providerNameResolver = null)
        }
    }

    private fun createRuntimeDispatcher(
        providerNameResolver: ((LlmProvider) -> String)?,
    ): ChatTaskDispatcher {
        val availabilityChecker = if (providerNameResolver != null) {
            DefaultChatModelAvailabilityChecker(providerNameResolver)
        } else {
            DefaultChatModelAvailabilityChecker()
        }
        return ChatTaskDispatcher(
            availabilityChecker = availabilityChecker,
            sessionStore = if (ChatSessionRepository.isReady) ChatSessionRepository else null,
        )
    }
}
