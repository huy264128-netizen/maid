package top.aaa000111.maid.provider

import androidx.annotation.StringRes
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.ktor.client.HttpClient
import top.aaa000111.maid.R

/**
 * 应用内四个能力分层的模型角色，对应 `AppRuntime` 中的四个 `PromptExecutor`。
 */
enum class ModelRole(
    val keyPrefix: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    FAST_CHEAP(
        keyPrefix = "fast_cheap",
        titleRes = R.string.settings_model_role_fast_cheap_title,
        descriptionRes = R.string.settings_model_role_fast_cheap_desc,
    ),
    COMPLEX_PLANNING(
        keyPrefix = "complex_planning",
        titleRes = R.string.settings_model_role_complex_planning_title,
        descriptionRes = R.string.settings_model_role_complex_planning_desc,
    ),
    FAST_VISION(
        keyPrefix = "fast_vision",
        titleRes = R.string.settings_model_role_fast_vision_title,
        descriptionRes = R.string.settings_model_role_fast_vision_desc,
    ),
    COMPLEX_VISION(
        keyPrefix = "complex_vision",
        titleRes = R.string.settings_model_role_complex_vision_title,
        descriptionRes = R.string.settings_model_role_complex_vision_desc,
    ),
}

/**
 * 从服务商 `/models` 接口动态解析出的模型能力元数据快照（字段均为可选，缺失时走保守默认值）。
 */
data class LlmModelMetadata(
    val contextLength: Long? = null,
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
    val supportsReasoning: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = contextLength == null &&
            supportsVision == null &&
            supportsTools == null &&
            supportsReasoning == null

    fun formatCompactSummary(): String? {
        val parts = buildList {
            contextLength?.takeIf { it > 0L }?.let { len ->
                val formatted = when {
                    len >= 1_000_000L && len % 1_000_000L == 0L -> "${len / 1_000_000L}M"
                    len >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", len / 1_000_000.0)
                    len >= 1_000L -> "${len / 1_000L}K"
                    else -> len.toString()
                }
                add(formatted)
            }
            if (supportsVision == true) add("视觉")
            if (supportsReasoning == true) add("推理")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

/**
 * 单个模型角色的 Provider、可选自定义端点 ID、Model ID 及能力元数据快照。
 */
data class RoleModelSelection(
    val provider: LlmProvider? = null,
    val modelId: String? = null,
    val customProviderId: String? = null,
    val modelMetadata: LlmModelMetadata? = null,
    val reasoningEffort: ModelReasoningEffort = ModelReasoningEffort.DEFAULT,
) {
    val normalizedModelId: String?
        get() = modelId?.trim()?.takeIf { it.isNotEmpty() }

    val normalizedCustomProviderId: String?
        get() = if (provider == LlmProvider.CUSTOM) {
            customProviderId?.trim()?.takeIf { it.isNotEmpty() } ?: CustomProviderProfile.DEFAULT_ID
        } else {
            null
        }

    val isComplete: Boolean
        get() = (provider != null) && (normalizedModelId != null)

    val hasPartialInput: Boolean
        get() = (provider != null) && (normalizedModelId == null)

    val providerCacheKey: String?
        get() = provider?.let { ModelProviderTarget.providerCacheKey(it, normalizedCustomProviderId) }
}

/**
 * 从服务商接口拉取到的模型条目。
 */
data class LlmModelOption(
    val provider: LlmProvider,
    val modelId: String,
    val displayName: String = modelId,
    val metadata: LlmModelMetadata = LlmModelMetadata(),
)

/**
 * Koog 模型定义与执行器构造辅助方法（模型列表全部实时向服务商服务器请求，不在本地硬编码）。
 */
object LlmModelCatalog {

    const val DEFAULT_CONTEXT_LENGTH = 128_000L

    fun resolveKoogModel(
        provider: LlmProvider,
        modelId: String,
        metadata: LlmModelMetadata? = null,
    ): LLModel {
        val koogProvider = when (provider) {
            LlmProvider.ANTHROPIC -> LLMProvider.Anthropic
            LlmProvider.GEMINI -> LLMProvider.Google
            LlmProvider.DEEPSEEK -> LLMProvider.DeepSeek
            LlmProvider.OPENROUTER -> LLMProvider.OpenRouter
            LlmProvider.OPENAI,
            LlmProvider.CHATGPT,
            LlmProvider.GROQ,
            LlmProvider.XAI,
            LlmProvider.MISTRAL,
            LlmProvider.SILICONFLOW,
            LlmProvider.DASHSCOPE,
            LlmProvider.MOONSHOT,
            LlmProvider.ZHIPU,
            LlmProvider.DOUBAO,
            LlmProvider.CUSTOM -> LLMProvider.OpenAI
        }
        val capabilities = buildList {
            add(LLMCapability.Completion)
            if (provider != LlmProvider.CHATGPT) {
                add(LLMCapability.Temperature)
            }
            if (metadata?.supportsTools != false) {
                add(LLMCapability.Tools)
            }
            if (metadata?.supportsVision != false) {
                add(LLMCapability.Vision.Image)
            }
            if (koogProvider == LLMProvider.OpenAI && provider != LlmProvider.CHATGPT) {
                add(LLMCapability.OpenAIEndpoint.Completions)
            }
        }
        val resolvedContextLength = metadata?.contextLength
            ?.takeIf { it > 0L }
            ?: DEFAULT_CONTEXT_LENGTH
        return LLModel(
            provider = koogProvider,
            id = modelId.trim(),
            capabilities = capabilities,
            contextLength = resolvedContextLength,
        )
    }

    fun createChatGptExecutor(
        accessTokenProvider: suspend (forceRefresh: Boolean) -> String = { forceRefresh ->
            ChatGptAuthRepository.getValidAccessToken(forceRefresh)
        },
        onReasoningSummaryDelta: ((String) -> Unit)? = null,
    ): PromptExecutor = ChatGptResponsesPromptExecutor(
        accessTokenProvider = accessTokenProvider,
        onReasoningSummaryDelta = onReasoningSummaryDelta,
    )

    fun createExecutor(
        provider: LlmProvider,
        apiKey: String,
        customBaseUrl: String? = null,
    ): PromptExecutor {
        if (provider == LlmProvider.CHATGPT) {
            return createChatGptExecutor()
        }
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "API Key must not be blank" }
        return when (provider) {
            LlmProvider.OPENAI -> SingleLLMPromptExecutor(
                OpenAILLMClient(
                    apiKey = trimmedKey,
                    baseClient = ProviderHttpClient.createReasoningAwareHttpClient(),
                ),
            )
            LlmProvider.ANTHROPIC -> SingleLLMPromptExecutor(createAnthropicClient(trimmedKey))
            LlmProvider.GEMINI -> SingleLLMPromptExecutor(
                GoogleLLMClient(
                    apiKey = trimmedKey,
                    baseClient = ProviderHttpClient.createReasoningAwareHttpClient(),
                ),
            )
            LlmProvider.DEEPSEEK -> SingleLLMPromptExecutor(
                DeepSeekLLMClient(
                    apiKey = trimmedKey,
                    baseClient = ProviderHttpClient.createReasoningAwareHttpClient(),
                ),
            )
            LlmProvider.OPENROUTER -> SingleLLMPromptExecutor(
                OpenRouterLLMClient(
                    apiKey = trimmedKey,
                    baseClient = ProviderHttpClient.createReasoningAwareHttpClient(),
                ),
            )
            LlmProvider.GROQ -> openAiCompatibleExecutor(
                apiKey = trimmedKey,
                baseUrl = "https://api.groq.com/openai",
            )
            LlmProvider.XAI -> openAiCompatibleExecutor(
                apiKey = trimmedKey,
                baseUrl = "https://api.x.ai",
            )
            LlmProvider.MISTRAL -> openAiCompatibleExecutor(
                apiKey = trimmedKey,
                baseUrl = "https://api.mistral.ai",
            )
            LlmProvider.SILICONFLOW -> openAiCompatibleExecutor(
                apiKey = trimmedKey,
                baseUrl = "https://api.siliconflow.cn",
            )
            LlmProvider.DASHSCOPE -> openAiCompatibleExecutor(
                apiKey = trimmedKey,
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode",
            )
            LlmProvider.MOONSHOT -> openAiCompatibleExecutor(
                apiKey = trimmedKey,
                baseUrl = "https://api.moonshot.cn",
            )
            LlmProvider.ZHIPU -> openAiCompatibleExecutor(
                apiKey = trimmedKey,
                baseUrl = "https://open.bigmodel.cn",
                chatCompletionsPath = "api/paas/v4/chat/completions",
            )
            LlmProvider.DOUBAO -> openAiCompatibleExecutor(
                apiKey = trimmedKey,
                baseUrl = "https://ark.cn-beijing.volces.com",
                chatCompletionsPath = "api/v3/chat/completions",
            )
            LlmProvider.CUSTOM -> {
                val normalizedBase = normalizeCustomOpenAiBaseUrl(customBaseUrl)
                if (normalizedBase == null) {
                    SingleLLMPromptExecutor(
                        OpenAILLMClient(
                            apiKey = trimmedKey,
                            baseClient = ProviderHttpClient.createReasoningAwareHttpClient(),
                        ),
                    )
                } else {
                    openAiCompatibleExecutor(
                        apiKey = trimmedKey,
                        baseUrl = normalizedBase,
                    )
                }
            }
        }
    }

    internal fun createAnthropicClient(
        apiKey: String,
        baseClient: HttpClient = ProviderHttpClient.createReasoningAwareHttpClient(),
    ): AnthropicLLMClient {
        // Koog looks up the wire ID by LLModel. Maid already uses IDs from Anthropic's model list.
        val modelIds = object : Map<LLModel, String> by emptyMap() {
            override fun get(key: LLModel): String? = key.id.takeIf { key.provider == LLMProvider.Anthropic }
        }
        return AnthropicLLMClient(
            apiKey = apiKey,
            settings = AnthropicClientSettings(modelVersionsMap = modelIds),
            baseClient = baseClient,
        )
    }

    private fun openAiCompatibleExecutor(
        apiKey: String,
        baseUrl: String,
        chatCompletionsPath: String? = null,
    ): PromptExecutor {
        val settings = if (chatCompletionsPath == null) {
            OpenAIClientSettings(baseUrl = baseUrl)
        } else {
            OpenAIClientSettings(
                baseUrl = baseUrl,
                chatCompletionsPath = chatCompletionsPath,
            )
        }
        return SingleLLMPromptExecutor(
            OpenAILLMClient(
                apiKey = apiKey,
                settings = settings,
                baseClient = ProviderHttpClient.createReasoningAwareHttpClient(),
            ),
        )
    }

    internal fun normalizeCustomOpenAiBaseUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.removeSuffix("/models").removeSuffix("/v1").trimEnd('/')
    }
}

/** 单独的默认图片生成目标，不参与聊天模型配置组。 */
data class ImageGenModelSelection(
    val provider: LlmProvider? = null,
    val modelId: String = "",
    val customProviderId: String? = null,
) {
    val normalizedModelId: String
        get() = modelId.trim()

    val normalizedCustomProviderId: String?
        get() = if (provider == LlmProvider.CUSTOM) {
            customProviderId?.trim()?.takeIf { it.isNotEmpty() } ?: CustomProviderProfile.DEFAULT_ID
        } else {
            null
        }

    val isConfigured: Boolean
        get() = provider != null && normalizedModelId.isNotEmpty()
}
