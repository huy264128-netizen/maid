package top.aaa000111.maid.provider

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChatGptResponsesPromptExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun buildRequestPayload_enforcesPlanUsageConstraintsAndConvertsToolsAndImages() {
        val model = LlmModelCatalog.resolveKoogModel(LlmProvider.CHATGPT, "gpt-5.4")
        val testPrompt = prompt("test") {
            system("You are Maid.")
            message(
                Message.User(
                    content = "Describe this image",
                    metaInfo = RequestMetaInfo.Empty,
                    attachments = listOf(
                        Attachment.Image(
                            content = AttachmentContent.Binary.Base64("AQID"),
                            format = "png",
                            mimeType = "image/png",
                        ),
                    ),
                ),
            )
            message(
                Message.Tool.Call(
                    id = "call_1",
                    tool = "search_web",
                    content = """{"query":"openai"}""",
                    metaInfo = ResponseMetaInfo.Empty,
                ),
            )
            message(
                Message.Tool.Result(
                    id = "call_1",
                    tool = "search_web",
                    content = """{"results":["ok"]}""",
                    metaInfo = RequestMetaInfo.Empty,
                ),
            )
        }
        val tool = ToolDescriptor(
            name = "search_web",
            description = "Search the web",
            requiredParameters = listOf(
                ToolParameterDescriptor("query", "Search query", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("limit", "Result count", ToolParameterType.Integer),
            ),
        )

        val payload = ChatGptResponsesPromptExecutor.buildRequestPayload(
            prompt = testPrompt,
            model = model,
            tools = listOf(tool),
        )

        assertEquals("gpt-5.4", payload["model"]?.jsonPrimitive?.content)
        assertEquals(false, payload["store"]?.jsonPrimitive?.boolean)
        assertEquals(true, payload["stream"]?.jsonPrimitive?.boolean)
        assertEquals("You are Maid.", payload["instructions"]?.jsonPrimitive?.content)

        // 严禁携带不支持的参数
        assertNull(payload["temperature"])
        assertNull(payload["top_p"])
        assertNull(payload["max_output_tokens"])
        assertNull(payload["previous_response_id"])

        val input = payload["input"]!!.jsonArray
        assertEquals(3, input.size)
        // input 中绝不包含 role=system
        assertTrue(input.none { it.jsonObject["role"]?.jsonPrimitive?.content == "system" })

        val userContent = input[0].jsonObject["content"]!!.jsonArray
        assertEquals("input_text", userContent[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("input_image", userContent[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/png;base64,AQID",
            userContent[1].jsonObject["image_url"]?.jsonPrimitive?.content,
        )

        assertEquals("function_call", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[1].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[2].jsonObject["call_id"]?.jsonPrimitive?.content)

        val toolsArray = payload["tools"]!!.jsonArray
        assertEquals(1, toolsArray.size)
        assertEquals("function", toolsArray[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("search_web", toolsArray[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun executeStreamingAndExecute_parsesTextDeltasToolCallsAndUsage() = runBlocking {
        val sseBody = listOf(
            """event: response.output_text.delta""",
            """data: {"type":"response.output_text.delta","delta":"Hello "}""",
            "",
            """event: response.output_text.delta""",
            """data: {"type":"response.output_text.delta","delta":"Maid!"}""",
            "",
            """event: response.output_item.added""",
            """data: {"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_abc","name":"get_time","arguments":""}}""",
            "",
            """event: response.function_call_arguments.delta""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"tz\":"}""",
            "",
            """event: response.function_call_arguments.delta""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"UTC\"}"}""",
            "",
            """event: response.output_item.done""",
            """data: {"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","call_id":"call_abc","name":"get_time","arguments":"{\"tz\":\"UTC\"}"}}""",
            "",
            """event: response.completed""",
            """data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":12,"output_tokens":8,"total_tokens":20}}}""",
            "",
        ).joinToString("\n")

        var capturedAuth: String? = null
        var capturedRequestJson: String? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    capturedRequestJson = (request.body as? TextContent)?.text
                    respond(
                        content = sseBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                }
            }
        }

        val executor = ChatGptResponsesPromptExecutor(
            accessTokenProvider = { "oauth_access_token_xyz" },
            httpClient = client,
        )
        val model = LlmModelCatalog.resolveKoogModel(LlmProvider.CHATGPT, "gpt-5.4")
        val reqPrompt = prompt("chat") {
            system("Be helpful.")
            user("What time is it?")
        }

        val frames = executor.executeStreaming(reqPrompt, model, emptyList()).toList()
        assertEquals("Bearer oauth_access_token_xyz", capturedAuth)
        val parsedReq = json.parseToJsonElement(capturedRequestJson!!).jsonObject
        assertFalse(parsedReq["store"]!!.jsonPrimitive.boolean)
        assertTrue(parsedReq["stream"]!!.jsonPrimitive.boolean)

        val appends = frames.filterIsInstance<StreamFrame.Append>().joinToString("") { it.text }
        assertEquals("Hello Maid!", appends)
        val toolCallFrame = frames.filterIsInstance<StreamFrame.ToolCall>().single()
        assertEquals("call_abc", toolCallFrame.id)
        assertEquals("get_time", toolCallFrame.name)
        assertEquals("""{"tz":"UTC"}""", toolCallFrame.content)
        val endFrame = frames.filterIsInstance<StreamFrame.End>().single()
        assertEquals(20, endFrame.metaInfo.totalTokensCount)

        val responses = executor.execute(reqPrompt, model, emptyList())
        assertEquals(2, responses.size)
        assertEquals("Hello Maid!", (responses[0] as Message.Assistant).content)
        assertEquals("get_time", (responses[1] as Message.Tool.Call).tool)
    }

    @Test
    fun execute_retriesOnceOn401WithForcedRefresh() = runBlocking {
        val attempts = AtomicInteger(0)
        val forceRefreshCalls = AtomicInteger(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val current = attempts.incrementAndGet()
                    if (current == 1) {
                        assertEquals("Bearer stale_token", request.headers[HttpHeaders.Authorization])
                        respond(
                            content = """{"error":{"code":"invalid_token","message":"expired"}}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        assertEquals("Bearer fresh_token", request.headers[HttpHeaders.Authorization])
                        respond(
                            content = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Recovered\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                        )
                    }
                }
            }
        }

        val executor = ChatGptResponsesPromptExecutor(
            accessTokenProvider = { forceRefresh ->
                if (forceRefresh) {
                    forceRefreshCalls.incrementAndGet()
                    "fresh_token"
                } else {
                    "stale_token"
                }
            },
            httpClient = client,
        )
        val model = LlmModelCatalog.resolveKoogModel(LlmProvider.CHATGPT, "gpt-5.4")
        val responses = executor.execute(prompt("p") { user("hi") }, model, emptyList())

        assertEquals(2, attempts.get())
        assertEquals(1, forceRefreshCalls.get())
        assertEquals("Recovered", (responses.single() as Message.Assistant).content)
    }

    @Test
    fun execute_mapsQuotaExceededSseFailureToFriendlyError() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = """
                            event: response.failed
                            data: {"type":"response.failed","response":{"status":"failed","error":{"code":"subscription_sharing_usage_limit_exceeded","message":"Monthly quota exceeded"}}}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                }
            }
        }
        val executor = ChatGptResponsesPromptExecutor(
            accessTokenProvider = { "valid_token" },
            httpClient = client,
        )
        val model = LlmModelCatalog.resolveKoogModel(LlmProvider.CHATGPT, "gpt-5.4")

        try {
            executor.execute(prompt("p") { user("hi") }, model, emptyList())
            fail("Expected ChatGptApiException")
        } catch (e: ChatGptApiException) {
            assertEquals("subscription_sharing_usage_limit_exceeded", e.errorCode)
            assertTrue(e.message!!.contains("订阅额度已达上限"))
        }
    }

    @Test
    fun modelFetcher_fetchesChatGptModelsWithSlugVisibilityFilterAnd401Retry() = runBlocking {
        val callCount = AtomicInteger(0)
        val transport = object : ModelHttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>): ModelHttpResponse {
                assertEquals("https://api.openai.com/v1/models", url)
                val count = callCount.incrementAndGet()
                return if (count == 1) {
                    assertEquals("Bearer old_tok", headers["Authorization"])
                    ModelHttpResponse(401, """{"error":{"message":"Unauthorized"}}""")
                } else {
                    assertEquals("Bearer new_tok", headers["Authorization"])
                    ModelHttpResponse(
                        200,
                        """
                        {
                          "models": [
                            {"slug":"gpt-5.4","visibility":"list","display_name":"GPT-5.4"},
                            {"slug":"gpt-5.4-mini","visibility":"list","display_name":"GPT-5.4 Mini"},
                            {"slug":"internal-hidden","visibility":"hide","display_name":"Hidden"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }
            }
        }

        val result = LlmProviderModelFetcher.fetchModels(
            provider = LlmProvider.CHATGPT,
            apiKey = null,
            transport = transport,
            chatGptAccessTokenProvider = { force -> if (force) "new_tok" else "old_tok" },
        )

        assertTrue(result.isSuccess)
        assertEquals(2, callCount.get())
        val models = result.getOrThrow()
        assertEquals(listOf("gpt-5.4", "gpt-5.4-mini"), models.map { it.modelId })
        assertEquals(listOf("GPT-5.4", "GPT-5.4 Mini"), models.map { it.displayName })
        assertTrue(models.all { it.provider == LlmProvider.CHATGPT })
    }

    @Test
    fun executeStreaming_emitsReasoningSummaryDeltasAndCompletedItemFallback() = runBlocking {
        val sseWithDelta = listOf(
            """event: response.reasoning_summary_text.delta""",
            """data: {"type":"response.reasoning_summary_text.delta","delta":"Step 1. "}""",
            "",
            """event: response.reasoning_summary_text.delta""",
            """data: {"type":"response.reasoning_summary_text.delta","delta":"Step 2."}""",
            "",
            """event: response.reasoning_summary_text.done""",
            """data: {"type":"response.reasoning_summary_text.done","text":"Step 1. Step 2."}""",
            "",
            """event: response.output_text.delta""",
            """data: {"type":"response.output_text.delta","delta":"Final answer."}""",
            "",
            """event: response.completed""",
            """data: {"type":"response.completed","response":{"status":"completed"}}""",
            "",
        ).joinToString("\n")

        val sseWithDoneOnly = listOf(
            """event: response.output_item.done""",
            """data: {"type":"response.output_item.done","item":{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"Summary from done item"}]}}""",
            "",
            """event: response.output_text.delta""",
            """data: {"type":"response.output_text.delta","delta":"Done."}""",
            "",
            """event: response.completed""",
            """data: {"type":"response.completed","response":{"status":"completed"}}""",
            "",
        ).joinToString("\n")

        val callIndex = AtomicInteger(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    val body = if (callIndex.getAndIncrement() == 0) sseWithDelta else sseWithDoneOnly
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                }
            }
        }

        val reasoningChunks = mutableListOf<String>()
        val executor = ChatGptResponsesPromptExecutor(
            accessTokenProvider = { "valid_token" },
            httpClient = client,
            onReasoningSummaryDelta = { chunk -> reasoningChunks += chunk },
        )
        val model = LlmModelCatalog.resolveKoogModel(LlmProvider.CHATGPT, "gpt-5.4")
        val reqPrompt = prompt("reasoning") { user("Think about this") }

        val frames1 = executor.executeStreaming(reqPrompt, model, emptyList()).toList()
        assertEquals("Final answer.", frames1.filterIsInstance<StreamFrame.Append>().joinToString("") { it.text })
        assertEquals("Step 1. Step 2.", reasoningChunks.joinToString(""))

        reasoningChunks.clear()
        val frames2 = executor.executeStreaming(reqPrompt, model, emptyList()).toList()
        assertEquals("Done.", frames2.filterIsInstance<StreamFrame.Append>().joinToString("") { it.text })
        assertEquals("Summary from done item", reasoningChunks.joinToString(""))
    }
}
