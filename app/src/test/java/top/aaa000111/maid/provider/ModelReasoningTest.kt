package top.aaa000111.maid.provider

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aaa000111.maid.runtime.AppRuntime

class ModelReasoningTest {
    private class RecordingExecutor : PromptExecutor {
        val prompts = mutableListOf<Prompt>()
        override suspend fun execute(
            prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>,
        ): List<Message.Response> {
            prompts += prompt
            return emptyList()
        }
        override fun executeStreaming(
            prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> {
            prompts += prompt
            return emptyFlow()
        }
        override suspend fun executeMultipleChoices(
            prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>,
        ): List<List<Message.Response>> {
            prompts += prompt
            return emptyList()
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("Not used")
    }

    private val testPrompt = prompt(
        "reasoning-test",
        params = LLMParams(
            temperature = 0.2,
            maxTokens = 100,
            additionalProperties = mapOf("custom_flag" to JsonPrimitive(true)),
        ),
    ) { user("Hello") }

    @After
    fun tearDown() {
        AppRuntime.resetForTest()
    }

    @Test
    fun allSelectedModelsAllowAnEffortOrAnUnsetValue() {
        val delegate = RecordingExecutor()
        assertEquals(ModelReasoningEffort.DEFAULT, ModelReasoningEffort.fromId(null))
        assertEquals(ModelReasoningEffort.DEFAULT, ModelReasoningEffort.fromId(""))
        assertEquals(ModelReasoningEffort.DEFAULT, ModelReasoningEffort.fromId("future-value"))
        for (provider in LlmProvider.entries) {
            val selection = RoleModelSelection(
                provider = provider,
                modelId = "model",
                modelMetadata = LlmModelMetadata(supportsReasoning = false),
            )
            assertTrue(ModelReasoningEffort.HIGH in selection.availableReasoningEfforts())
            assertTrue(ModelReasoningEffort.DEFAULT in selection.availableReasoningEfforts())
            assertSame(delegate, selection.withReasoning(delegate))
        }
        assertEquals(listOf(ModelReasoningEffort.DEFAULT), RoleModelSelection().availableReasoningEfforts())
    }

    @Test
    fun historyRoundTripOmitsClearedEffortAndStillReadsOldDefaults() {
        val selection = RoleModelSelection(LlmProvider.GROQ, "model", reasoningEffort = ModelReasoningEffort.HIGH)
        val clearing = selection.copy(reasoningEffort = ModelReasoningEffort.DEFAULT)
        val history = RoleModelHistory.remember(listOf(selection), clearing)
        val encoded = RoleModelHistory.encode(history)
        assertTrue("reasoning_effort" !in encoded)
        assertEquals(listOf(clearing), RoleModelHistory.decode(encoded))
        assertEquals(listOf(clearing), RoleModelHistory.decode(
            """[{"provider":"groq","model":"model","reasoning_effort":"default"}]""",
        ))
    }

    @Test
    fun runtimeAppliesRoleEffortWithoutContaminatingCachedExecutorOrFallbackRole() = runBlocking {
        val delegate = RecordingExecutor()
        var primaryReady = true
        val configs = mapOf(
            ModelRole.COMPLEX_PLANNING to RoleModelSelection(
                LlmProvider.OPENAI, "same-model", reasoningEffort = ModelReasoningEffort.HIGH,
            ),
            ModelRole.FAST_CHEAP to RoleModelSelection(
                LlmProvider.CUSTOM, "same-model", reasoningEffort = ModelReasoningEffort.LOW,
            ),
        )
        suspend fun resolve() = AppRuntime.resolveModelBinding(
            listOf(ModelRole.COMPLEX_PLANNING, ModelRole.FAST_CHEAP),
            configsLoader = { configs },
            availabilityChecker = { provider, _ ->
                if (provider == LlmProvider.OPENAI && !primaryReady) {
                    ProviderAvailability.Unavailable("not ready")
                } else ProviderAvailability.Ready
            },
            executorCreator = { _, _ -> delegate },
        )
        val high = resolve()
        high.executor.execute(testPrompt, high.model, emptyList())
        primaryReady = false
        val low = resolve()
        low.executor.executeStreaming(testPrompt, low.model, emptyList()).toList()
        low.executor.executeMultipleChoices(testPrompt, low.model, emptyList())
        assertEquals(listOf("high", "low", "low"), delegate.prompts.map {
            it.params.additionalProperties?.get("reasoning_effort")?.jsonPrimitive?.content
        })
        assertTrue(delegate.prompts.all { it.params.temperature == null && it.params.maxTokens == 100 })
        assertTrue(delegate.prompts.all { it.params.additionalProperties?.get("custom_flag") == JsonPrimitive(true) })
        assertEquals(0.2, testPrompt.params.temperature)
        assertNull(testPrompt.params.additionalProperties?.get("reasoning_effort"))
        assertSame(delegate, AppRuntime.getCachedRoleExecutor(ModelRole.FAST_CHEAP)?.executor)
    }

    @Test
    fun chatGptPayloadUsesNestedEffortForNormalAndStreamingCalls() = runBlocking {
        val delegate = RecordingExecutor()
        val selection = RoleModelSelection(
            LlmProvider.CHATGPT, "model", reasoningEffort = ModelReasoningEffort.XHIGH,
        )
        val model = LlmModelCatalog.resolveKoogModel(LlmProvider.CHATGPT, "model")
        val executor = selection.withReasoning(delegate)
        executor.execute(testPrompt, model, emptyList())
        executor.executeStreaming(testPrompt, model, emptyList()).toList()
        delegate.prompts.forEach { configured ->
            val payload = ChatGptResponsesPromptExecutor.buildRequestPayload(configured, model, emptyList())
            assertEquals("xhigh", payload["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
            assertNull(payload["reasoning_effort"])
            assertNull(payload["temperature"])
        }
        assertNull(ChatGptResponsesPromptExecutor.buildRequestPayload(
            testPrompt, model, emptyList(),
        )["reasoning"])
    }

    @Test
    fun openAiClientActuallySerializesEffortIntoHttpBody() = runBlocking {
        var captured: JsonObject? = null
        val http = HttpClient(MockEngine { request ->
            captured = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(
                """{"id":"test","object":"chat.completion","created":1,"model":"model",
                    "choices":[{"index":0,"finish_reason":"stop",
                    "message":{"role":"assistant","content":"OK"}}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ReasoningContentSupport.ReasoningContentPlugin)
        }
        try {
            for (provider in listOf(LlmProvider.OPENAI, LlmProvider.CUSTOM, LlmProvider.OPENROUTER)) {
                val client = if (provider == LlmProvider.OPENROUTER) {
                    OpenRouterLLMClient(apiKey = "test-key", baseClient = http)
                } else OpenAILLMClient(apiKey = "test-key", baseClient = http)
                val delegate = SingleLLMPromptExecutor(client)
                val selection = RoleModelSelection(
                    provider, "model", reasoningEffort = ModelReasoningEffort.XHIGH,
                )
                val model = LlmModelCatalog.resolveKoogModel(provider, "model")
                selection.withReasoning(delegate).execute(testPrompt, model, emptyList())
                val effort = if (provider == LlmProvider.OPENROUTER) {
                    captured?.get("reasoning")?.jsonObject?.get("effort")
                } else captured?.get("reasoning_effort")
                assertEquals(captured.toString(), "xhigh", effort?.jsonPrimitive?.content)
                assertNull(captured?.get("temperature"))
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun nativeProviderClientsSendEffortInTheirApiRequest() = runBlocking {
        var captured: JsonObject? = null
        val http = HttpClient(MockEngine { request ->
            captured = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(
                """{"error":{"message":"test request captured"}}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ReasoningContentSupport.ReasoningContentPlugin)
        }
        try {
            val clients = mapOf(
                LlmProvider.ANTHROPIC to LlmModelCatalog.createAnthropicClient(apiKey = "test-key", baseClient = http),
                LlmProvider.DEEPSEEK to DeepSeekLLMClient(apiKey = "test-key", baseClient = http),
                LlmProvider.GEMINI to GoogleLLMClient(apiKey = "test-key", baseClient = http),
            )
            for ((provider, client) in clients) {
                captured = null
                val modelId = when (provider) {
                    LlmProvider.ANTHROPIC -> "claude-sonnet-4-6"
                    LlmProvider.GEMINI -> "gemini-3.5-flash"
                    else -> "deepseek-chat"
                }
                val selection = RoleModelSelection(provider, modelId, reasoningEffort = ModelReasoningEffort.HIGH)
                val executor = selection.withReasoning(SingleLLMPromptExecutor(client))
                val model = LlmModelCatalog.resolveKoogModel(provider, modelId)
                val result = runCatching { executor.execute(testPrompt, model, emptyList()) }
                assertTrue(result.isFailure)
                val request = requireNotNull(captured) { "No request for $provider: ${result.exceptionOrNull()}" }
                val expected = if (provider == LlmProvider.GEMINI) "HIGH" else "high"
                val actual = when (provider) {
                    LlmProvider.ANTHROPIC -> request["output_config"]?.jsonObject?.get("effort")
                    LlmProvider.GEMINI -> request["generationConfig"]?.jsonObject
                        ?.get("thinkingConfig")?.jsonObject?.get("thinkingLevel")
                    else -> request["reasoning_effort"]
                }
                assertEquals(request.toString(), expected, actual?.jsonPrimitive?.content)
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun openRouterUsesNestedReasoningInsteadOfChatCompletionsField() = runBlocking {
        val delegate = RecordingExecutor()
        val selection = RoleModelSelection(
            LlmProvider.OPENROUTER, "provider/model", reasoningEffort = ModelReasoningEffort.LOW,
        )
        selection.withReasoning(delegate).execute(
            testPrompt, LlmModelCatalog.resolveKoogModel(LlmProvider.OPENROUTER, "provider/model"), emptyList(),
        )
        val properties = delegate.prompts.single().params.additionalProperties.orEmpty()
        assertEquals("low", properties["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertNull(properties["reasoning_effort"])
    }
}
