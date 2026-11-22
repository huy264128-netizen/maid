package top.aaa000111.maid.tinymodel

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.aaa000111.maid.agent.ProotLinuxAgentLifecycleManager
import top.aaa000111.maid.runtime.AppRuntime
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

@RunWith(AndroidJUnit4::class)
class TinyModelDeviceTest {
    @Test
    fun selectedModelGeneratesTextThroughLoopbackBridge() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AppRuntime.init(instrumentation.targetContext.applicationContext)
        AppRuntime.syncTinyModel()

        val env = withContext(Dispatchers.IO) { AppRuntime.getOrCreateTinyModelLocalBridge().buildEnvironmentVariables() }
        val health = request(env.getValue(TinyModelLocalBridge.ENV_HEALTH_URL))
        assertTrue("TinyModel is unavailable: $health", health.getValue("available").jsonPrimitive.boolean)

        val response = request(
            env.getValue(TinyModelLocalBridge.ENV_GENERATE_URL),
            """{"prompt":"Reply with only this word: READY","max_tokens":16,"temperature":0.0}""",
        )
        val answer = response.getValue("text").jsonPrimitive.content.trim()
        assertTrue("TinyModel generated no text: $response", answer.isNotEmpty())

        instrumentation.sendStatus(0, Bundle().apply {
            putString("tinymodel_health", health.toString())
            putString("tinymodel_generation", response.toString())
        })
    }

    @Test
    fun dedicatedProotTerminalStartsBridgeAndGeneratesText() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AppRuntime.init(instrumentation.targetContext.applicationContext)
        AppRuntime.syncTinyModel()

        ProotLinuxAgentLifecycleManager.default.use { manager ->
            val health = withContext(Dispatchers.IO) {
                manager.executeInDedicatedTerminal("maid-tiny --health", timeoutMillis = 30_000L)
            }
            assertTrue("PRoot health request failed: ${health.stderr.ifBlank { health.errorMessage.orEmpty() }}", health.isSuccess)
            val status = Json.parseToJsonElement(health.stdout.trim()).jsonObject
            assertTrue("PRoot TinyModel is unavailable: $status", status.getValue("available").jsonPrimitive.boolean)

            val generation = withContext(Dispatchers.IO) {
                manager.executeInDedicatedTerminal(
                    "maid-tiny --raw -m 16 -t 0 'Reply with only this word: READY'",
                    timeoutMillis = 100_000L,
                )
            }
            assertTrue(
                "PRoot generation failed: ${generation.errorMessage.orEmpty()} ${generation.stderr}",
                generation.isSuccess,
            )
            val response = Json.parseToJsonElement(generation.stdout.trim()).jsonObject
            assertTrue(response.getValue("text").jsonPrimitive.content.isNotBlank())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("proot_tinymodel_health", status.toString())
                putString("proot_tinymodel_generation", response.toString())
            })
        }
    }

    private suspend fun request(url: String, body: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val endpoint = URI(url)
            check(endpoint.host == "127.0.0.1") { "Expected a local bridge endpoint" }
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 5_000)
                socket.soTimeout = 100_000
                val payload = body?.toByteArray(Charsets.UTF_8)
                val headers = buildString {
                    append(if (payload == null) "GET " else "POST ")
                    append("${endpoint.rawPath} HTTP/1.1\r\n")
                    append("Host: ${endpoint.host}:${endpoint.port}\r\n")
                    append("Connection: close\r\n")
                    if (payload != null) {
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${payload.size}\r\n")
                    }
                    append("\r\n")
                }
                val output = socket.getOutputStream()
                output.write(headers.toByteArray(Charsets.UTF_8))
                if (payload != null) output.write(payload)
                output.flush()
                val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                val status = response.substringBefore("\r\n")
                val content = response.substringAfter("\r\n\r\n", "")
                check(status.startsWith("HTTP/1.1 200 ")) { "TinyModel bridge returned $status: $content" }
                Json.parseToJsonElement(content).jsonObject
            }
        }
}
