package top.aaa000111.maid.provider

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.memory.MaidMemoryManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ReasoningContentSupportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    internal class SampleToolSet : ToolSet {
        @Tool
        @LLMDescription("Lookup device status")
        fun getDeviceStatus(): String = "ok"
    }

    private class TinyLocalHttpServer(
        private val handler: (body: String) -> Pair<Int, String>,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)

        val port: Int
            get() = serverSocket.localPort

        private val acceptThread = thread(isDaemon = true, name = "tiny-test-http") {
            while (running.get()) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    handleClient(socket)
                }
            }
        }

        private fun handleClient(socket: Socket) {
            socket.use { client ->
                val input = BufferedInputStream(client.getInputStream())
                val headerBytes = ByteArrayOutputStream()
                var matched = 0
                while (true) {
                    val b = input.read()
                    if (b == -1) return
                    headerBytes.write(b)
                    matched = when (matched) {
                        0 -> if (b == '\r'.code) 1 else 0
                        1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                        2 -> if (b == '\r'.code) 3 else 0
                        3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                        else -> 0
                    }
                    if (matched == 4) break
                }

                val headerText = headerBytes.toString(Charsets.UTF_8.name())
                val contentLength = headerText.lines()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0

                val bodyBytes = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val read = input.read(bodyBytes, offset, contentLength - offset)
                    if (read == -1) break
                    offset += read
                }
                val requestBody = bodyBytes.decodeToString(0, offset)
                val (statusCode, responseBody) = handler(requestBody)
                val statusText = if (statusCode == 200) "OK" else "Bad Request"
                val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
                val headers = buildString {
                    append("HTTP/1.1 $statusCode $statusText\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${responseBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)

                client.getOutputStream().apply {
                    write(headers)
                    write(responseBytes)
                    flush()
                }
            }
        }

        override fun close() {
            running.set(false)
            runCatching { serverSocket.close() }
            runCatching { acceptThread.join(1_000) }
        }
    }

    @Before
    fun setUp() {
        ReasoningContentSupport.sharedCache.clear()
        MaidMemoryManager.init(rootPath = tempFolder.root.toPath())
    }

    @After
    fun tearDown() {
        ReasoningContentSupport.sharedCache.clear()
        MaidMemoryManager.resetForTest()
    }

    @Test
    fun captureAndInject_preservesReasoningContentIncludingEmptyString_andSkipsNonThinking() {
        val cache = ReasoningContentCache(maxEntries = 8)
        val serverResponse = """
            {
              "id": "resp_1",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "",
                    "reasoning_content": "先检查设备状态，再决定下一步。",
                    "tool_calls": [
                      {
                        "id": "call_think_1",
                        "type": "function",
                        "function": { "name": "getDeviceStatus", "arguments": "{}" }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals(1, ReasoningContentSupport.captureReasoningFromResponse(serverResponse, cache))
        assertEquals("先检查设备状态，再决定下一步。", cache.get("call_think_1"))

        val outgoingRequest = """
            {
              "model": "glm-4.7",
              "messages": [
                {"role": "user", "content": "帮我看看状态"},
                {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [
                    {
                      "id": "call_think_1",
                      "type": "function",
                      "function": {"name": "getDeviceStatus", "arguments": "{}"}
                    }
                  ]
                },
                {"role": "tool", "tool_call_id": "call_think_1", "content": "ok"}
              ]
            }
        """.trimIndent()

        val rewritten = ReasoningContentSupport.injectReasoningIntoRequest(outgoingRequest, cache)
        assertNotNull(rewritten)
        val rewrittenObj = json.parseToJsonElement(rewritten!!) as JsonObject
        val assistantMsg = (rewrittenObj["messages"] as JsonArray)[1] as JsonObject
        assertEquals(
            "先检查设备状态，再决定下一步。",
            (assistantMsg["reasoning_content"] as JsonPrimitive).content,
        )

        // 空字符串 reasoning_content 同样必须回传
        val emptyReasoningResponse = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": "",
                  "reasoning_content": "",
                  "tool_calls": [{"id": "call_empty_1", "type": "function", "function": {"name": "x", "arguments": "{}"}}]
                }
              }]
            }
        """.trimIndent()
        ReasoningContentSupport.captureReasoningFromResponse(emptyReasoningResponse, cache)
        val emptyReq = """
            {"messages":[{"role":"assistant","tool_calls":[{"id":"call_empty_1","type":"function","function":{"name":"x","arguments":"{}"}}]}]}
        """.trimIndent()
        val rewrittenEmpty = ReasoningContentSupport.injectReasoningIntoRequest(emptyReq, cache)
        assertNotNull(rewrittenEmpty)
        val emptyAssistant = ((json.parseToJsonElement(rewrittenEmpty!!) as JsonObject)["messages"] as JsonArray)[0] as JsonObject
        assertEquals("", (emptyAssistant["reasoning_content"] as JsonPrimitive).content)

        // 未命中缓存的非思考模型请求不注入多余字段
        val nonThinkingReq = """
            {"messages":[{"role":"assistant","tool_calls":[{"id":"call_unknown","type":"function","function":{"name":"x","arguments":"{}"}}]}]}
        """.trimIndent()
        assertNull(ReasoningContentSupport.injectReasoningIntoRequest(nonThinkingReq, cache))
    }

    @Test
    fun openAiAndDeepSeekClients_passReasoningContentBackAcrossToolCallsAndMemoryExtraction() = runBlocking {
        val capturedRequests = CopyOnWriteArrayList<JsonObject>()
        val requestCounter = AtomicInteger(0)

        TinyLocalHttpServer { requestText ->
            val reqObj = json.parseToJsonElement(requestText) as JsonObject
            capturedRequests += reqObj

            val messages = reqObj["messages"] as JsonArray
            // 模拟严格思考模式接口校验：所有包含 tool_calls 的 assistant 消息必须带有 reasoning_content
            val missingReasoning = messages.any { elem ->
                val obj = elem as? JsonObject ?: return@any false
                val role = (obj["role"] as? JsonPrimitive)?.contentOrNull
                val toolCalls = obj["tool_calls"] as? JsonArray
                role == "assistant" && !toolCalls.isNullOrEmpty() && "reasoning_content" !in obj
            }

            if (missingReasoning) {
                return@TinyLocalHttpServer 400 to """
                    {"error":{"message":"The 'reasoning_content' in the thinking mode must be passed back to the API.","type":"invalid_request_error"}}
                """.trimIndent()
            }

            val step = requestCounter.incrementAndGet()
            val isMemoryPrompt = messages.any { elem ->
                val content = ((elem as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
                content.contains("Analyze the conversation history", ignoreCase = true)
            }

            val responseJson = when {
                isMemoryPrompt -> """
                    {
                      "id": "chatcmpl_mem",
                      "object": "chat.completion",
                      "created": 1700000003,
                      "model": "thinking-model",
                      "system_fingerprint": "fp",
                      "choices": [{
                        "index": 0,
                        "finish_reason": "stop",
                        "message": {"role": "assistant", "content": "[]", "reasoning_content": "无需保存额外事实"}
                      }]
                    }
                """.trimIndent()

                step == 1 -> """
                    {
                      "id": "chatcmpl_1",
                      "object": "chat.completion",
                      "created": 1700000001,
                      "model": "thinking-model",
                      "system_fingerprint": "fp",
                      "choices": [{
                        "index": 0,
                        "finish_reason": "tool_calls",
                        "message": {
                          "role": "assistant",
                          "content": "",
                          "reasoning_content": "需要先调用 getDeviceStatus 检查状态",
                          "tool_calls": [{
                            "id": "call_real_http_1",
                            "type": "function",
                            "function": {"name": "getDeviceStatus", "arguments": "{}"}
                          }]
                        }
                      }]
                    }
                """.trimIndent()

                else -> """
                    {
                      "id": "chatcmpl_2",
                      "object": "chat.completion",
                      "created": 1700000002,
                      "model": "thinking-model",
                      "system_fingerprint": "fp",
                      "choices": [{
                        "index": 0,
                        "finish_reason": "stop",
                        "message": {
                          "role": "assistant",
                          "content": "设备状态一切正常。",
                          "reasoning_content": "已确认设备正常，给出简洁结论。"
                        }
                      }]
                    }
                """.trimIndent()
            }
            200 to responseJson
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}"

            // 1. 验证 OpenAILLMClient（含 OpenAI 兼容服务端，如智谱/Kimi/豆包/百炼/硅基流动）
            val openAiClient = OpenAILLMClient(
                apiKey = "test-key",
                settings = OpenAIClientSettings(baseUrl = baseUrl),
                baseClient = ReasoningContentSupport.createReasoningAwareHttpClient(),
            )
            val openAiModel = LLModel(
                provider = LLMProvider.OpenAI,
                id = "thinking-model",
                capabilities = listOf(
                    LLMCapability.Completion,
                    LLMCapability.Temperature,
                    LLMCapability.Tools,
                    LLMCapability.OpenAIEndpoint.Completions,
                ),
                contextLength = 128_000L,
            )
            val agent1 = ChatAgent(
                promptExecutor = SingleLLMPromptExecutor(openAiClient),
                model = openAiModel,
                toolRegistry = ToolRegistry { tools(SampleToolSet()) },
                plannerLifecycleManager = null,
                enableMemory = true,
                autoSaveMemory = true,
            )
            val reply1 = agent1.chat("帮我检查下设备状态")
            assertEquals("设备状态一切正常。", reply1)
            assertEquals(3, capturedRequests.size)

            // 验证第 2 次（工具结果）请求已回传 reasoning_content
            val toolTurnMsgs = capturedRequests[1]["messages"] as JsonArray
            val assistantWithToolCall = toolTurnMsgs
                .filterIsInstance<JsonObject>()
                .first { obj ->
                    (obj["role"] as? JsonPrimitive)?.contentOrNull == "assistant" &&
                        (obj["tool_calls"] as? JsonArray)?.isNotEmpty() == true
                }
            assertEquals(
                "需要先调用 getDeviceStatus 检查状态",
                (assistantWithToolCall["reasoning_content"] as JsonPrimitive).content,
            )

            // 验证第 3 次（记忆抽取）若携带 tool_calls 也必须带 reasoning_content（且服务端未返回 400）
            val memoryTurnMsgs = capturedRequests[2]["messages"] as JsonArray
            assertTrue(
                memoryTurnMsgs.filterIsInstance<JsonObject>().none { obj ->
                    (obj["role"] as? JsonPrimitive)?.contentOrNull == "assistant" &&
                        (obj["tool_calls"] as? JsonArray)?.isNotEmpty() == true &&
                        "reasoning_content" !in obj
                },
            )

            // 2. 验证 DeepSeekLLMClient 同样通过 ReasoningContentPlugin 正常回传
            capturedRequests.clear()
            requestCounter.set(0)
            val deepSeekClient = DeepSeekLLMClient(
                apiKey = "test-key",
                settings = DeepSeekClientSettings(
                    baseUrl = baseUrl,
                    chatCompletionsPath = "v1/chat/completions",
                ),
                baseClient = ReasoningContentSupport.createReasoningAwareHttpClient(),
            )
            val deepSeekModel = LLModel(
                provider = LLMProvider.DeepSeek,
                id = "deepseek-reasoner",
                capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
                contextLength = 128_000L,
            )
            val agent2 = ChatAgent(
                promptExecutor = SingleLLMPromptExecutor(deepSeekClient),
                model = deepSeekModel,
                toolRegistry = ToolRegistry { tools(SampleToolSet()) },
                plannerLifecycleManager = null,
                enableMemory = false,
                autoSaveMemory = false,
            )
            val reply2 = agent2.chat("再次检查")
            assertEquals("设备状态一切正常。", reply2)
            assertEquals(2, capturedRequests.size)
            assertTrue(
                (capturedRequests[1]["messages"] as JsonArray)
                    .filterIsInstance<JsonObject>()
                    .any { obj ->
                        (obj["role"] as? JsonPrimitive)?.contentOrNull == "assistant" &&
                            (obj["reasoning_content"] as? JsonPrimitive)?.contentOrNull == "需要先调用 getDeviceStatus 检查状态"
                    },
            )
        }
    }
}
