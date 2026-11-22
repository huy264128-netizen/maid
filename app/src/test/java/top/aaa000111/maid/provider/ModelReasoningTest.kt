package top.aaa000111.maid.provider

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
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
    fun defaultAndUnsupportedSelectionsKeepOriginalExecutor() {
        val delegate = RecordingExecutor()
        assertEquals(ModelReasoningEffort.DEFAULT, ModelReasoningEffort.fromId(null))
        assertEquals(ModelReasoningEffort.DEFAULT, ModelReasoningEffort.fromId("future-value"))
        val selection = RoleModelSelection(LlmProvider.OPENAI, "model")
        assertSame(delegate, selection.withReasoning(delegate))
        val unsupported = selection.copy(
            modelMetadata = LlmModelMetadata(supportsReasoning = false),
            reasoningEffort = ModelReasoningEffort.HIGH,
        )
        assertSame(delegate, unsupported.withReasoning(delegate))
        assertSame(delegate, selection.copy(
            provider = LlmProvider.GEMINI, reasoningEffort = ModelReasoningEffort.HIGH,
        ).withReasoning(delegate))
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
