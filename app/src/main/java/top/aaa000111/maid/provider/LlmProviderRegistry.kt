package top.aaa000111.maid.provider

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 单个 LLM 服务商在 PRoot Linux 环境中对应的标准环境变量规范。
 */
data class LlmProviderEnvSpec(
    val apiKeyEnvVar: String,
    val aliasApiKeyEnvVars: List<String> = emptyList(),
    val baseUrlEnvVar: String,
    val defaultBaseUrl: String?,
)

/**
 * 单个 LLM 服务商的实时可用性（就绪态）描述。
 */
sealed interface ProviderAvailability {
    data object Ready : ProviderAvailability

    data class Unavailable(
        val reason: String,
        val actionHint: String? = null,
    ) : ProviderAvailability

    val isReady: Boolean
        get() = this is Ready
}

/**
 * 插件化的 LLM 服务商适配器接口。
 *
 * 将不同鉴权机制（静态 API Key、ChatGPT OAuth 2.0、未来本地服务 / 设备码登录等）的
 * 就绪态检查、模型列表拉取、Koog `LLModel` 构造、`PromptExecutor` 构建与 PRoot 环境变量映射
 * 封装到各自适配器中，消除上层代码（`AppRuntime`、`ChatAgent`、`ChatBackgroundRuntime`、
 * `ChatViewModel`、`SettingsViewModel`）中散落的 `when (provider.authType)` 分支。
 */
interface LlmProviderAdapter {
    val provider: LlmProvider

    fun observeAvailability(customProviderId: String? = null): Flow<ProviderAvailability>

    suspend fun checkAvailability(customProviderId: String? = null): ProviderAvailability

    suspend fun fetchModels(customProviderId: String? = null): Result<List<LlmModelOption>>

    fun resolveKoogModel(
        modelId: String,
        metadata: LlmModelMetadata? = null,
    ): LLModel = LlmModelCatalog.resolveKoogModel(provider, modelId, metadata)

    suspend fun createExecutor(customProviderId: String? = null): PromptExecutor?

    fun prootEnvSpec(): LlmProviderEnvSpec? = null
}

/**
 * 覆盖全部静态 API Key 类服务商（含多自定义端点档案）的通用适配器实现。
 */
class ApiKeyProviderAdapter(
    override val provider: LlmProvider,
    private val envSpec: LlmProviderEnvSpec?,
    private val apiKeyFlowProvider: (customProviderId: String?) -> Flow<String?> = { customId ->
        if (provider == LlmProvider.CUSTOM && !customId.isNullOrBlank()) {
            LlmKeyRepository.observeCustomProfileApiKey(customId)
        } else {
            LlmKeyRepository.observeApiKey(provider)
        }
    },
    private val apiKeyLoader: suspend (customProviderId: String?) -> String? = { customId ->
        if (provider == LlmProvider.CUSTOM) {
            LlmKeyRepository.getCustomProfileApiKey(customId)
        } else {
            LlmKeyRepository.getApiKey(provider)
        }
    },
    private val customBaseUrlLoader: suspend (customProviderId: String?) -> String? = { customId ->
        if (provider == LlmProvider.CUSTOM && ModelConfigRepository.isReady) {
            ModelConfigRepository.resolveCustomBaseUrl(customId)
        } else {
            null
        }
    },
    private val modelFetcher: suspend (apiKey: String?, customBaseUrl: String?) -> Result<List<LlmModelOption>> = { key, baseUrl ->
        LlmProviderModelFetcher.fetchApiKeyModels(
            provider = provider,
            apiKey = key,
            customBaseUrl = baseUrl,
        )
    },
    private val executorFactory: (apiKey: String, customBaseUrl: String?) -> PromptExecutor = { key, baseUrl ->
        LlmModelCatalog.createExecutor(
            provider = provider,
            apiKey = key,
            customBaseUrl = baseUrl,
        )
    },
) : LlmProviderAdapter {

    init {
        require(provider.usesApiKey) {
            "ApiKeyProviderAdapter requires an API_KEY provider, got ${provider.id}"
        }
    }

    override fun observeAvailability(customProviderId: String?): Flow<ProviderAvailability> {
        return apiKeyFlowProvider(customProviderId).map { key ->
            if (!key.isNullOrBlank()) {
                ProviderAvailability.Ready
            } else {
                missingKeyUnavailable()
            }
        }
    }

    override suspend fun checkAvailability(customProviderId: String?): ProviderAvailability {
        val key = runCatching { apiKeyLoader(customProviderId) }.getOrNull()
        return if (!key.isNullOrBlank()) {
            ProviderAvailability.Ready
        } else {
            missingKeyUnavailable()
        }
    }

    override suspend fun fetchModels(customProviderId: String?): Result<List<LlmModelOption>> {
        val key = runCatching { apiKeyLoader(customProviderId) }.getOrNull()
        val baseUrl = runCatching { customBaseUrlLoader(customProviderId) }.getOrNull()
        return modelFetcher(key, baseUrl)
    }

    override suspend fun createExecutor(customProviderId: String?): PromptExecutor? {
        val key = runCatching { apiKeyLoader(customProviderId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val baseUrl = runCatching { customBaseUrlLoader(customProviderId) }.getOrNull()
        return runCatching {
            executorFactory(key, baseUrl)
        }.getOrNull()
    }

    override fun prootEnvSpec(): LlmProviderEnvSpec? = envSpec

    private fun missingKeyUnavailable(): ProviderAvailability.Unavailable {
        return ProviderAvailability.Unavailable(
            reason = "服务商（${provider.id}）尚未配置 API Key，请先前往设置页面的「API Key 管理」添加密钥。",
            actionHint = "API Key 管理",
        )
    }
}

/**
 * 面向 OpenAI「Sign in with ChatGPT」（OAuth 2.0 + OIDC + PKCE）的专属服务商适配器。
 */
class ChatGptOAuthProviderAdapter(
    private val authStateFlow: () -> Flow<ChatGptAuthState> = { ChatGptAuthRepository.state },
    private val readinessChecker: suspend () -> Boolean = { ChatGptAuthRepository.isPlanUsageReady() },
    private val modelFetcher: suspend () -> Result<List<LlmModelOption>> = {
        LlmProviderModelFetcher.fetchChatGptModels()
    },
    private val executorFactory: () -> PromptExecutor = {
        LlmModelCatalog.createChatGptExecutor()
    },
) : LlmProviderAdapter {

    override val provider: LlmProvider = LlmProvider.CHATGPT

    override fun observeAvailability(customProviderId: String?): Flow<ProviderAvailability> {
        return authStateFlow().map { state -> mapAuthStateToAvailability(state) }
    }

    override suspend fun checkAvailability(customProviderId: String?): ProviderAvailability {
        return if (runCatching { readinessChecker() }.getOrDefault(false)) {
            ProviderAvailability.Ready
        } else {
            mapAuthStateToAvailability(ChatGptAuthRepository.state.value)
        }
    }

    override suspend fun fetchModels(customProviderId: String?): Result<List<LlmModelOption>> {
        return modelFetcher()
    }

    override suspend fun createExecutor(customProviderId: String?): PromptExecutor? {
        if (!runCatching { readinessChecker() }.getOrDefault(false)) return null
        return runCatching { executorFactory() }.getOrNull()
    }

    override fun prootEnvSpec(): LlmProviderEnvSpec? = null

    private fun mapAuthStateToAvailability(state: ChatGptAuthState): ProviderAvailability {
        return when (state.status) {
            ChatGptAuthStatus.SIGNED_IN_READY -> ProviderAvailability.Ready
            ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE -> ProviderAvailability.Unavailable(
                reason = "ChatGPT 账号已登录，但未授予订阅额度调用权限（${ChatGptOAuthConfig.REQUIRED_PLAN_USAGE_SCOPE}），请前往「Sign in with ChatGPT」重新授权。",
                actionHint = "Sign in with ChatGPT",
            )
            ChatGptAuthStatus.SESSION_EXPIRED -> ProviderAvailability.Unavailable(
                reason = "ChatGPT 登录会话已过期，请前往设置页面的「Sign in with ChatGPT」重新登录。",
                actionHint = "Sign in with ChatGPT",
            )
            ChatGptAuthStatus.SIGNING_IN -> ProviderAvailability.Unavailable(
                reason = "正在等待浏览器完成 Sign in with ChatGPT 登录回调，请完成后重试。",
                actionHint = "Sign in with ChatGPT",
            )
            ChatGptAuthStatus.SIGNED_OUT -> ProviderAvailability.Unavailable(
                reason = "对话模型使用 ChatGPT 服务商，请先前往设置页面的「Sign in with ChatGPT」完成登录并授予订阅额度调用权限。",
                actionHint = "Sign in with ChatGPT",
            )
        }
    }
}

/**
 * 全局 LLM 服务商适配器注册中心。
 */
object LlmProviderRegistry {

    private val defaultAdapters: Map<LlmProvider, LlmProviderAdapter> = buildDefaultAdapters()

    @Volatile
    private var testOverrides: Map<LlmProvider, LlmProviderAdapter> = emptyMap()

    fun adapterFor(provider: LlmProvider): LlmProviderAdapter {
        return testOverrides[provider]
            ?: defaultAdapters[provider]
            ?: error("No LlmProviderAdapter registered for provider: ${provider.id}")
    }

    fun allAdapters(): List<LlmProviderAdapter> {
        return LlmProvider.modelSelectionProviders.map { adapterFor(it) }
    }

    fun observeAvailability(
        provider: LlmProvider,
        customProviderId: String? = null,
    ): Flow<ProviderAvailability> = adapterFor(provider).observeAvailability(customProviderId)

    fun observeAllAvailability(): Flow<Map<LlmProvider, ProviderAvailability>> {
        val providers = LlmProvider.modelSelectionProviders
        if (providers.isEmpty()) return flowOf(emptyMap())
        val flows = providers.map { provider -> observeAvailability(provider) }
        return combine(flows) { statuses ->
            providers.mapIndexed { index, provider ->
                provider to statuses[index]
            }.toMap()
        }
    }

    suspend fun checkAvailability(
        provider: LlmProvider,
        customProviderId: String? = null,
    ): ProviderAvailability = adapterFor(provider).checkAvailability(customProviderId)

    suspend fun fetchModels(
        provider: LlmProvider,
        customProviderId: String? = null,
    ): Result<List<LlmModelOption>> = adapterFor(provider).fetchModels(customProviderId)

    fun resolveKoogModel(
        provider: LlmProvider,
        modelId: String,
        metadata: LlmModelMetadata? = null,
    ): LLModel = adapterFor(provider).resolveKoogModel(modelId, metadata)

    suspend fun createExecutor(
        provider: LlmProvider,
        customProviderId: String? = null,
    ): PromptExecutor? = adapterFor(provider).createExecutor(customProviderId)

    fun prootEnvSpec(provider: LlmProvider): LlmProviderEnvSpec? =
        adapterFor(provider).prootEnvSpec()

    internal fun registerAdapterForTest(adapter: LlmProviderAdapter) {
        synchronized(this) {
            testOverrides = testOverrides + (adapter.provider to adapter)
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            testOverrides = emptyMap()
        }
    }

    private fun buildDefaultAdapters(): Map<LlmProvider, LlmProviderAdapter> {
        val map = LinkedHashMap<LlmProvider, LlmProviderAdapter>()
        LlmProvider.entries.forEach { provider ->
            val adapter: LlmProviderAdapter = when (provider.authType) {
                LlmProviderAuthType.API_KEY -> ApiKeyProviderAdapter(
                    provider = provider,
                    envSpec = defaultProotEnvSpec(provider),
                )
                LlmProviderAuthType.CHATGPT_OAUTH -> ChatGptOAuthProviderAdapter()
            }
            map[provider] = adapter
        }
        return map
    }

    internal fun defaultProotEnvSpec(provider: LlmProvider): LlmProviderEnvSpec? = when (provider) {
        LlmProvider.OPENAI -> LlmProviderEnvSpec(
            apiKeyEnvVar = "OPENAI_API_KEY",
            baseUrlEnvVar = "OPENAI_BASE_URL",
            defaultBaseUrl = "https://api.openai.com/v1",
        )
        LlmProvider.CHATGPT -> null
        LlmProvider.ANTHROPIC -> LlmProviderEnvSpec(
            apiKeyEnvVar = "ANTHROPIC_API_KEY",
            baseUrlEnvVar = "ANTHROPIC_BASE_URL",
            defaultBaseUrl = "https://api.anthropic.com",
        )
        LlmProvider.GEMINI -> LlmProviderEnvSpec(
            apiKeyEnvVar = "GEMINI_API_KEY",
            aliasApiKeyEnvVars = listOf("GOOGLE_API_KEY"),
            baseUrlEnvVar = "GEMINI_BASE_URL",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        )
        LlmProvider.DEEPSEEK -> LlmProviderEnvSpec(
            apiKeyEnvVar = "DEEPSEEK_API_KEY",
            baseUrlEnvVar = "DEEPSEEK_BASE_URL",
            defaultBaseUrl = "https://api.deepseek.com/v1",
        )
        LlmProvider.OPENROUTER -> LlmProviderEnvSpec(
            apiKeyEnvVar = "OPENROUTER_API_KEY",
            baseUrlEnvVar = "OPENROUTER_BASE_URL",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
        )
        LlmProvider.GROQ -> LlmProviderEnvSpec(
            apiKeyEnvVar = "GROQ_API_KEY",
            baseUrlEnvVar = "GROQ_BASE_URL",
            defaultBaseUrl = "https://api.groq.com/openai/v1",
        )
        LlmProvider.XAI -> LlmProviderEnvSpec(
            apiKeyEnvVar = "XAI_API_KEY",
            baseUrlEnvVar = "XAI_BASE_URL",
            defaultBaseUrl = "https://api.x.ai/v1",
        )
        LlmProvider.MISTRAL -> LlmProviderEnvSpec(
            apiKeyEnvVar = "MISTRAL_API_KEY",
            baseUrlEnvVar = "MISTRAL_BASE_URL",
            defaultBaseUrl = "https://api.mistral.ai/v1",
        )
        LlmProvider.SILICONFLOW -> LlmProviderEnvSpec(
            apiKeyEnvVar = "SILICONFLOW_API_KEY",
            baseUrlEnvVar = "SILICONFLOW_BASE_URL",
            defaultBaseUrl = "https://api.siliconflow.cn/v1",
        )
        LlmProvider.DASHSCOPE -> LlmProviderEnvSpec(
            apiKeyEnvVar = "DASHSCOPE_API_KEY",
            baseUrlEnvVar = "DASHSCOPE_BASE_URL",
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        )
        LlmProvider.MOONSHOT -> LlmProviderEnvSpec(
            apiKeyEnvVar = "MOONSHOT_API_KEY",
            baseUrlEnvVar = "MOONSHOT_BASE_URL",
            defaultBaseUrl = "https://api.moonshot.cn/v1",
        )
        LlmProvider.ZHIPU -> LlmProviderEnvSpec(
            apiKeyEnvVar = "ZHIPU_API_KEY",
            aliasApiKeyEnvVars = listOf("ZHIPUAI_API_KEY"),
            baseUrlEnvVar = "ZHIPU_BASE_URL",
            defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        )
        LlmProvider.DOUBAO -> LlmProviderEnvSpec(
            apiKeyEnvVar = "DOUBAO_API_KEY",
            aliasApiKeyEnvVars = listOf("ARK_API_KEY"),
            baseUrlEnvVar = "DOUBAO_BASE_URL",
            defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        )
        LlmProvider.CUSTOM -> LlmProviderEnvSpec(
            apiKeyEnvVar = "CUSTOM_LLM_API_KEY",
            baseUrlEnvVar = "CUSTOM_LLM_BASE_URL",
            defaultBaseUrl = null,
        )
    }
}
