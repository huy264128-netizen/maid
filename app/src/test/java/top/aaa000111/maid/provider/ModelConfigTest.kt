package top.aaa000111.maid.provider

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigTest {

    private class FakeTransport(
        private val handler: (url: String, headers: Map<String, String>) -> ModelHttpResponse,
    ) : ModelHttpTransport {
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()

        override suspend fun get(url: String, headers: Map<String, String>): ModelHttpResponse {
            lastUrl = url
            lastHeaders = headers
            return handler(url, headers)
        }
    }

    @Test
    fun modelRole_hasFourExpectedRoles() {
        assertEquals(
            listOf(
                ModelRole.FAST_CHEAP,
                ModelRole.COMPLEX_PLANNING,
                ModelRole.FAST_VISION,
                ModelRole.COMPLEX_VISION,
            ),
            ModelRole.entries,
        )
    }

    @Test
    fun roleModelSelection_validatesCompletionAndPartialInput() {
        val empty = RoleModelSelection()
        assertFalse(empty.isComplete)
        assertFalse(empty.hasPartialInput)

        val providerOnly = RoleModelSelection(provider = LlmProvider.OPENAI, modelId = "   ")
        assertFalse(providerOnly.isComplete)
        assertTrue(providerOnly.hasPartialInput)

        val complete = RoleModelSelection(provider = LlmProvider.OPENAI, modelId = " gpt-4o-mini ")
        assertTrue(complete.isComplete)
        assertFalse(complete.hasPartialInput)
        assertEquals("gpt-4o-mini", complete.normalizedModelId)
    }

    @Test
    fun imageGenModelSelection_normalizesModelAndCustomEndpoint() {
        val empty = ImageGenModelSelection()
        assertFalse(empty.isConfigured)

        val configured = ImageGenModelSelection(
            provider = LlmProvider.CUSTOM,
            modelId = " image-model ",
            customProviderId = " team-endpoint ",
        )
        assertTrue(configured.isConfigured)
        assertEquals("image-model", configured.normalizedModelId)
        assertEquals("team-endpoint", configured.normalizedCustomProviderId)

        val defaultCustom = configured.copy(customProviderId = " ")
        assertEquals(CustomProviderProfile.DEFAULT_ID, defaultCustom.normalizedCustomProviderId)
    }

    @Test
    fun llmModelCatalog_resolvesDynamicKoogModelAndExecutor() {
        val resolved = LlmModelCatalog.resolveKoogModel(LlmProvider.CUSTOM, "my-custom-model")
        assertEquals(LLMProvider.OpenAI, resolved.provider)
        assertEquals("my-custom-model", resolved.id)

        assertEquals(
            "https://example.com",
            LlmModelCatalog.normalizeCustomOpenAiBaseUrl("https://example.com/v1/"),
        )
        assertNull(LlmModelCatalog.normalizeCustomOpenAiBaseUrl("   "))

        LlmProvider.apiKeyProviders.forEach { provider ->
            assertNotNull(
                LlmModelCatalog.createExecutor(
                    provider = provider,
                    apiKey = "test-api-key",
                    customBaseUrl = "https://example.com/v1",
                ),
            )
        }
    }

    @Test
    fun llmModelCatalog_appliesDynamicMetadataToResolvedKoogModel() {
        val textOnlyMeta = LlmModelMetadata(
            contextLength = 256_000L,
            supportsVision = false,
            supportsTools = false,
            supportsReasoning = true,
        )
        val textOnlyModel = LlmModelCatalog.resolveKoogModel(
            provider = LlmProvider.OPENROUTER,
            modelId = "custom/reasoning-text-only",
            metadata = textOnlyMeta,
        )
        assertEquals(256_000L, textOnlyModel.contextLength)
        assertFalse(LLMCapability.Vision.Image in textOnlyModel.capabilities)
        assertFalse(LLMCapability.Tools in textOnlyModel.capabilities)
        assertEquals("256K · 推理", textOnlyMeta.formatCompactSummary())

        val visionMeta = LlmModelMetadata(
            contextLength = 1_048_576L,
            supportsVision = true,
            supportsTools = true,
            supportsReasoning = false,
        )
        val visionModel = LlmModelCatalog.resolveKoogModel(
            provider = LlmProvider.GEMINI,
            modelId = "gemini-2.5-pro",
            metadata = visionMeta,
        )
        assertEquals(1_048_576L, visionModel.contextLength)
        assertTrue(LLMCapability.Vision.Image in visionModel.capabilities)
        assertTrue(LLMCapability.Tools in visionModel.capabilities)
        assertEquals("1.0M · 视觉", visionMeta.formatCompactSummary())
    }

    @Test(expected = IllegalArgumentException::class)
    fun llmModelCatalog_createExecutor_rejectsBlankKey() {
        LlmModelCatalog.createExecutor(LlmProvider.OPENAI, "   ")
    }

    @Test
    fun modelFetcher_fetchesOpenAiModelsFromServer() = runBlocking {
        val transport = FakeTransport { _, _ ->
            ModelHttpResponse(
                statusCode = 200,
                body = """{"data":[{"id":"gpt-5"},{"id":"gpt-4o-mini"}]}""",
            )
        }

        val result = LlmProviderModelFetcher.fetchModels(
            LlmProvider.OPENAI,
            "sk-test",
            transport = transport,
        )
        assertTrue(result.isSuccess)
        assertEquals("https://api.openai.com/v1/models", transport.lastUrl)
        assertEquals("Bearer sk-test", transport.lastHeaders["Authorization"])
        assertEquals(listOf("gpt-4o-mini", "gpt-5"), result.getOrThrow().map { it.modelId })
    }

    @Test
    fun modelFetcher_fetchesAnthropicModelsWithRequiredHeadersAndMetadata() = runBlocking {
        val transport = FakeTransport { _, _ ->
            ModelHttpResponse(
                statusCode = 200,
                body = """{"data":[{"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5","max_input_tokens":200000,"capabilities":{"image_input":{"supported":true},"tools":{"supported":true},"thinking":{"supported":true}}}]}""",
            )
        }

        val result = LlmProviderModelFetcher.fetchModels(
            LlmProvider.ANTHROPIC,
            "sk-ant-test",
            transport = transport,
        )
        assertTrue(result.isSuccess)
        assertEquals("https://api.anthropic.com/v1/models?limit=100", transport.lastUrl)
        assertEquals("sk-ant-test", transport.lastHeaders["x-api-key"])
        assertEquals("2023-06-01", transport.lastHeaders["anthropic-version"])
        val first = result.getOrThrow().single()
        assertEquals("claude-sonnet-4-5", first.modelId)
        assertEquals("Claude Sonnet 4.5", first.displayName)
        assertEquals(200_000L, first.metadata.contextLength)
        assertEquals(true, first.metadata.supportsVision)
        assertEquals(true, first.metadata.supportsTools)
    }

    @Test
    fun modelFetcher_fetchesGeminiModelsWithMetadataAndStripsPrefix() = runBlocking {
        val transport = FakeTransport { _, _ ->
            ModelHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "models": [
                        {
                          "name": "models/gemini-2.5-flash",
                          "displayName": "Gemini 2.5 Flash",
                          "inputTokenLimit": 1048576,
                          "thinking": true,
                          "supportedGenerationMethods": ["generateContent"]
                        },
                        {
                          "name": "models/text-embedding-004",
                          "displayName": "Text Embedding",
                          "supportedGenerationMethods": ["embedContent"]
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }

        val result = LlmProviderModelFetcher.fetchModels(
            LlmProvider.GEMINI,
            "AIza-test",
            transport = transport,
        )
        assertTrue(result.isSuccess)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100",
            transport.lastUrl,
        )
        assertEquals("AIza-test", transport.lastHeaders["x-goog-api-key"])
        val models = result.getOrThrow()
        assertEquals(1, models.size)
        assertEquals("gemini-2.5-flash", models.first().modelId)
        assertEquals("Gemini 2.5 Flash", models.first().displayName)
        assertEquals(1_048_576L, models.first().metadata.contextLength)
        assertEquals(true, models.first().metadata.supportsReasoning)
    }

    @Test
    fun modelFetcher_parsesOpenRouterAndChatGptMetadata() = runBlocking {
        val openRouterTransport = FakeTransport { _, _ ->
            ModelHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "data": [
                        {
                          "id": "openai/gpt-4o",
                          "name": "OpenAI: GPT-4o",
                          "context_length": 128000,
                          "architecture": { "input_modalities": ["text", "image"] },
                          "supported_parameters": ["tools", "reasoning"]
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val orModel = LlmProviderModelFetcher.fetchApiKeyModels(
            LlmProvider.OPENROUTER,
            "sk-or-v1-test",
            transport = openRouterTransport,
        ).getOrThrow().single()
        assertEquals(128_000L, orModel.metadata.contextLength)
        assertEquals(true, orModel.metadata.supportsVision)
        assertEquals(true, orModel.metadata.supportsTools)
        assertEquals(true, orModel.metadata.supportsReasoning)

        val chatGptTransport = FakeTransport { _, _ ->
            ModelHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "models": [
                        {
                          "slug": "gpt-5.4",
                          "visibility": "list",
                          "display_name": "GPT-5.4",
                          "context_window": 272000,
                          "input_modalities": ["text", "image"],
                          "supports_reasoning_summaries": true
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val cgModel = LlmProviderModelFetcher.fetchChatGptModels(
            accessTokenProvider = { "oauth_tok" },
            transport = chatGptTransport,
        ).getOrThrow().single()
        assertEquals(272_000L, cgModel.metadata.contextLength)
        assertEquals(true, cgModel.metadata.supportsVision)
        assertEquals(true, cgModel.metadata.supportsReasoning)
    }

    @Test
    fun customProviderProfileSerializer_roundTripsProfilesAndPreservesDefault() {
        val profiles = listOf(
            CustomProviderProfile(
                id = CustomProviderProfile.DEFAULT_ID,
                name = "默认中转",
                baseUrl = "https://relay.example.com/v1",
            ),
            CustomProviderProfile(
                id = "ollama",
                name = "本机 Ollama",
                baseUrl = "http://127.0.0.1:11434/v1",
            ),
        )
        val encoded = CustomProviderProfileSerializer.encode(profiles)
        val decoded = CustomProviderProfileSerializer.decode(encoded)
        assertEquals(profiles, decoded)

        assertEquals("llm_api_key_custom", CustomProviderProfile.storageKeyFor(CustomProviderProfile.DEFAULT_ID))
        assertEquals("llm_api_key_custom_ollama", CustomProviderProfile.storageKeyFor("ollama"))
        assertEquals("custom:default", ModelProviderTarget.providerCacheKey(LlmProvider.CUSTOM, null))
        assertEquals("custom:ollama", ModelProviderTarget.providerCacheKey(LlmProvider.CUSTOM, "ollama"))
    }

    @Test
    fun ktorModelHttpTransport_performsGetWithHeadersViaKtor() = runBlocking {
        var capturedAuth: String? = null
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"data":[{"id":"m1"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val transport = KtorModelHttpTransport(mockClient)
        val resp = transport.get("https://api.example.com/v1/models", mapOf("Authorization" to "Bearer k123"))
        assertEquals(200, resp.statusCode)
        assertEquals("Bearer k123", capturedAuth)
        assertTrue(resp.body.contains("m1"))
    }

    @Test
    fun modelFetcher_fetchesDeepSeekAndCustomEndpoints() = runBlocking {
        val transport = FakeTransport { _, _ ->
            ModelHttpResponse(
                statusCode = 200,
                body = """{"data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}""",
            )
        }

        val dsResult = LlmProviderModelFetcher.fetchModels(
            LlmProvider.DEEPSEEK,
            "sk-ds",
            transport = transport,
        )
        assertTrue(dsResult.isSuccess)
        assertEquals("https://api.deepseek.com/models", transport.lastUrl)

        val openRouterTransport = FakeTransport { _, _ ->
            ModelHttpResponse(
                statusCode = 200,
                body = """{"data":[{"id":"openai/gpt-4o","name":"OpenAI: GPT-4o"}]}""",
            )
        }
        val orResult = LlmProviderModelFetcher.fetchModels(
            LlmProvider.OPENROUTER,
            "sk-or-v1-test",
            transport = openRouterTransport,
        )
        assertTrue(orResult.isSuccess)
        assertEquals("https://openrouter.ai/api/v1/models", openRouterTransport.lastUrl)
        assertEquals("OpenAI: GPT-4o", orResult.getOrThrow().single().displayName)

        val expectedEndpoints = mapOf(
            LlmProvider.GROQ to "https://api.groq.com/openai/v1/models",
            LlmProvider.XAI to "https://api.x.ai/v1/models",
            LlmProvider.MISTRAL to "https://api.mistral.ai/v1/models",
            LlmProvider.SILICONFLOW to "https://api.siliconflow.cn/v1/models",
            LlmProvider.DASHSCOPE to "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
            LlmProvider.MOONSHOT to "https://api.moonshot.cn/v1/models",
            LlmProvider.ZHIPU to "https://open.bigmodel.cn/api/paas/v4/models",
            LlmProvider.DOUBAO to "https://ark.cn-beijing.volces.com/api/v3/models",
        )
        expectedEndpoints.forEach { (provider, expectedUrl) ->
            val result = LlmProviderModelFetcher.fetchModels(
                provider,
                "sk-test",
                transport = transport,
            )
            assertTrue("Expected success for $provider", result.isSuccess)
            assertEquals(expectedUrl, transport.lastUrl)
            assertEquals("Bearer sk-test", transport.lastHeaders["Authorization"])
        }

        val customV1Result = LlmProviderModelFetcher.fetchModels(
            LlmProvider.CUSTOM,
            "sk-custom",
            customBaseUrl = "https://openrouter.ai/api/v1/",
            transport = transport,
        )
        assertTrue(customV1Result.isSuccess)
        assertEquals("https://openrouter.ai/api/v1/models", transport.lastUrl)

        val customRootResult = LlmProviderModelFetcher.fetchModels(
            LlmProvider.CUSTOM,
            "sk-custom",
            customBaseUrl = "https://example.com",
            transport = transport,
        )
        assertTrue(customRootResult.isSuccess)
        assertEquals("https://example.com/v1/models", transport.lastUrl)
    }

    @Test
    fun modelFetcher_returnsFailureWhenMissingKeyOrHttpError() = runBlocking {
        val transport = FakeTransport { _, _ ->
            ModelHttpResponse(
                statusCode = 401,
                body = """{"error":{"message":"Incorrect API key provided"}}""",
            )
        }

        val missingKey = LlmProviderModelFetcher.fetchModels(
            LlmProvider.OPENAI,
            "",
            transport = transport,
        )
        assertTrue(missingKey.isFailure)

        val missingCustomUrl = LlmProviderModelFetcher.fetchModels(
            LlmProvider.CUSTOM,
            "sk-custom",
            "",
            transport = transport,
        )
        assertTrue(missingCustomUrl.isFailure)

        val unauthorized = LlmProviderModelFetcher.fetchModels(
            LlmProvider.OPENAI,
            "sk-bad",
            transport = transport,
        )
        assertTrue(unauthorized.isFailure)
        assertTrue(unauthorized.exceptionOrNull()?.message.orEmpty().contains("401"))
        assertTrue(
            unauthorized.exceptionOrNull()?.message.orEmpty().contains("Incorrect API key provided"),
        )
    }
}
