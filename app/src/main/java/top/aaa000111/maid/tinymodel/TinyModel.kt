package top.aaa000111.maid.tinymodel

import android.content.Context
import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内部专用的端侧轻量模型（基于 LiteRT-LM），不向用户设置界面暴露，专门承担内部辅助事务
 * （如标题生成、短文本摘要、分类路由、结构化信息抽取等）。
 *
 * 当前默认规格为 [TinyModelCatalog.LFM2_5_1_2B_INSTRUCT_INT4_GPU]（`LFM2.5-1.2B-Instruct` 到
 * LiteRT-LM 的移植版），模型文件直接放置在 `app/src/main/assets/` 中，首次使用时会在后台线程
 * 自动释放至应用私有目录 `filesDir/tiny_models/` 供 LiteRT-LM 原生引擎加载。
 *
 * 生命周期与并发约束：
 * - 独占单个后台单线程执行器（Single-Thread Executor），所有 `Engine` / `Conversation` 的初始化、
 *   流式推理、取消与关闭操作都在该唯一线程上执行，绝不阻塞主线程；
 * - 严格保证先停止流式输出并关闭 `Conversation`，再关闭 `Engine`，且各自最多关闭一次；
 * - 在 `AUTO` 模式下，若规格支持 GPU 则优先尝试 OpenCL GPU 后端，初始化失败时自动回退至 CPU；
 * - 针对 LFM2.5 在 LiteRT-LM 0.19.1 上的短卷积状态隔离需求（[TinyModelSpec.isolateEnginePerConversation]），
 *   预热后的首次任务直接复用已加载的 Engine，后续独立内部任务会安全重建干净的 Engine。
 */
class TinyModel internal constructor(
    val config: TinyModelConfig,
    private val fileStore: TinyModelFileStore,
    private val runtimeBackend: TinyModelRuntimeBackend = LiteRtTinyModelBackend,
) : AutoCloseable {

    constructor(
        context: Context,
        config: TinyModelConfig = TinyModelConfig(),
    ) : this(
        config = config,
        fileStore = TinyModelFileStore(context.applicationContext),
        runtimeBackend = LiteRtTinyModelBackend,
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "maid-tiny-model").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val ownerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    @Volatile
    private var engine: TinyModelEngineHandle? = null

    @Volatile
    private var activeConversation: TinyModelConversationHandle? = null

    private var engineHasServedConversation: Boolean = false
    private var gpuInitFailedModelPath: String? = null
    private val isGenerating = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val _state = MutableStateFlow<TinyModelState>(TinyModelState.Uninitialized)
    val state: StateFlow<TinyModelState> = _state.asStateFlow()

    val spec: TinyModelSpec
        get() = config.spec

    /** 释放后的模型本地目标绝对路径（位于 `filesDir/tiny_models/`）。 */
    val extractedModelFilePath: String
        get() = fileStore.targetFileFor(spec).absolutePath

    /** 检查模型是否可用（已内置于 `assets/` 或已释放到本地文件系统）。 */
    fun isModelAvailable(modelPathOverride: String? = null): Boolean {
        return fileStore.isModelAvailable(
            spec = spec,
            customModelPath = modelPathOverride ?: config.customModelPath,
        )
    }

    /** 返回已释放到本地文件系统的 `.litertlm` 文件；尚未释放时返回 `null`。 */
    fun resolveExtractedModelFile(modelPathOverride: String? = null): File? {
        return fileStore.resolveExtractedModelFile(
            spec = spec,
            customModelPath = modelPathOverride ?: config.customModelPath,
        )
    }

    /**
     * 在后台按需将 `assets/` 中的 `.litertlm` 释放到 `filesDir/tiny_models/`（已释放且未变化时直接复用）。
     */
    suspend fun prepareModelFile(
        modelPathOverride: String? = null,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<File> {
        if (closed.get()) {
            return Result.failure(IllegalStateException("TinyModel is already closed"))
        }
        return withContext(dispatcher) {
            mutex.withLock {
                if (closed.get()) {
                    return@withLock Result.failure(IllegalStateException("TinyModel is already closed"))
                }
                runCatching {
                    prepareModelFileLocked(modelPathOverride, onProgress)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    if (_state.value !is TinyModelState.ModelMissing) {
                        _state.value = TinyModelState.Error(error.message ?: "TinyModel asset preparation failed")
                    }
                }
            }
        }
    }

    /**
     * 在后台单线程执行器上预加载模型引擎（若模型尚在 `assets/` 中未释放，会先自动释放），减少首次内部调用延迟。
     * 若相同路径的干净引擎已经加载，则直接返回成功。
     */
    suspend fun preload(modelPathOverride: String? = null): Result<Unit> {
        if (closed.get()) {
            return Result.failure(IllegalStateException("TinyModel is already closed"))
        }
        return withContext(dispatcher) {
            mutex.withLock {
                if (closed.get()) {
                    return@withLock Result.failure(IllegalStateException("TinyModel is already closed"))
                }
                runCatching {
                    ensureCleanEngineLocked(modelPathOverride)
                    Unit
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    if (_state.value !is TinyModelState.ModelMissing) {
                        _state.value = TinyModelState.Error(error.message ?: "TinyModel preload failed")
                    }
                }
            }
        }
    }

    /**
     * 执行一次独立的内部文本生成任务并返回完整结果。
     */
    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
        maxOutputTokens: Int? = null,
        sampler: TinyModelSamplerConfig? = null,
        modelPathOverride: String? = null,
    ): TinyModelResult {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        check(!closed.get()) { "TinyModel is already closed" }

        val startedAt = System.currentTimeMillis()
        val builder = StringBuilder()
        var backendUsed: TinyModelActiveBackend? = null

        withContext(dispatcher) {
            mutex.withLock {
                check(!closed.get()) { "TinyModel is already closed" }
                val activeEngine = ensureCleanEngineLocked(modelPathOverride)
                backendUsed = activeEngine.activeBackend
                runSingleConversationLocked(
                    activeEngine = activeEngine,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    maxOutputTokens = maxOutputTokens,
                    sampler = sampler,
                    onChunk = { chunk -> builder.append(chunk) },
                )
            }
        }

        return TinyModelResult(
            text = builder.toString(),
            activeBackend = backendUsed ?: TinyModelActiveBackend.CPU,
            modelId = spec.id,
            durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
        )
    }

    /**
     * 以流式方式执行一次独立的内部文本生成任务。
     * 下游取消收集时会自动触发 [cancelCurrent]，安全中断底层推理。
     */
    fun generateStream(
        prompt: String,
        systemPrompt: String? = null,
        maxOutputTokens: Int? = null,
        sampler: TinyModelSamplerConfig? = null,
        modelPathOverride: String? = null,
    ): Flow<String> = channelFlow {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        check(!closed.get()) { "TinyModel is already closed" }

        try {
            withContext(dispatcher) {
                mutex.withLock {
                    check(!closed.get()) { "TinyModel is already closed" }
                    val activeEngine = ensureCleanEngineLocked(modelPathOverride)
                    runSingleConversationLocked(
                        activeEngine = activeEngine,
                        prompt = prompt,
                        systemPrompt = systemPrompt,
                        maxOutputTokens = maxOutputTokens,
                        sampler = sampler,
                        onChunk = { chunk -> send(chunk) },
                    )
                }
            }
        } catch (e: CancellationException) {
            cancelCurrent()
            throw e
        }
    }

    /**
     * 请求中断当前正在进行的推理。
     * 该调用会在独占的单线程执行器上触发 `Conversation.cancelProcess()`。
     */
    fun cancelCurrent() {
        if (closed.get() && activeConversation == null) return
        ownerScope.launch {
            activeConversation?.cancelProcess()
        }
    }

    /**
     * 释放当前已加载的引擎与会话以回收内存，但保持 [TinyModel] 实例可被后续调用重新加载。
     */
    suspend fun unload() {
        if (closed.get()) return
        cancelCurrent()
        withContext(dispatcher) {
            mutex.withLock {
                if (closed.get()) return@withLock
                releaseLocked()
                _state.value = TinyModelState.Uninitialized
            }
        }
    }

    /**
     * 创建适配 Koog [PromptExecutor] 的内部桥梁实例（仅限内部事务使用）。
     */
    fun asPromptExecutor(): PromptExecutor = TinyModelPromptExecutor(this)

    /**
     * 挂起式永久关闭：先停止推理，再依次关闭会话与引擎，最后关闭后台线程与协程作用域。
     */
    suspend fun closeAndAwait() {
        if (!closed.compareAndSet(false, true)) return
        ownerScope.launch {
            activeConversation?.cancelProcess()
        }
        val shutdownJob = ownerScope.launch {
            mutex.withLock {
                releaseLocked()
                _state.value = TinyModelState.Closed
            }
            executor.shutdown()
        }
        shutdownJob.invokeOnCompletion {
            ownerScope.cancel()
        }
        shutdownJob.join()
    }

    /**
     * 非阻塞永久关闭，适用于 `Application.onTerminate` / `AppRuntime.release` 等同步清理回调。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownerScope.launch {
            activeConversation?.cancelProcess()
        }
        ownerScope.launch {
            mutex.withLock {
                releaseLocked()
                _state.value = TinyModelState.Closed
            }
            executor.shutdown()
        }.invokeOnCompletion {
            ownerScope.cancel()
        }
    }

    private suspend fun prepareModelFileLocked(
        modelPathOverride: String?,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
    ): File {
        return try {
            fileStore.ensureModelFileReady(
                spec = spec,
                customModelPath = modelPathOverride ?: config.customModelPath,
                onProgress = { copied, total ->
                    _state.value = TinyModelState.Preparing(copied, total)
                    onProgress?.invoke(copied, total)
                },
            )
        } catch (e: FileNotFoundException) {
            _state.value = TinyModelState.ModelMissing(
                spec = spec,
                expectedAssetPaths = spec.candidateAssetPaths,
            )
            throw e
        }
    }

    private suspend fun ensureCleanEngineLocked(modelPathOverride: String?): TinyModelEngineHandle {
        val modelFile = prepareModelFileLocked(modelPathOverride)
        val resolvedPath = modelFile.absolutePath
        val currentEngine = engine
        val needsFreshEngine = currentEngine == null ||
            currentEngine.modelPath != resolvedPath ||
            (spec.isolateEnginePerConversation && engineHasServedConversation)

        if (!needsFreshEngine) {
            _state.value = TinyModelState.Ready(
                modelPath = currentEngine.modelPath,
                activeBackend = currentEngine.activeBackend,
            )
            return currentEngine
        }

        releaseLocked()

        val initialized = initializeEngineWithFallbackLocked(resolvedPath)
        engine = initialized
        engineHasServedConversation = false
        _state.value = TinyModelState.Ready(
            modelPath = initialized.modelPath,
            activeBackend = initialized.activeBackend,
        )
        return initialized
    }

    private fun initializeEngineWithFallbackLocked(modelPath: String): TinyModelEngineHandle {
        val cacheDirPath = runCatching { fileStore.ensureCacheDir().absolutePath }.getOrNull()
        val backendAttempts: List<TinyModelActiveBackend> = when (config.backendPreference) {
            TinyModelBackendPreference.AUTO -> {
                if (spec.supportsGpu && gpuInitFailedModelPath != modelPath) {
                    listOf(TinyModelActiveBackend.GPU, TinyModelActiveBackend.CPU)
                } else {
                    listOf(TinyModelActiveBackend.CPU)
                }
            }
            TinyModelBackendPreference.GPU -> listOf(TinyModelActiveBackend.GPU)
            TinyModelBackendPreference.CPU -> listOf(TinyModelActiveBackend.CPU)
        }

        var lastError: Throwable? = null
        for (attempt in backendAttempts) {
            _state.value = TinyModelState.Loading(modelPath = modelPath, backend = attempt)
            try {
                return runtimeBackend.createAndInitializeEngine(
                    TinyModelEngineInitParams(
                        modelPath = modelPath,
                        backend = attempt,
                        maxNumTokens = config.effectiveMaxNumTokens,
                        cpuThreadCount = config.cpuThreadCount,
                        cacheDir = cacheDirPath,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (attempt == TinyModelActiveBackend.GPU && config.backendPreference == TinyModelBackendPreference.AUTO) {
                    gpuInitFailedModelPath = modelPath
                }
                lastError = t
            }
        }
        throw IllegalStateException(
            "Failed to initialize TinyModel (${spec.id}) on backends $backendAttempts: ${lastError?.message}",
            lastError,
        )
    }

    private suspend fun runSingleConversationLocked(
        activeEngine: TinyModelEngineHandle,
        prompt: String,
        systemPrompt: String?,
        maxOutputTokens: Int?,
        sampler: TinyModelSamplerConfig?,
        onChunk: suspend (String) -> Unit,
    ) {
        val effectiveSystemInstruction = systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: spec.defaultSystemInstruction
        val effectiveSampler = sampler ?: config.defaultSampler
        val conversation = activeEngine.createConversation(
            TinyModelConversationParams(
                systemInstruction = effectiveSystemInstruction,
                sampler = effectiveSampler,
                maxOutputTokens = maxOutputTokens,
            ),
        )
        activeConversation = conversation
        engineHasServedConversation = true
        isGenerating.set(true)
        _state.value = TinyModelState.Busy(
            modelPath = activeEngine.modelPath,
            activeBackend = activeEngine.activeBackend,
        )

        var failed = false
        try {
            conversation.sendMessageAsync(
                text = prompt,
                maxOutputTokens = maxOutputTokens,
            ).collect { chunk ->
                if (chunk.isNotEmpty()) {
                    onChunk(chunk)
                }
            }
        } catch (e: CancellationException) {
            conversation.cancelProcess()
            throw e
        } catch (t: Throwable) {
            failed = true
            _state.value = TinyModelState.Error(t.message ?: "TinyModel inference failed")
            throw t
        } finally {
            isGenerating.set(false)
            activeConversation = null
            runCatching { conversation.close() }
            if (!failed && !closed.get() && engine != null) {
                _state.value = TinyModelState.Ready(
                    modelPath = activeEngine.modelPath,
                    activeBackend = activeEngine.activeBackend,
                )
            }
        }
    }

    private suspend fun releaseLocked() {
        while (isGenerating.get()) {
            activeConversation?.cancelProcess()
            delay(CANCEL_POLL_INTERVAL_MS)
        }
        val conv = activeConversation
        activeConversation = null
        if (conv != null) {
            runCatching { conv.close() }
        }

        val eng = engine
        engine = null
        engineHasServedConversation = false
        if (eng != null) {
            runCatching { eng.close() }
        }
    }

    companion object {
        private const val CANCEL_POLL_INTERVAL_MS = 50L
    }
}
