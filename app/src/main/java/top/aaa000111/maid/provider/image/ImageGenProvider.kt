package top.aaa000111.maid.provider.image

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Dns
import top.aaa000111.maid.provider.LlmProvider

internal data class ImageGenRequest(
    val modelId: String,
    val prompt: String,
    val aspectRatio: String? = null,
)

internal data class GeneratedImage(val file: File, val mimeType: String)

internal class ImageGenProvider(
    private val provider: LlmProvider,
    private val endpoint: String,
    private val apiKey: String,
    private val tempDirectory: File,
    private val apiClient: HttpClient = ImageGenHttpClients.api,
    private val downloadClient: HttpClient = ImageGenHttpClients.download,
) {
    suspend fun generate(request: ImageGenRequest): GeneratedImage {
        val model = request.modelId.trim()
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,199}"))) { "Invalid image model ID" }
        require(request.prompt.isNotBlank() && request.prompt.length <= 12_000) { "Image prompt must be 1–12000 characters" }
        val ratio = request.aspectRatio?.trim()?.takeIf(String::isNotEmpty)
        require(ratio == null || ratio.matches(Regex("[1-9][0-9]*(\\.[0-9]+)?:[1-9][0-9]*(\\.[0-9]+)?"))) {
            "Use a width:height aspect ratio such as 1:1 or omit it"
        }
        val payload = when (provider) {
            LlmProvider.GEMINI -> geminiPayload(request.prompt, ratio)
            else -> imagePayload(model, request.prompt, ratio)
        }
        val url = when (provider) {
            LlmProvider.GEMINI -> {
                val geminiId = model.removePrefix("models/")
                require(geminiId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { "Invalid Gemini model ID" }
                "$endpoint/models/$geminiId:generateContent"
            }
            LlmProvider.OPENROUTER -> "$endpoint/images"
            else -> "$endpoint/images/generations"
        }

        val bytes = withTimeoutOrNull(180_000) {
            val raw = apiClient.preparePost(url) {
                if (provider == LlmProvider.GEMINI) header("x-goog-api-key", apiKey) else bearerAuth(apiKey)
                setBody(TextContent(payload.toString(), ContentType.Application.Json))
            }.execute { response ->
                check(response.status.isSuccess()) { "${provider.id} image generation failed (HTTP ${response.status.value})" }
                response.bodyAsChannel().readBounded(MAX_RESPONSE_BYTES).decodeToString()
            }
            val root = try {
                Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid image response")
            } catch (_: kotlinx.serialization.SerializationException) {
                error("Invalid image response")
            }
            if (provider == LlmProvider.GEMINI) {
                val image = root.objects("candidates").asSequence()
                    .flatMap { it.obj("content")?.objects("parts").orEmpty().asSequence() }
                    .filter { (it["thought"] as? JsonPrimitive)?.contentOrNull != "true" }
                    .mapNotNull { it.obj("inlineData")?.string("data") }
                    .firstOrNull()
                    ?: error("Gemini returned no generated image")
                decodeImage(image)
            } else {
                val image = root.objects("data").firstOrNull() ?: error("Provider returned no generated image")
                image.string("b64_json")?.let(::decodeImage)
                    ?: image.string("url")?.let { downloadImage(it) }
                    ?: error("Provider returned no image data")
            }
        } ?: error("Image generation timed out")

        val mimeType = imageMimeType(bytes)
        val extension = when (mimeType) {
            "image/jpeg" -> "jpg"
            else -> mimeType.substringAfter('/')
        }
        val file = File.createTempFile("maid_image_", ".$extension", tempDirectory)
        return try {
            file.writeBytes(bytes)
            GeneratedImage(file, mimeType)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private fun imagePayload(model: String, prompt: String, ratio: String?) = buildJsonObject {
        put("model", model)
        put("prompt", prompt)
        put("n", 1)
        if (ratio != null) {
            if (provider == LlmProvider.XAI || provider == LlmProvider.OPENROUTER) {
                put("aspect_ratio", ratio)
            } else {
                val size = when (ratio) {
                    "1:1" -> "1024x1024"
                    "3:2" -> "1536x1024"
                    "2:3" -> "1024x1536"
                    else -> error("OpenAI-compatible image ratios: 1:1, 3:2, 2:3")
                }
                put("size", size)
            }
        }
    }

    private fun geminiPayload(prompt: String, ratio: String?) = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
            })
        }
        putJsonObject("generationConfig") {
            putJsonArray("responseModalities") {
                add("TEXT")
                add("IMAGE")
            }
            if (ratio != null) putJsonObject("imageConfig") { put("aspectRatio", ratio) }
        }
    }

    private suspend fun downloadImage(rawUrl: String): ByteArray {
        val url = try { URI(rawUrl) } catch (_: Exception) { error("Invalid generated image URL") }
        val host = url.host.orEmpty().trimEnd('.')
        require(url.scheme.equals("https", ignoreCase = true) &&
            host.contains('.') && host.substringAfterLast('.').any(Char::isLetter) && !host.contains(':') &&
            url.userInfo == null && url.port in listOf(-1, 443)
        ) { "Generated image URL must use public HTTPS" }
        return downloadClient.prepareGet(url.toASCIIString()).execute { response ->
            check(response.status.isSuccess()) { "Image download failed (HTTP ${response.status.value})" }
            response.bodyAsChannel().readBounded(MAX_IMAGE_BYTES)
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 24 * 1024 * 1024

        fun decodeImage(data: String): ByteArray {
            require(data.length <= ((MAX_IMAGE_BYTES + 2) / 3) * 4) { "Generated image is too large" }
            return Base64.getDecoder().decode(data).also {
                require(it.size <= MAX_IMAGE_BYTES) { "Generated image is too large" }
            }
        }

        fun imageMimeType(bytes: ByteArray): String = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
            ) -> "image/png"
            bytes.size >= 3 && bytes[0] == (-1).toByte() &&
                bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "image/jpeg"
            bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" &&
                bytes.decodeToString(8, 12) == "WEBP" -> "image/webp"
            else -> error("Unsupported generated image format (expected PNG, JPEG or WebP)")
        }

        suspend fun io.ktor.utils.io.ByteReadChannel.readBounded(limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = readAvailable(buffer, 0, buffer.size)
                if (count == -1) break
                if (count > limit - output.size()) throw IOException("Image service response is too large")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        fun JsonObject.obj(key: String) = this[key] as? JsonObject
        fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}

private object ImageGenHttpClients {
    val api by lazy { imageHttpClient(publicDownload = false) }
    val download by lazy { imageHttpClient(publicDownload = true) }

    private fun imageHttpClient(publicDownload: Boolean) = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(175, TimeUnit.SECONDS)
                if (publicDownload) {
                    proxy(Proxy.NO_PROXY)
                    dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                            if (addresses.any { address ->
                                    val bytes = address.address
                                    val first = bytes[0].toInt() and 255
                                    val second = bytes[1].toInt() and 255
                                    address.isAnyLocalAddress || address.isLoopbackAddress ||
                                        address.isLinkLocalAddress || address.isSiteLocalAddress ||
                                        address.isMulticastAddress || if (bytes.size == 4) {
                                            first == 0 || first >= 224 || (first == 100 && second in 64..127)
                                        } else {
                                            first and 0xe0 != 0x20
                                        }
                                }
                            ) throw UnknownHostException("Image download target must be public")
                        }
                    })
                }
            }
        }
    }
}
