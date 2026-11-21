package top.aaa000111.maid.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import ai.koog.agents.memory.providers.AgentMemoryProvider
import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.aaa000111.maid.learning.LearningRuntime
import top.aaa000111.maid.agent.ChatModelBinding
import top.aaa000111.maid.memory.MaidMemoryManager
import top.aaa000111.maid.security.SecureKeyStorage
import top.aaa000111.maid.provider.ChatGptAuthRepository
import top.aaa000111.maid.provider.LlmKeyRepository
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.LlmProviderRegistry
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.ProviderAvailability
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.settings.AgentStepConfigRepository
import top.aaa000111.maid.settings.ModelSafetyRepository
import top.aaa000111.maid.settings.PersonaRepository
import top.aaa000111.maid.shizuku.ShizukuManager
import top.aaa000111.maid.shizuku.ShizukuStatus
import top.aaa000111.maid.agent.DeviceActionAgent
import top.aaa000111.maid.agent.ProotLinuxAgentLifecycleManager
import top.aaa000111.maid.agent.VisionToolSet
import top.aaa000111.maid.androidtool.PrivilegeManager
import top.aaa000111.maid.chat.ChatBackgroundRuntime
import top.aaa000111.maid.chat.ChatSessionRepository
import top.aaa000111.maid.proot.ProotVtfxTerminalManager
import top.aaa000111.maid.proot.TermuxProotRuntime
import top.aaa000111.maid.search.KtorSearchHttpClient
import top.aaa000111.maid.search.ProotSearXngManager
import top.aaa000111.maid.search.SearchConfigRepository
import top.aaa000111.maid.search.SearchEngineRouter
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.WebPageFetcher
import top.aaa000111.maid.tinymodel.TinyModel
import top.aaa000111.maid.tinymodel.TinyModelConfig
import top.aaa000111.maid.tinymodel.TinyModelConfigRepository
import top.aaa000111.maid.tinymodel.TinyModelFileStore
import top.aaa000111.maid.tinymodel.TinyModelLocalBridge
import top.aaa000111.maid.tinymodel.TinyModelSelection
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 附带服务商与模型标识的运行态执行器缓存条目，便于按服务商精确失效并校验角色一致性。
 */
internal data class CachedRoleExecutor(
    val provider: LlmProvider,
    val customProviderId: String?,
    val modelId: String,
    val executor: PromptExecutor,
)

/**
 * 应用启动时的静态环境与构建信息。
 */
data class AppBuildInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val isDebuggable: Boolean,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val androidSdkInt: Int,
    val appStartedAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromContext(
            context: Context,
            appStartedAtMillis: Long = System.currentTimeMillis(),
        ): AppBuildInfo {
            val appContext = context.applicationContext
            val packageManager = appContext.packageManager
            val packageName = appContext.packageName

            val packageInfo = runCatching {
                packageManager.getPackageInfo(packageName, 0)
            }.getOrNull()

            val applicationInfo = runCatching {
                packageManager.getApplicationInfo(packageName, 0)
            }.getOrNull()

            val versionName = packageInfo?.versionName.orEmpty().ifEmpty { "unknown" }
            val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
            val isDebuggable = applicationInfo != null &&
                (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

            return AppBuildInfo(
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                isDebuggable = isDebuggable,
                deviceManufacturer = Build.MANUFACTURER.orEmpty(),
                deviceModel = Build.MODEL.orEmpty(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                androidSdkInt = Build.VERSION.SDK_INT,
                appStartedAtMillis = appStartedAtMillis,
            )
        }
    }
}

/**
 * 应用运行时全局单例：
 * 统一初始化并持有应用级单例组件、构建信息以及四个 Koog 提示词执行器变量。
 */
object AppRuntime {
    val chatAgentManager get() = top.aaa000111.maid.agent.ChatAgentManager.default
    val plannerAgentManager get() = top.aaa000111.maid.agent.PlannerAgentManager.default
    val deviceActionAgentManager get() = top.aaa000111.maid.agent.DeviceActionAgentManager.default
    val researchAgentManager get() = top.aaa000111.maid.agent.ResearchAgentManager.default
    val prootLinuxAgentManager get() = top.aaa000111.maid.agent.ProotLinuxAgentManager.default


    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var currentBuildInfo: AppBuildInfo = AppBuildInfo(
        packageName = "",
        versionName = "unknown",
        versionCode = 0L,
        isDebuggable = false,
        deviceManufacturer = "",
        deviceModel = "",
        androidRelease = "",
        androidSdkInt = 0,
    )

    val buildInfo: AppBuildInfo
        get() = currentBuildInfo

    @Volatile
    private var customShizukuStatus: StateFlow<ShizukuStatus>? = null

    val shizukuStatus: StateFlow<ShizukuStatus>
        get() = customShizukuStatus ?: ShizukuManager.status

    @Volatile
    private var customStoredKeysFlow: Flow<Map<LlmProvider, String?>>? = null

    val storedKeysFlow: Flow<Map<LlmProvider, String?>>
        get() = customStoredKeysFlow ?: LlmKeyRepository.observeAllApiKeys()

    private val _preferredProvider = MutableStateFlow(LlmProvider.OPENAI)
    val preferredProvider: StateFlow<LlmProvider> = _preferredProvider.asStateFlow()

    val memoryProvider: AgentMemoryProvider?
        get() = MaidMemoryManager.memoryProviderOrNull

    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var providerAvailabilityObserverJob: Job? = null

    private val cachedRoleExecutors = ConcurrentHashMap<ModelRole, CachedRoleExecutor>()
    private val directRoleExecutors = ConcurrentHashMap<ModelRole, PromptExecutor>()

    /** 快速廉价应答模型执行器 */
    var fastCheapExecutor: PromptExecutor?
        get() = getRoleExecutor(ModelRole.FAST_CHEAP)
        set(value) = setDirectRoleExecutor(ModelRole.FAST_CHEAP, value)

    /** 复杂规划模型执行器 */
    var complexPlanningExecutor: PromptExecutor?
        get() = getRoleExecutor(ModelRole.COMPLEX_PLANNING)
        set(value) = setDirectRoleExecutor(ModelRole.COMPLEX_PLANNING, value)

    /** 快速视觉模型执行器 */
    var fastVisionExecutor: PromptExecutor?
        get() = getRoleExecutor(ModelRole.FAST_VISION)
        set(value) = setDirectRoleExecutor(ModelRole.FAST_VISION, value)

    /** 复杂视觉模型执行器 */
    var complexVisionExecutor: PromptExecutor?
        get() = getRoleExecutor(ModelRole.COMPLEX_VISION)
        set(value) = setDirectRoleExecutor(ModelRole.COMPLEX_VISION, value)

    private fun getRoleExecutor(role: ModelRole): PromptExecutor? {
        return cachedRoleExecutors[role]?.executor ?: directRoleExecutors[role]
    }

    private fun setDirectRoleExecutor(role: ModelRole, executor: PromptExecutor?) {
        cachedRoleExecutors.remove(role)
        if (executor == null) {
            directRoleExecutors.remove(role)
        } else {
            directRoleExecutors[role] = executor
        }
    }

    internal fun cacheRoleExecutor(role: ModelRole, entry: CachedRoleExecutor) {
        directRoleExecutors.remove(role)
        cachedRoleExecutors[role] = entry
    }

    internal fun getCachedRoleExecutor(role: ModelRole): CachedRoleExecutor? =
        cachedRoleExecutors[role]

    fun invalidateExecutorForRole(role: ModelRole) {
        cachedRoleExecutors.remove(role)
        directRoleExecutors.remove(role)
    }

    /**
     * 当某个服务商状态变为不可用（例如 ChatGPT 会话过期、API Key 被清空）时，立即淘汰绑定到该服务商的所有缓存执行器。
     */
    fun invalidateExecutorsForProvider(
        provider: LlmProvider,
        customProviderId: String? = null,
    ) {
        val iterator = cachedRoleExecutors.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val providerMatched = entry.provider == provider
            val customMatched = customProviderId == null || entry.customProviderId == customProviderId
            if (providerMatched && customMatched) {
                iterator.remove()
            }
        }
    }

    fun clearAllCachedExecutors() {
        cachedRoleExecutors.clear()
        directRoleExecutors.clear()
    }

    @Volatile
    private var appContext: Context? = null

    private val _activeTinyModel = MutableStateFlow<TinyModel?>(null)
    val activeTinyModelFlow: StateFlow<TinyModel?> = _activeTinyModel.asStateFlow()

    /** 应用内部事务专用的端侧轻量模型（基于 LiteRT-LM） */
    var tinyModel: TinyModel?
        get() = _activeTinyModel.value
        internal set(value) {
            _activeTinyModel.value = value
        }

    @Volatile
    private var sharedTinyModelLocalBridge: TinyModelLocalBridge? = null

    /**
     * 获取或懒启动面向 PRoot Linux 的端侧 [TinyModel] 本地 HTTP 桥接服务（仅监听 `127.0.0.1`）。
     */
    fun getOrCreateTinyModelLocalBridge(): TinyModelLocalBridge {
        sharedTinyModelLocalBridge?.takeIf { it.isRunning }?.let { return it }
        return synchronized(this) {
            sharedTinyModelLocalBridge?.takeIf { it.isRunning }
                ?: TinyModelLocalBridge(tinyModelProvider = { tinyModel })
                    .ensureStarted()
                    .also { sharedTinyModelLocalBridge = it }
        }
    }

    /**
     * 生成无条件注入 PRoot Linux 默认环境的端侧小模型桥接环境变量；若桥接启动失败则安全返回空映射。
     */
    fun getTinyModelLocalBridgeEnv(): Map<String, String> {
        return runCatching {
            getOrCreateTinyModelLocalBridge().buildEnvironmentVariables()
        }.getOrDefault(emptyMap())
    }

    @Volatile
    private var sharedSearchHttpClient: SearchHttpClient? = null

    @Volatile
    private var sharedSearchEngineRouter: SearchEngineRouter? = null

    @Volatile
    private var sharedWebPageFetcher: WebPageFetcher? = null

    /** 获取或懒创建应用共享的 Ktor 搜索与网页抓取 HTTP 客户端。 */
    fun getOrCreateSearchHttpClient(): SearchHttpClient {
        sharedSearchHttpClient?.let { return it }
        return synchronized(this) {
            sharedSearchHttpClient ?: KtorSearchHttpClient.createDefault().also {
                sharedSearchHttpClient = it
            }
        }
    }

    /** 获取或懒创建应用共享的多引擎搜索路由器（默认含 DuckDuckGo / Brave / Bing / CN Bing / Wikipedia）。 */
    fun getOrCreateSearchEngineRouter(): SearchEngineRouter {
        sharedSearchEngineRouter?.let { return it }
        return synchronized(this) {
            sharedSearchEngineRouter ?: SearchEngineRouter.createDefault(
                httpClient = getOrCreateSearchHttpClient(),
                ownsHttpClient = false,
            ).also {
                sharedSearchEngineRouter = it
            }
        }
    }

    /** 获取或懒创建应用共享的网页正文抓取器。 */
    fun getOrCreateWebPageFetcher(): WebPageFetcher {
        sharedWebPageFetcher?.let { return it }
        return synchronized(this) {
            sharedWebPageFetcher ?: WebPageFetcher(
                httpClient = getOrCreateSearchHttpClient(),
            ).also {
                sharedWebPageFetcher = it
            }
        }
    }

    /** 刷新已持久化的搜索配置缓存，并清空所有搜索引擎的可用性探测缓存。 */
    suspend fun syncSearchConfig() {
        if (!initialized) return
        if (SearchConfigRepository.isReady) {
            SearchConfigRepository.getConfig()
        }
        invalidateSearchAvailabilityCache()
    }

    /** 清空共享搜索路由器的全部可用性缓存（例如修改 SearXNG 实例地址或切换网络代理后）。 */
    fun invalidateSearchAvailabilityCache() {
        sharedSearchEngineRouter?.invalidateAllAvailabilityCaches()
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            appContext = appCtx
            SecureKeyStorage.init(appCtx)
            ChatGptAuthRepository.init(appCtx)
            ModelConfigRepository.init(appCtx)
            TinyModelConfigRepository.init(appCtx)
            SearchConfigRepository.init(appCtx)
            ModelSafetyRepository.init(appCtx)
            AgentStepConfigRepository.init(appCtx)
            PersonaRepository.init(appCtx)
            ShizukuManager.init(appCtx)
            PrivilegeManager.init(appCtx)
            MaidMemoryManager.init(appCtx)
            VisionToolSet.init(appCtx)
            DeviceActionAgent.init(appCtx)
            ChatSessionRepository.init(appCtx)
            LearningRuntime.init(appCtx)
            ChatBackgroundRuntime.init(appCtx)
            TermuxProotRuntime.init(appCtx)
            ProotSearXngManager.init(appCtx)
            ProotLinuxAgentLifecycleManager.init(appCtx)

            val discoveredOptions = runCatching {
                TinyModelFileStore(appCtx).discoverAvailableModels()
            }.getOrDefault(emptyList())
            val initialConfig = TinyModelConfigRepository.resolveEffectiveConfig(
                selection = TinyModelSelection(),
                availableOptions = discoveredOptions,
            )
            tinyModel?.close()
            tinyModel = TinyModel(appCtx, initialConfig)

            currentBuildInfo = AppBuildInfo.fromContext(appCtx)
            customShizukuStatus = null
            customStoredKeysFlow = null
            startProviderAvailabilityObserverLocked()
            initialized = true
        }
    }

    private fun startProviderAvailabilityObserverLocked() {
        providerAvailabilityObserverJob?.cancel()
        providerAvailabilityObserverJob = runtimeScope.launch {
            LlmProviderRegistry.observeAllAvailability().collect { statuses ->
                statuses.forEach { (provider, availability) ->
                    if (!availability.isReady) {
                        invalidateExecutorsForProvider(provider)
                    }
                }
            }
        }
    }

    internal fun initForTest(
        buildInfo: AppBuildInfo,
        shizukuStatus: StateFlow<ShizukuStatus> = MutableStateFlow(ShizukuStatus()),
        storedKeysFlow: Flow<Map<LlmProvider, String?>> = MutableStateFlow(emptyMap()),
        memoryRootPath: Path? = null,
        tinyModel: TinyModel? = null,
    ) {
        synchronized(this) {
            appContext = null
            currentBuildInfo = buildInfo
            customShizukuStatus = shizukuStatus
            customStoredKeysFlow = storedKeysFlow
            _preferredProvider.value = LlmProvider.OPENAI
            providerAvailabilityObserverJob?.cancel()
            providerAvailabilityObserverJob = null
            clearAllCachedExecutors()
            this.tinyModel?.close()
            this.tinyModel = tinyModel
            if (memoryRootPath != null) {
                MaidMemoryManager.init(rootPath = memoryRootPath)
            } else {
                MaidMemoryManager.resetForTest()
            }
            VisionToolSet.resetForTest()
            DeviceActionAgent.resetForTest()
            ProotLinuxAgentLifecycleManager.resetForTest()
            ChatSessionRepository.resetForTest()
                ChatBackgroundRuntime.resetForTest()
            LearningRuntime.resetForTest()
            initialized = true
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            appContext = null
            providerAvailabilityObserverJob?.cancel()
            providerAvailabilityObserverJob = null
            clearAllCachedExecutors()
            tinyModel?.close()
            tinyModel = null
            runCatching { sharedTinyModelLocalBridge?.close() }
            sharedTinyModelLocalBridge = null
            ProotSearXngManager.stopImmediate()
            releaseSearchRuntimeLocked()
            SearchConfigRepository.resetForTest()
            _preferredProvider.value = LlmProvider.OPENAI
            customShizukuStatus = null
            customStoredKeysFlow = null
            MaidMemoryManager.resetForTest()
            VisionToolSet.resetForTest()
            DeviceActionAgent.resetForTest()
            ProotLinuxAgentLifecycleManager.resetForTest()
            ChatSessionRepository.resetForTest()
                ChatBackgroundRuntime.resetForTest()
            LearningRuntime.resetForTest()
            ChatGptAuthRepository.resetForTest()
            LlmProviderRegistry.resetForTest()
            initialized = false
        }
    }

    fun setPreferredProvider(provider: LlmProvider) {
        _preferredProvider.value = provider
    }

    /**
     * 根据本地保存的四种模型配置与 [LlmProviderRegistry] 中对应服务商的适配器，刷新四个 Koog `PromptExecutor`。
     */
    suspend fun syncModelExecutors() {
        if (!initialized) return
        val configs = ModelConfigRepository.getAllConfigs()

        ModelRole.entries.forEach { role ->
            val selection = configs[role]?.takeIf { it.isComplete }
            val provider = selection?.provider
            val modelId = selection?.normalizedModelId
            val customProviderId = selection?.normalizedCustomProviderId
            if (provider == null || modelId == null) {
                invalidateExecutorForRole(role)
                return@forEach
            }
            val executor = LlmProviderRegistry.createExecutor(
                provider = provider,
                customProviderId = customProviderId,
            )
            if (executor != null) {
                cacheRoleExecutor(
                    role = role,
                    entry = CachedRoleExecutor(
                        provider = provider,
                        customProviderId = customProviderId,
                        modelId = modelId,
                        executor = executor,
                    ),
                )
            } else {
                invalidateExecutorForRole(role)
            }
        }
    }

    /**
     * 统一的角色模型绑定解析入口（供 `ChatAgent` 及子 Agent 共享）：
     * - 按 [preferredRoles] 顺序查找已完整配置且当前就绪的角色；
     * - 命中缓存前执行轻量就绪检查，若该服务商已失效（如 OAuth 会话过期或密钥被移除），立即淘汰缓存并平滑回退到下一个候选角色。
     */
    suspend fun resolveModelBinding(
        preferredRoles: List<ModelRole>,
        configsLoader: suspend () -> Map<ModelRole, RoleModelSelection> = {
            ModelConfigRepository.getAllConfigs()
        },
        availabilityChecker: suspend (LlmProvider, String?) -> ProviderAvailability = { provider, customId ->
            LlmProviderRegistry.checkAvailability(provider, customId)
        },
        executorCreator: suspend (LlmProvider, String?) -> PromptExecutor? = { provider, customId ->
            LlmProviderRegistry.createExecutor(provider, customId)
        },
    ): ChatModelBinding {
        val configs = configsLoader()
        val candidateRoles = preferredRoles.ifEmpty { listOf(ModelRole.FAST_CHEAP) }
        var firstUnavailableReason: String? = null

        for (role in candidateRoles) {
            val selection = configs[role]?.takeIf { it.isComplete } ?: continue
            val provider = selection.provider ?: continue
            val modelId = selection.normalizedModelId ?: continue
            val customProviderId = selection.normalizedCustomProviderId

            val availability = availabilityChecker(provider, customProviderId)
            if (!availability.isReady) {
                invalidateExecutorForRole(role)
                if (firstUnavailableReason == null) {
                    firstUnavailableReason = (availability as? ProviderAvailability.Unavailable)?.reason
                        ?: "Provider ${provider.id} is unavailable"
                }
                continue
            }

            val cachedEntry = getCachedRoleExecutor(role)
            val reusableExecutor = when {
                cachedEntry != null &&
                    cachedEntry.provider == provider &&
                    cachedEntry.customProviderId == customProviderId &&
                    cachedEntry.modelId == modelId -> cachedEntry.executor
                cachedEntry == null && directRoleExecutors[role] != null -> directRoleExecutors[role]
                else -> null
            }

            val executor = reusableExecutor ?: executorCreator(provider, customProviderId)
            if (executor == null) {
                invalidateExecutorForRole(role)
                if (firstUnavailableReason == null) {
                    firstUnavailableReason = "Failed to initialize executor for provider: ${provider.id}"
                }
                continue
            }

            if (reusableExecutor == null) {
                cacheRoleExecutor(
                    role = role,
                    entry = CachedRoleExecutor(
                        provider = provider,
                        customProviderId = customProviderId,
                        modelId = modelId,
                        executor = executor,
                    ),
                )
            }

            return ChatModelBinding(
                executor = executor,
                model = LlmProviderRegistry.resolveKoogModel(
                    provider = provider,
                    modelId = modelId,
                    metadata = selection.modelMetadata,
                ),
                provider = provider,
            )
        }

        if (firstUnavailableReason != null) {
            error(firstUnavailableReason)
        }
        error("No chat model configured for roles: ${candidateRoles.joinToString { it.name }}")
    }

    /**
     * 根据已持久化的 [TinyModelConfigRepository] 配置与当前可用模型列表，刷新运行中的 [tinyModel]。
     */
    suspend fun syncTinyModel(forceReload: Boolean = false) {
        if (!initialized) return
        val ctx = appContext ?: return
        if (!TinyModelConfigRepository.isReady) return
        val selection = TinyModelConfigRepository.getSelection()
        val discovered = runCatching {
            TinyModelFileStore(ctx).discoverAvailableModels()
        }.getOrDefault(emptyList())
        val resolvedConfig = TinyModelConfigRepository.resolveEffectiveConfig(
            selection = selection,
            availableOptions = discovered,
        )
        applyTinyModelConfig(ctx, resolvedConfig, forceReload = forceReload)
    }

    private fun applyTinyModelConfig(
        context: Context,
        config: TinyModelConfig,
        forceReload: Boolean,
    ) {
        val oldToClose = synchronized(this) {
            val current = tinyModel
            if (!forceReload && current != null && current.config == config) {
                return
            }
            tinyModel = TinyModel(context, config)
            current
        }
        oldToClose?.close()
    }

    private fun releaseSearchRuntimeLocked() {
        sharedSearchEngineRouter = null
        sharedWebPageFetcher = null
        sharedSearchHttpClient?.close()
        sharedSearchHttpClient = null
    }

    fun release() {
        providerAvailabilityObserverJob?.cancel()
        providerAvailabilityObserverJob = null
        clearAllCachedExecutors()
        tinyModel?.close()
        tinyModel = null
        ProotSearXngManager.stopImmediate()
        synchronized(this) {
            releaseSearchRuntimeLocked()
        }
        ProotLinuxAgentLifecycleManager.default.disconnectDedicatedTerminal()
        ProotVtfxTerminalManager.release()
        TermuxProotRuntime.disconnectPersistentConnection()
        ShizukuManager.release()
    }
}
