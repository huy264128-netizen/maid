package top.aaa000111.maid.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ModelHttpResponse(
    val statusCode: Int,
    val body: String,
)

interface ModelHttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): ModelHttpResponse
}

/**
 * 直接向各 LLM 服务商服务器请求实时可用模型列表，不使用本地硬编码或磁盘缓存（全局单例）。
 *
 * 默认使用基于统一 Ktor + OkHttp 栈的 [KtorModelHttpTransport]，并自动解析服务商返回的可选能力元数据
 *（上下文长度、视觉、工具调用与推理支持）。
 */
object LlmProviderModelFetcher {

    private val defaultTransport: ModelHttpTransport by lazy {
        KtorModelHttpTransport(ProviderHttpClient.sharedHttpClient)
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchModels(
        provider: LlmProvider,
        apiKey: String?,
        customBaseUrl: String? = null,
        transport: ModelHttpTransport = defaultTransport,
        chatGptAccessTokenProvider: suspend (forceRefresh: Boolean) -> String = { forceRefresh ->
            ChatGptAuthRepository.getValidAccessToken(forceRefresh)
        },
    ): Result<List<LlmModelOption>> {
        if (provider == LlmProvider.CHATGPT) {
            return fetchChatGptModels(
                transport = transport,
                accessTokenProvider = chatGptAccessTokenProvider,
            )
        }
        return fetchApiKeyModels(
            provider = provider,
            apiKey = apiKey,
            customBaseUrl = customBaseUrl,
            transport = transport,
        )
    }

    suspend fun fetchApiKeyModels(
        provider: LlmProvider,
        apiKey: String?,
        customBaseUrl: String? = null,
        transport: ModelHttpTransport = defaultTransport,
    ): Result<List<LlmModelOption>> {
        val trimmedKey = apiKey?.trim().orEmpty()
        if (trimmedKey.isEmpty()) {
            return Result.failure(
                IllegalStateException("尚未配置该服务商的 API Key，请先在「API Key 管理」中保存密钥"),
            )
        }

        val requestSpec = try {
            buildRequestSpec(provider, trimmedKey, customBaseUrl)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        return runCatching {
            val response = transport.get(requestSpec.url, requestSpec.headers)
            if (response.statusCode !in 200..299) {
                val detail = extractErrorMessage(response.body)
                val suffix = if (detail.isNullOrBlank()) "" else "：$detail"
                throw IllegalStateException("服务器返回错误 (${response.statusCode})$suffix")
            }

            val models = parseModels(provider, response.body)
            if (models.isEmpty()) {
                throw IllegalStateException("服务商未返回任何可用模型")
            }
            models
        }
    }

    suspend fun fetchChatGptModels(
        transport: ModelHttpTransport = defaultTransport,
        accessTokenProvider: suspend (forceRefresh: Boolean) -> String = { forceRefresh ->
            ChatGptAuthRepository.getValidAccessToken(forceRefresh)
        },
    ): Result<List<LlmModelOption>> = runCatching {
        var token = accessTokenProvider(false)
        var spec = buildRequestSpec(LlmProvider.CHATGPT, token, customBaseUrl = null)
        var response = transport.get(spec.url, spec.headers)
        if (response.statusCode == 401) {
            token = accessTokenProvider(true)
            spec = buildRequestSpec(LlmProvider.CHATGPT, token, customBaseUrl = null)
            response = transport.get(spec.url, spec.headers)
        }
        if (response.statusCode !in 200..299) {
            val detail = extractErrorMessage(response.body)
            val suffix = if (detail.isNullOrBlank()) "" else "：$detail"
            throw IllegalStateException("ChatGPT 模型列表获取失败 (${response.statusCode})$suffix")
        }

        val models = parseModels(LlmProvider.CHATGPT, response.body)
        if (models.isEmpty()) {
            throw IllegalStateException("ChatGPT 未返回任何可用模型")
        }
        models
    }

    internal fun buildRequestSpec(
        provider: LlmProvider,
        apiKey: String,
        customBaseUrl: String?,
    ): RequestSpec {
        return when (provider) {
            LlmProvider.OPENAI,
            LlmProvider.CHATGPT -> RequestSpec(
                url = "https://api.openai.com/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.ANTHROPIC -> RequestSpec(
                url = "https://api.anthropic.com/v1/models?limit=100",
                headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                ),
            )

            LlmProvider.GEMINI -> RequestSpec(
                url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100",
                headers = mapOf("x-goog-api-key" to apiKey),
            )

            LlmProvider.DEEPSEEK -> RequestSpec(
                url = "https://api.deepseek.com/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.OPENROUTER -> RequestSpec(
                url = "https://openrouter.ai/api/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.GROQ -> RequestSpec(
                url = "https://api.groq.com/openai/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.XAI -> RequestSpec(
                url = "https://api.x.ai/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.MISTRAL -> RequestSpec(
                url = "https://api.mistral.ai/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.SILICONFLOW -> RequestSpec(
                url = "https://api.siliconflow.cn/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.DASHSCOPE -> RequestSpec(
                url = "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.MOONSHOT -> RequestSpec(
                url = "https://api.moonshot.cn/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.ZHIPU -> RequestSpec(
                url = "https://open.bigmodel.cn/api/paas/v4/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.DOUBAO -> RequestSpec(
                url = "https://ark.cn-beijing.volces.com/api/v3/models",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )

            LlmProvider.CUSTOM -> {
                val modelsUrl = resolveCustomModelsUrl(customBaseUrl)
                    ?: throw IllegalArgumentException(
                        "请先在「API Key 管理」中填写自定义服务商的 Base URL",
                    )
                RequestSpec(
                    url = modelsUrl,
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                )
            }
        }
    }

    internal fun resolveCustomModelsUrl(rawBaseUrl: String?): String? {
        val trimmed = rawBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            trimmed.endsWith("/models") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/models"
            else -> "$trimmed/v1/models"
        }
    }

    internal fun parseModels(provider: LlmProvider, rawJson: String): List<LlmModelOption> {
        val root = json.parseToJsonElement(rawJson).jsonObject
        return when (provider) {
            LlmProvider.GEMINI -> parseGeminiModels(root)
            LlmProvider.ANTHROPIC -> parseAnthropicModels(root)
            LlmProvider.CHATGPT -> parseChatGptModels(root)
            LlmProvider.OPENAI,
            LlmProvider.DEEPSEEK,
            LlmProvider.OPENROUTER,
            LlmProvider.GROQ,
            LlmProvider.XAI,
            LlmProvider.MISTRAL,
            LlmProvider.SILICONFLOW,
            LlmProvider.DASHSCOPE,
            LlmProvider.MOONSHOT,
            LlmProvider.ZHIPU,
            LlmProvider.DOUBAO,
            LlmProvider.CUSTOM -> parseOpenAiStyleModels(provider, root)
        }
    }

    private fun parseChatGptModels(root: JsonObject): List<LlmModelOption> {
        val modelsArray = root["models"] as? JsonArray
        if (modelsArray != null) {
            val seen = LinkedHashSet<String>()
            val listed = ArrayList<LlmModelOption>(modelsArray.size)
            val fallbackAll = ArrayList<LlmModelOption>(modelsArray.size)
            val seenFallback = LinkedHashSet<String>()

            for (element in modelsArray) {
                val item = element as? JsonObject ?: continue
                val id = (item["slug"] ?: item["id"] ?: item["name"])
                    ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (id.isEmpty()) continue
                val displayName = (item["display_name"] ?: item["title"] ?: item["name"])
                    ?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: id
                val option = LlmModelOption(
                    provider = LlmProvider.CHATGPT,
                    modelId = id,
                    displayName = displayName,
                    metadata = extractOpenAiStyleMetadata(item),
                )
                if (seenFallback.add(id)) {
                    fallbackAll.add(option)
                }
                val visibility = item["visibility"]?.jsonPrimitive?.contentOrNull?.trim()
                if ((visibility.isNullOrEmpty() || visibility.equals("list", ignoreCase = true)) && seen.add(id)) {
                    listed.add(option)
                }
            }
            val chosen = if (listed.isNotEmpty()) listed else fallbackAll
            if (chosen.isNotEmpty()) {
                return chosen.sortedBy { it.modelId.lowercase() }
            }
        }
        return parseOpenAiStyleModels(LlmProvider.CHATGPT, root)
    }

    private fun parseOpenAiStyleModels(
        provider: LlmProvider,
        root: JsonObject,
    ): List<LlmModelOption> {
        val array = (root["data"] ?: root["models"])?.jsonArray ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val result = ArrayList<LlmModelOption>(array.size)
        for (element in array) {
            val item = element as? JsonObject ?: continue
            val id = (item["id"] ?: item["slug"] ?: item["name"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isNotEmpty() && seen.add(id)) {
                val displayName = (item["display_name"] ?: item["name"])?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: id
                result.add(
                    LlmModelOption(
                        provider = provider,
                        modelId = id,
                        displayName = displayName,
                        metadata = extractOpenAiStyleMetadata(item),
                    ),
                )
            }
        }
        return result.sortedBy { it.modelId.lowercase() }
    }

    private fun parseAnthropicModels(root: JsonObject): List<LlmModelOption> {
        val array = root["data"]?.jsonArray ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val result = ArrayList<LlmModelOption>(array.size)
        for (element in array) {
            val item = element as? JsonObject ?: continue
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isNotEmpty() && seen.add(id)) {
                val displayName = item["display_name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: id
                val contextLength = firstPositiveLong(
                    item["max_input_tokens"],
                    item["context_window"],
                    item["context_length"],
                )
                val capabilities = item["capabilities"] as? JsonObject
                val supportsVision = extractNestedSupportedBoolean(capabilities, "image_input")
                    ?: (capabilities?.get("vision") as? JsonPrimitive)?.booleanOrNull
                val supportsTools = extractNestedSupportedBoolean(capabilities, "tools")
                    ?: (capabilities?.get("tool_use") as? JsonPrimitive)?.booleanOrNull
                val supportsReasoning = extractNestedSupportedBoolean(capabilities, "thinking")
                    ?: extractNestedSupportedBoolean(capabilities, "reasoning")
                result.add(
                    LlmModelOption(
                        provider = LlmProvider.ANTHROPIC,
                        modelId = id,
                        displayName = displayName,
                        metadata = LlmModelMetadata(
                            contextLength = contextLength,
                            supportsVision = supportsVision,
                            supportsTools = supportsTools,
                            supportsReasoning = supportsReasoning,
                        ),
                    ),
                )
            }
        }
        return result
    }

    private fun parseGeminiModels(root: JsonObject): List<LlmModelOption> {
        val array = root["models"]?.jsonArray ?: return emptyList()
        val allModels = ArrayList<LlmModelOption>(array.size)
        val chatCapableModels = ArrayList<LlmModelOption>(array.size)
        val seenAll = LinkedHashSet<String>()
        val seenChat = LinkedHashSet<String>()

        for (element in array) {
            val item = element as? JsonObject ?: continue
            val rawName = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val id = rawName.removePrefix("models/").trim()
            if (id.isEmpty()) continue

            val displayName = item["displayName"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: id
            val metadata = LlmModelMetadata(
                contextLength = firstPositiveLong(item["inputTokenLimit"], item["contextWindow"]),
                supportsReasoning = (item["thinking"] as? JsonPrimitive)?.booleanOrNull,
            )
            val option = LlmModelOption(
                provider = LlmProvider.GEMINI,
                modelId = id,
                displayName = displayName,
                metadata = metadata,
            )
            if (seenAll.add(id)) {
                allModels.add(option)
            }

            val methods = item["supportedGenerationMethods"]?.jsonArray
            if (methods != null && methods.isNotEmpty()) {
                val supportsGenerateContent = methods.any {
                    it.jsonPrimitive.contentOrNull == "generateContent"
                }
                if (supportsGenerateContent && seenChat.add(id)) {
                    chatCapableModels.add(option)
                }
            }
        }
        return if (chatCapableModels.isNotEmpty()) chatCapableModels else allModels
    }

    private fun extractOpenAiStyleMetadata(item: JsonObject): LlmModelMetadata {
        val topProvider = item["top_provider"] as? JsonObject
        val contextLength = firstPositiveLong(
            item["context_length"],
            item["context_window"],
            item["max_context_length"],
            item["max_model_len"],
            topProvider?.get("context_length"),
        )

        val architecture = item["architecture"] as? JsonObject
        val capabilities = item["capabilities"] as? JsonObject
        val inputModalities = (architecture?.get("input_modalities") ?: item["input_modalities"]) as? JsonArray
        val modalityString = architecture?.get("modality")?.let { (it as? JsonPrimitive)?.contentOrNull }

        val supportsVision: Boolean? = when {
            (capabilities?.get("vision") as? JsonPrimitive)?.booleanOrNull != null ->
                (capabilities["vision"] as JsonPrimitive).booleanOrNull
            inputModalities != null && inputModalities.isNotEmpty() ->
                inputModalities.any { (it as? JsonPrimitive)?.contentOrNull.equals("image", ignoreCase = true) }
            !modalityString.isNullOrBlank() ->
                modalityString.substringBefore("->").contains("image", ignoreCase = true)
            else -> null
        }

        val supportedParameters = item["supported_parameters"] as? JsonArray
        val supportsTools: Boolean? = when {
            (capabilities?.get("tools") as? JsonPrimitive)?.booleanOrNull != null ->
                (capabilities["tools"] as JsonPrimitive).booleanOrNull
            (capabilities?.get("function_calling") as? JsonPrimitive)?.booleanOrNull != null ->
                (capabilities["function_calling"] as JsonPrimitive).booleanOrNull
            supportedParameters != null && supportedParameters.isNotEmpty() ->
                supportedParameters.any { (it as? JsonPrimitive)?.contentOrNull.equals("tools", ignoreCase = true) }
            else -> null
        }

        val reasoningLevels = (item["supported_reasoning_levels"] ?: item["reasoning_levels"]) as? JsonArray
        val supportsReasoning: Boolean? = when {
            (capabilities?.get("reasoning") as? JsonPrimitive)?.booleanOrNull != null ->
                (capabilities["reasoning"] as JsonPrimitive).booleanOrNull
            (capabilities?.get("thinking") as? JsonPrimitive)?.booleanOrNull != null ->
                (capabilities["thinking"] as JsonPrimitive).booleanOrNull
            (item["supports_reasoning_summaries"] as? JsonPrimitive)?.booleanOrNull != null ->
                (item["supports_reasoning_summaries"] as JsonPrimitive).booleanOrNull
            reasoningLevels != null -> reasoningLevels.isNotEmpty()
            !((item["default_reasoning_level"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) -> true
            supportedParameters != null && supportedParameters.isNotEmpty() ->
                supportedParameters.any { elem ->
                    val p = (elem as? JsonPrimitive)?.contentOrNull
                    p.equals("reasoning", ignoreCase = true) || p.equals("include_reasoning", ignoreCase = true)
                }
            else -> null
        }

        return LlmModelMetadata(
            contextLength = contextLength,
            supportsVision = supportsVision,
            supportsTools = supportsTools,
            supportsReasoning = supportsReasoning,
        )
    }

    private fun extractNestedSupportedBoolean(
        capabilities: JsonObject?,
        key: String,
    ): Boolean? {
        val node = capabilities?.get(key) ?: return null
        return when (node) {
            is JsonPrimitive -> node.booleanOrNull
            is JsonObject -> (node["supported"] as? JsonPrimitive)?.booleanOrNull
            else -> null
        }
    }

    private fun firstPositiveLong(vararg elements: kotlinx.serialization.json.JsonElement?): Long? {
        for (elem in elements) {
            val value = (elem as? JsonPrimitive)?.longOrNull
            if (value != null && value > 0L) return value
        }
        return null
    }

    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val nestedError = (root["error"] as? JsonObject)
                ?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
            if (!nestedError.isNullOrEmpty()) {
                return@runCatching nestedError
            }
            root["message"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: body.trim().take(160)
    }

    internal data class RequestSpec(
        val url: String,
        val headers: Map<String, String>,
    )
}
