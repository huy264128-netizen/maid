package top.aaa000111.maid.provider

import androidx.annotation.StringRes
import top.aaa000111.maid.R

/**
 * 模型服务商的鉴权方式：
 * - [API_KEY]：通过「API Key 管理」页面配置静态 API 密钥的服务商
 * - [CHATGPT_OAUTH]：通过 OpenAI「Sign in with ChatGPT」OAuth 2.0 / OIDC 登录的服务商
 */
enum class LlmProviderAuthType {
    API_KEY,
    CHATGPT_OAUTH,
}

/**
 * 静态 API Key 服务商的存储与输入提示元数据。
 * 非 API Key 服务商（如 OAuth 登录的 [LlmProvider.CHATGPT]）无需携带该配置，直接置为 `null`。
 */
data class ApiKeySpec(
    val storageKey: String,
    val keyHint: String,
)

/**
 * 自定义 OpenAI 兼容端点配置档案（支持同时配置多个自建/聚合服务，如本机 Ollama、公司 vLLM 与第三方中转）。
 *
 * - 当 [id] 为 [DEFAULT_ID] 时，透明兼容历史上单一 `LlmProvider.CUSTOM` 的存储键（`llm_api_key_custom` 与 `custom_provider_base_url`）。
 * - 新增的自定义端点使用独立存储键 `llm_api_key_custom_<id>`。
 */
data class CustomProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String = "",
) {
    val normalizedId: String
        get() = id.trim()

    val normalizedName: String
        get() = name.trim()

    val normalizedBaseUrl: String
        get() = baseUrl.trim()

    val storageKey: String
        get() = storageKeyFor(normalizedId)

    companion object {
        const val DEFAULT_ID = "default"
        private const val CUSTOM_KEY_PREFIX = "llm_api_key_custom_"

        fun storageKeyFor(profileId: String?): String {
            val normalized = profileId?.trim().orEmpty()
            return if (normalized.isEmpty() || normalized == DEFAULT_ID) {
                LlmProvider.CUSTOM.storageKey
            } else {
                "$CUSTOM_KEY_PREFIX$normalized"
            }
        }
    }
}

/**
 * 「模型选择」页面中的一个具体可选服务商目标：
 * - 对内置服务商，[customProviderId] 为 `null`；
 * - 对多自定义端点配置，[provider] 为 [LlmProvider.CUSTOM] 且携带对应的 [customProviderId] 与 [customDisplayName]。
 */
data class ModelProviderTarget(
    val provider: LlmProvider,
    val customProviderId: String? = null,
    val customDisplayName: String? = null,
) {
    val cacheKey: String
        get() = providerCacheKey(provider, customProviderId)

    companion object {
        fun providerCacheKey(provider: LlmProvider, customProviderId: String? = null): String {
            if (provider != LlmProvider.CUSTOM) return provider.id
            val normalizedCustomId = customProviderId?.trim()?.takeIf { it.isNotEmpty() }
                ?: CustomProviderProfile.DEFAULT_ID
            return "${provider.id}:$normalizedCustomId"
        }
    }
}

/**
 * LLM 模型服务商枚举。
 *
 * 注意：「API Key 管理」仅展示 [apiKeyProviders]（[LlmProviderAuthType.API_KEY]），
 * 而「模型选择」展示更广泛的 [modelSelectionProviders]（含 OAuth 账号登录类服务商，如 [CHATGPT]）。
 */
enum class LlmProvider(
    val id: String,
    @param:StringRes val displayNameRes: Int,
    val apiKeySpec: ApiKeySpec? = null,
    val authType: LlmProviderAuthType = LlmProviderAuthType.API_KEY,
) {
    OPENAI(
        id = "openai",
        displayNameRes = R.string.llm_provider_openai,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_openai",
            keyHint = "sk-...",
        ),
    ),
    CHATGPT(
        id = "chatgpt",
        displayNameRes = R.string.llm_provider_chatgpt,
        apiKeySpec = null,
        authType = LlmProviderAuthType.CHATGPT_OAUTH,
    ),
    ANTHROPIC(
        id = "anthropic",
        displayNameRes = R.string.llm_provider_anthropic,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_anthropic",
            keyHint = "sk-ant-...",
        ),
    ),
    GEMINI(
        id = "gemini",
        displayNameRes = R.string.llm_provider_gemini,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_gemini",
            keyHint = "AIza...",
        ),
    ),
    DEEPSEEK(
        id = "deepseek",
        displayNameRes = R.string.llm_provider_deepseek,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_deepseek",
            keyHint = "sk-...",
        ),
    ),
    OPENROUTER(
        id = "openrouter",
        displayNameRes = R.string.llm_provider_openrouter,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_openrouter",
            keyHint = "sk-or-v1-...",
        ),
    ),
    GROQ(
        id = "groq",
        displayNameRes = R.string.llm_provider_groq,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_groq",
            keyHint = "gsk_...",
        ),
    ),
    XAI(
        id = "xai",
        displayNameRes = R.string.llm_provider_xai,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_xai",
            keyHint = "xai-...",
        ),
    ),
    MISTRAL(
        id = "mistral",
        displayNameRes = R.string.llm_provider_mistral,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_mistral",
            keyHint = "mistral-...",
        ),
    ),
    SILICONFLOW(
        id = "siliconflow",
        displayNameRes = R.string.llm_provider_siliconflow,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_siliconflow",
            keyHint = "sk-...",
        ),
    ),
    DASHSCOPE(
        id = "dashscope",
        displayNameRes = R.string.llm_provider_dashscope,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_dashscope",
            keyHint = "sk-...",
        ),
    ),
    MOONSHOT(
        id = "moonshot",
        displayNameRes = R.string.llm_provider_moonshot,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_moonshot",
            keyHint = "sk-...",
        ),
    ),
    ZHIPU(
        id = "zhipu",
        displayNameRes = R.string.llm_provider_zhipu,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_zhipu",
            keyHint = "id.secret",
        ),
    ),
    DOUBAO(
        id = "doubao",
        displayNameRes = R.string.llm_provider_doubao,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_doubao",
            keyHint = "ark-...",
        ),
    ),
    CUSTOM(
        id = "custom",
        displayNameRes = R.string.llm_provider_custom,
        apiKeySpec = ApiKeySpec(
            storageKey = "llm_api_key_custom",
            keyHint = "sk-...",
        ),
    );

    /** 向后兼容属性：无 API Key 配置的服务商返回空字符串 */
    val storageKey: String
        get() = apiKeySpec?.storageKey.orEmpty()

    /** 向后兼容属性：无 API Key 配置的服务商返回空字符串 */
    val keyHint: String
        get() = apiKeySpec?.keyHint.orEmpty()

    val usesApiKey: Boolean
        get() = apiKeySpec != null && authType == LlmProviderAuthType.API_KEY

    companion object {
        /** 仅限在「API Key 管理」页面配置的服务商集合 */
        val apiKeyProviders: List<LlmProvider> = entries.filter { it.usesApiKey }

        /** 「模型选择」页面支持的服务商集合（包含 API Key 与账号登录等非 API Key 服务商） */
        val modelSelectionProviders: List<LlmProvider> = entries

        fun fromId(id: String?): LlmProvider {
            return entries.firstOrNull { it.id == id } ?: OPENAI
        }
    }
}
