package top.aaa000111.maid.provider.image

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.provider.LlmProvider

class ImageGenProviderTest {
    @get:Rule val directory = TemporaryFolder()

    private val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aOasAAAAASUVORK5CYII="
    private val png = Base64.getDecoder().decode(pngBase64)

    @Test
    fun generationUsesEachProvidersRouteAuthAndPayload() = runBlocking {
        for ((provider, path) in mapOf(
            LlmProvider.OPENAI to "/v1/images/generations",
            LlmProvider.CUSTOM to "/v1/images/generations",
            LlmProvider.XAI to "/v1/images/generations",
            LlmProvider.OPENROUTER to "/v1/images",
            LlmProvider.GEMINI to "/v1/models/test-model:generateContent",
        )) {
            HttpClient(MockEngine { request ->
                assertEquals(path, request.url.encodedPath)
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                if (provider == LlmProvider.GEMINI) {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertEquals("fake-key", request.headers["x-goog-api-key"])
                    val config = payload.getValue("generationConfig").jsonObject
                    assertEquals("3:2", config.getValue("imageConfig").jsonObject.getValue("aspectRatio").jsonPrimitive.content)
                    respond("""{"candidates":[{"content":{"parts":[{"text":"caption"},{"inlineData":{"mimeType":"image/png","data":"$pngBase64"}}]}}]}""")
                } else {
                    assertEquals("Bearer fake-key", request.headers[HttpHeaders.Authorization])
                    assertEquals("test-model", payload.getValue("model").jsonPrimitive.content)
                    assertEquals("test prompt", payload.getValue("prompt").jsonPrimitive.content)
                    if (provider == LlmProvider.OPENAI || provider == LlmProvider.CUSTOM) {
                        assertEquals("1536x1024", payload.getValue("size").jsonPrimitive.content)
                    } else {
                        assertEquals("3:2", payload.getValue("aspect_ratio").jsonPrimitive.content)
                    }
                    respond("""{"data":[{"b64_json":"$pngBase64"}]}""")
                }
            }).use { client ->
                val image = provider(provider, client).generate(ImageGenRequest("test-model", "test prompt", "3:2"))
                assertEquals("image/png", image.mimeType)
                assertArrayEquals(png, image.file.readBytes())
            }
        }
    }

    @Test
    fun returnedImageUrlIsDownloadedWithoutProviderCredentials() = runBlocking {
        val api = HttpClient(MockEngine {
            respond("""{"data":[{"url":"https://cdn.example.com/generated.png"}]}""")
        })
        val download = HttpClient(MockEngine { request ->
            assertEquals("cdn.example.com", request.url.host)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["x-goog-api-key"])
            respond(ByteReadChannel(png))
        })
        api.use {
            download.use {
                val image = provider(LlmProvider.OPENAI, api, download).generate(ImageGenRequest("test-model", "test prompt"))
                assertArrayEquals(png, image.file.readBytes())
            }
        }
    }

    private fun provider(provider: LlmProvider, api: HttpClient, download: HttpClient = api) = ImageGenProvider(
        provider = provider,
        endpoint = "https://api.example.com/v1",
        apiKey = "fake-key",
        tempDirectory = directory.root,
        apiClient = api,
        downloadClient = download,
    )
}
