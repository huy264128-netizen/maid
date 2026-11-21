package top.aaa000111.maid.tinymodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.agent.ProotLinuxAgent
import top.aaa000111.maid.proot.TermuxProotRuntime
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class TinyModelLocalBridgeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun createTestTinyModel(
        id: String = "lfm2.5-350m-test",
        chunksProvider: (prompt: String, params: TinyModelConversationParams) -> List<String> = { prompt, params ->
            listOf("echo[sys=${params.systemInstruction}]: ", prompt)
        },
    ): TinyModel {
        val spec = TinyModelSpec(
            id = id,
            displayName = "Test TinyModel ($id)",
            assetPath = "models/$id.litertlm",
            fileName = "$id.litertlm",
            supportsGpu = false,
            maxContextTokens = 4096,
            defaultMaxNumTokens = 1024,
            isolateEnginePerConversation = false,
        )
        val modelBytes = "fake-litertlm-model-bytes-$id".toByteArray(StandardCharsets.UTF_8)
        val assetSource = object : TinyModelAssetSource {
            override fun list(dirPath: String): List<String> = listOf(spec.fileName)
            override fun exists(assetPath: String): Boolean = assetPath == spec.assetPath
            override fun open(assetPath: String): InputStream {
                if (assetPath != spec.assetPath) throw FileNotFoundException(assetPath)
                return ByteArrayInputStream(modelBytes)
            }
            override fun lengthOrNull(assetPath: String): Long? =
                if (assetPath == spec.assetPath) modelBytes.size.toLong() else null
        }
        val fileStore = TinyModelFileStore(
            filesDir = File(tempFolder.root, "files_$id"),
            cacheRootDir = File(tempFolder.root, "cache_$id"),
            assetSource = assetSource,
        )
        val backend = object : TinyModelRuntimeBackend {
            override fun createAndInitializeEngine(params: TinyModelEngineInitParams): TinyModelEngineHandle {
                return object : TinyModelEngineHandle {
                    override val modelPath: String = params.modelPath
                    override val activeBackend: TinyModelActiveBackend = params.backend
                    override fun createConversation(params: TinyModelConversationParams): TinyModelConversationHandle {
                        val capturedParams = params
                        return object : TinyModelConversationHandle {
                            override fun sendMessageAsync(
                                text: String,
                                maxOutputTokens: Int?,
                            ): Flow<String> = flow {
                                chunksProvider(text, capturedParams).forEach { emit(it) }
                            }
                            override fun cancelProcess() = Unit
                            override fun close() = Unit
                        }
                    }
                    override fun close() = Unit
                }
            }
        }
        return TinyModel(
            config = TinyModelConfig(spec = spec),
            fileStore = fileStore,
            runtimeBackend = backend,
        )
    }

    private data class HttpResp(
        val code: Int,
        val contentType: String,
        val body: String,
    )

    private fun httpRequest(
        method: String,
        url: String,
        jsonBody: String? = null,
    ): HttpResp {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 10_000
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        val contentType = conn.contentType.orEmpty()
        conn.disconnect()
        return HttpResp(code = code, contentType = contentType, body = body)
    }

    @Test
    fun bridgeServesHealthModelsAndGenerateWithoutAuthorization() {
        val model = createTestTinyModel()
        val bridge = TinyModelLocalBridge(
            tinyModelProvider = { model },
        ).ensureStarted()

        try {
            assertTrue(bridge.isRunning)
            val env = bridge.buildEnvironmentVariables()
            assertEquals("http://127.0.0.1:${bridge.port}/v1", env[TinyModelLocalBridge.ENV_BASE_URL])
            assertEquals("http://127.0.0.1:${bridge.port}/generate", env[TinyModelLocalBridge.ENV_GENERATE_URL])
            assertEquals("lfm2.5-350m-test", env[TinyModelLocalBridge.ENV_MODEL_NAME])

            // 1. /health
            val health = httpRequest("GET", bridge.healthUrl)
            assertEquals(200, health.code)
            val healthJson = json.parseToJsonElement(health.body).jsonObject
            assertEquals("ok", healthJson["status"]?.jsonPrimitive?.content)
            assertTrue(healthJson["available"]?.jsonPrimitive?.boolean == true)
            assertEquals("lfm2.5-350m-test", healthJson["model"]?.jsonPrimitive?.content)

            // 2. /v1/models
            val models = httpRequest("GET", "${bridge.baseUrl}/models")
            assertEquals(200, models.code)
            val modelsJson = json.parseToJsonElement(models.body).jsonObject
            assertEquals("list", modelsJson["object"]?.jsonPrimitive?.content)
            assertEquals(
                "lfm2.5-350m-test",
                modelsJson["data"]?.jsonArray?.first()?.jsonObject?.get("id")?.jsonPrimitive?.content,
            )

            // 3. POST /generate
            val genResp = httpRequest(
                method = "POST",
                url = bridge.generateUrl,
                jsonBody = """{"prompt":"hello proot","system":"be brief","max_tokens":128,"temperature":0.1}""",
            )
            assertEquals(200, genResp.code)
            val genJson = json.parseToJsonElement(genResp.body).jsonObject
            assertEquals("echo[sys=be brief]: hello proot", genJson["text"]?.jsonPrimitive?.content)
            assertEquals("lfm2.5-350m-test", genJson["model"]?.jsonPrimitive?.content)
        } finally {
            bridge.close()
            model.close()
        }
    }

    @Test
    fun chatCompletionsSupportsNonStreamingAndSseStreamingAndDynamicModelSwap() {
        val modelA = createTestTinyModel(
            id = "model-a",
            chunksProvider = { prompt, _ -> listOf("part1-", "part2:", prompt) },
        )
        val modelB = createTestTinyModel(
            id = "model-b",
            chunksProvider = { prompt, _ -> listOf("from-B:", prompt) },
        )
        var currentModel: TinyModel? = modelA

        val bridge = TinyModelLocalBridge(
            tinyModelProvider = { currentModel },
        ).ensureStarted()

        try {
            // 1. OpenAI 非流式（含 system 与多轮消息）
            val nonStream = httpRequest(
                method = "POST",
                url = "${bridge.baseUrl}/chat/completions",
                jsonBody = """
                    {
                      "model": "model-a",
                      "messages": [
                        {"role": "system", "content": "classify only"},
                        {"role": "user", "content": [{"type": "text", "text": "ping"}]}
                      ],
                      "max_tokens": 64
                    }
                """.trimIndent(),
            )
            assertEquals(200, nonStream.code)
            val completionObj = json.parseToJsonElement(nonStream.body).jsonObject
            assertEquals("chat.completion", completionObj["object"]?.jsonPrimitive?.content)
            assertEquals("model-a", completionObj["model"]?.jsonPrimitive?.content)
            val replyText = completionObj["choices"]!!.jsonArray[0].jsonObject["message"]!!
                .jsonObject["content"]!!.jsonPrimitive.content
            assertEquals("part1-part2:ping", replyText)

            // 2. OpenAI SSE 流式输出
            val streamed = httpRequest(
                method = "POST",
                url = "${bridge.baseUrl}/chat/completions",
                jsonBody = """
                    {
                      "messages": [{"role": "user", "content": "hi"}],
                      "stream": true
                    }
                """.trimIndent(),
            )
            assertEquals(200, streamed.code)
            assertTrue(streamed.contentType.contains("text/event-stream"))
            val dataLines = streamed.body.lines().filter { it.startsWith("data: ") }
            assertTrue(dataLines.last() == "data: [DONE]")
            val assembled = dataLines
                .dropLast(1)
                .mapNotNull { line ->
                    val chunkObj = json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                    val delta = chunkObj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                    delta?.get("content")?.jsonPrimitive?.content
                }
                .joinToString("")
            assertEquals("part1-part2:hi", assembled)

            // 3. 运行时切换 TinyModel 后无需重启桥接即生效
            currentModel = modelB
            val afterSwap = httpRequest(
                method = "POST",
                url = bridge.generateUrl,
                jsonBody = """{"prompt":"swapped"}""",
            )
            assertEquals(200, afterSwap.code)
            val swapJson = json.parseToJsonElement(afterSwap.body).jsonObject
            assertEquals("from-B:swapped", swapJson["text"]?.jsonPrimitive?.content)
            assertEquals("model-b", swapJson["model"]?.jsonPrimitive?.content)

            // 4. TinyModel 不可用时返回 503
            currentModel = null
            val unavailable = httpRequest(
                method = "POST",
                url = bridge.generateUrl,
                jsonBody = """{"prompt":"hello"}""",
            )
            assertEquals(503, unavailable.code)
            val healthUnavailable = httpRequest("GET", bridge.healthUrl)
            assertEquals(200, healthUnavailable.code)
            assertFalse(
                json.parseToJsonElement(healthUnavailable.body).jsonObject["available"]!!.jsonPrimitive.boolean,
            )
        } finally {
            bridge.close()
            modelA.close()
            modelB.close()
        }
    }

    @Test
    fun prootCliInstallAndAgentSystemPromptExposeUnconditionalTinyModelAccess() {
        val fakeRootfs = tempFolder.newFolder("fake_rootfs")
        TermuxProotRuntime.ensureMaidTinyCliInstalledLocked(fakeRootfs)

        val cliFile = File(fakeRootfs, "usr/local/bin/maid-tiny")
        assertTrue(cliFile.isFile)
        assertTrue(cliFile.canExecute())
        val cliContent = cliFile.readText(StandardCharsets.UTF_8)
        assertTrue(cliContent.contains(TermuxProotRuntime.MAID_TINY_CLI_VERSION_MARKER))
        assertTrue(cliContent.contains("MAID_TINY_MODEL_GENERATE_URL"))
        assertFalse(cliContent.contains("MAID_TINY_MODEL_API_KEY"))

        val prootPrompt = ProotLinuxAgent.buildEffectiveSystemPrompt(
            baseSystemPrompt = ProotLinuxAgent.DEFAULT_SYSTEM_PROMPT,
            sessionId = "s1",
            suggestedWorkingDirDisplay = "~/s1/",
            suggestedWorkingDirPosix = "/root/s1/",
            hasVisionTool = false,
            llmApiEnvPromptSection = null,
        )
        assertTrue(prootPrompt.contains("[内置离线端侧小模型（无需条件授权，开箱即用）]"))
        assertTrue(prootPrompt.contains("maid-tiny"))
        assertTrue(prootPrompt.contains("MAID_TINY_MODEL_BASE_URL"))

        // 在支持 /bin/sh 与 python3 的宿主机上直接端到端验证 maid-tiny CLI 脚本与本地桥接通信
        val shBin = File("/bin/sh")
        if (shBin.canExecute()) {
            val model = createTestTinyModel(
                id = "cli-live-test",
                chunksProvider = { prompt, params ->
                    listOf("CLI[${params.systemInstruction}] => $prompt")
                },
            )
            val bridge = TinyModelLocalBridge(
                tinyModelProvider = { model },
            ).ensureStarted()
            try {
                // 1. 语法检查 sh -n
                val syntaxProc = ProcessBuilder("/bin/sh", "-n", cliFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val syntaxOut = syntaxProc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                assertEquals("Shell syntax error: $syntaxOut", 0, syntaxProc.waitFor())

                // 2. 管道 + 参数 + -s 实际调用
                val runProc = ProcessBuilder(
                    cliFile.absolutePath,
                    "-s",
                    "summarize",
                    "-m",
                    "64",
                    "--stdin",
                    "header",
                ).apply {
                    environment().putAll(bridge.buildEnvironmentVariables())
                    redirectErrorStream(true)
                }.start()
                runProc.outputStream.use {
                    it.write("piped body\n".toByteArray(StandardCharsets.UTF_8))
                }
                val cliOutput = runProc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                assertEquals("CLI failed: $cliOutput", 0, runProc.waitFor())
                assertTrue("Unexpected CLI output: $cliOutput", cliOutput.contains("CLI[summarize] => header"))
                assertTrue("Unexpected CLI output: $cliOutput", cliOutput.contains("piped body"))

                // 3. --health 检查
                val healthProc = ProcessBuilder(cliFile.absolutePath, "--health").apply {
                    environment().putAll(bridge.buildEnvironmentVariables())
                    redirectErrorStream(true)
                }.start()
                val healthOut = healthProc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                assertEquals("Health failed: $healthOut", 0, healthProc.waitFor())
                assertTrue("Unexpected health output: $healthOut", healthOut.contains("\"available\":true"))
            } finally {
                bridge.close()
                model.close()
            }
        }
    }
}
