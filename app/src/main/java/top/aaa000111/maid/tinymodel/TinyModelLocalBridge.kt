package top.aaa000111.maid.tinymodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import top.aaa000111.maid.runtime.AppRuntime
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 面向本机 PRoot Linux 容器的端侧 [TinyModel] 轻量本地 HTTP 桥接服务：
 * - 仅绑定 `127.0.0.1` 随机空闲端口，零额外第三方服务端依赖；
 * - 本机客户端直接通过 HTTP 调用；
 * - 每次请求实时读取 [tinyModelProvider]（默认 [AppRuntime.tinyModel]），在设置中切换小模型后无需重启 PRoot 即生效；
 * - 暴露 OpenAI 兼容端点（`GET /v1/models`、`POST /v1/chat/completions` 含非流式与 SSE 流式）、
 *   精简生成端点（`POST /generate`）与健康检查端点（`GET /health`）。
 */
class TinyModelLocalBridge(
    private val tinyModelProvider: () -> TinyModel? = { AppRuntime.tinyModel },
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : AutoCloseable {

    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var requestScope: CoroutineScope? = null

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean
        get() = !closed.get() && serverSocket?.isClosed == false && port > 0

    val baseUrl: String
        get() = if (port > 0) "http://127.0.0.1:$port/v1" else ""

    val generateUrl: String
        get() = if (port > 0) "http://127.0.0.1:$port/generate" else ""

    val healthUrl: String
        get() = if (port > 0) "http://127.0.0.1:$port/health" else ""

    /**
     * 幂等启动本地 HTTP 桥接服务；若已启动则直接返回。
     */
    fun ensureStarted(): TinyModelLocalBridge {
        if (isRunning) return this
        synchronized(lifecycleLock) {
            check(!closed.get()) { "TinyModelLocalBridge is already closed" }
            if (isRunning) return this

            val loopback = InetAddress.getByName("127.0.0.1")
            val boundSocket = ServerSocket(0, 50, loopback)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            serverSocket = boundSocket
            port = boundSocket.localPort
            requestScope = scope

            val thread = Thread({
                acceptLoop(boundSocket, scope)
            }, "maid-tiny-bridge-accept").apply {
                isDaemon = true
                start()
            }
            acceptThread = thread
        }
        return this
    }

    /**
     * 构造注入到 PRoot Linux 容器默认环境中的端侧小模型桥接环境变量（无需任何条件授权）。
     */
    fun buildEnvironmentVariables(): Map<String, String> {
        ensureStarted()
        val modelId = tinyModelProvider()?.spec?.id ?: TinyModelCatalog.DEFAULT.id
        return linkedMapOf(
            ENV_BASE_URL to baseUrl,
            ENV_GENERATE_URL to generateUrl,
            ENV_HEALTH_URL to healthUrl,
            ENV_MODEL_NAME to modelId,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lifecycleLock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            requestScope?.cancel()
            requestScope = null
            acceptThread?.interrupt()
            acceptThread = null
            port = 0
        }
    }

    private fun acceptLoop(
        server: ServerSocket,
        scope: CoroutineScope,
    ) {
        while (!closed.get() && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (closed.get() || server.isClosed) break
                continue
            }

            scope.launch {
                handleClient(client)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { conn ->
            conn.soTimeout = SOCKET_READ_TIMEOUT_MS
            val input = BufferedInputStream(conn.getInputStream())
            val output = BufferedOutputStream(conn.getOutputStream())
            try {
                val request = readHttpRequest(input) ?: return
                when {
                    request.method == "GET" && request.path == "/health" -> {
                        handleHealth(output)
                    }

                    request.method == "GET" && request.path == "/v1/models" -> {
                        handleListModels(output)
                    }

                    request.method == "POST" && request.path == "/generate" -> {
                        handleSimpleGenerate(request.body, output)
                    }

                    request.method == "POST" && request.path == "/v1/chat/completions" -> {
                        handleChatCompletions(request.body, output)
                    }

                    request.method == "OPTIONS" -> {
                        writeNoContentResponse(output)
                    }

                    else -> {
                        writeOpenAiError(
                            output = output,
                            statusCode = 404,
                            statusText = "Not Found",
                            message = "Unsupported endpoint: ${request.method} ${request.path}",
                            type = "invalid_request_error",
                        )
                    }
                }
            } catch (e: HttpRequestParseException) {
                runCatching {
                    writeOpenAiError(
                        output = output,
                        statusCode = e.statusCode,
                        statusText = e.statusText,
                        message = e.message ?: e.statusText,
                        type = "invalid_request_error",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // 客户端主动断开连接，静默结束
            } catch (e: Throwable) {
                runCatching {
                    writeOpenAiError(
                        output = output,
                        statusCode = 500,
                        statusText = "Internal Server Error",
                        message = e.message ?: (e::class.simpleName ?: "Internal server error"),
                        type = "server_error",
                    )
                }
            }
        }
    }

    private fun handleHealth(output: OutputStream) {
        val tinyModel = tinyModelProvider()
        val available = tinyModel?.isModelAvailable() == true
        val payload = buildJsonObject {
            put("status", "ok")
            put("available", available)
            put("model", tinyModel?.spec?.id ?: TinyModelCatalog.DEFAULT.id)
            put("display_name", tinyModel?.spec?.displayName ?: TinyModelCatalog.DEFAULT.displayName)
            put("state", tinyModel?.state?.value?.let { it::class.simpleName } ?: "Unavailable")
        }
        writeJsonResponse(output, 200, "OK", payload.toString())
    }

    private fun handleListModels(output: OutputStream) {
        val tinyModel = tinyModelProvider()
        val modelId = tinyModel?.spec?.id ?: TinyModelCatalog.DEFAULT.id
        val createdSec = System.currentTimeMillis() / 1000L
        val payload = buildJsonObject {
            put("object", "list")
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", modelId)
                            put("object", "model")
                            put("created", createdSec)
                            put("owned_by", "maid-local")
                            put("available", tinyModel?.isModelAvailable() == true)
                        },
                    )
                },
            )
        }
        writeJsonResponse(output, 200, "OK", payload.toString())
    }

    private suspend fun handleSimpleGenerate(
        rawBody: String,
        output: OutputStream,
    ) {
        val jsonObj = parseJsonObject(rawBody) ?: run {
            writeOpenAiError(
                output = output,
                statusCode = 400,
                statusText = "Bad Request",
                message = "Request body must be a valid JSON object.",
                type = "invalid_request_error",
            )
            return
        }

        val prompt = jsonObj["prompt"]?.asStringOrNull()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            writeOpenAiError(
                output = output,
                statusCode = 400,
                statusText = "Bad Request",
                message = "Field 'prompt' must be a non-empty string.",
                type = "invalid_request_error",
            )
            return
        }

        val systemPrompt = (jsonObj["system"] ?: jsonObj["system_prompt"])
            ?.asStringOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val tinyModel = requireAvailableTinyModel(output) ?: return
        val maxOutputTokens = resolveMaxOutputTokens(jsonObj)
        val sampler = resolveSampler(jsonObj, tinyModel.config.defaultSampler)

        val result = try {
            withTimeout(requestTimeoutMillis.milliseconds) {
                tinyModel.generate(
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    maxOutputTokens = maxOutputTokens,
                    sampler = sampler,
                )
            }
        } catch (_: TimeoutCancellationException) {
            tinyModel.cancelCurrent()
            writeOpenAiError(
                output = output,
                statusCode = 504,
                statusText = "Gateway Timeout",
                message = "TinyModel generation timed out after ${requestTimeoutMillis}ms.",
                type = "timeout_error",
            )
            return
        }

        val responseJson = buildJsonObject {
            put("text", result.text)
            put("model", result.modelId)
            put("backend", result.activeBackend.name)
            put("duration_ms", result.durationMs)
        }
        writeJsonResponse(output, 200, "OK", responseJson.toString())
    }

    private suspend fun handleChatCompletions(
        rawBody: String,
        output: OutputStream,
    ) {
        val jsonObj = parseJsonObject(rawBody) ?: run {
            writeOpenAiError(
                output = output,
                statusCode = 400,
                statusText = "Bad Request",
                message = "Request body must be a valid JSON object.",
                type = "invalid_request_error",
            )
            return
        }

        val messagesElement = jsonObj["messages"] as? JsonArray
        if (messagesElement == null || messagesElement.isEmpty()) {
            writeOpenAiError(
                output = output,
                statusCode = 400,
                statusText = "Bad Request",
                message = "Field 'messages' must be a non-empty JSON array.",
                type = "invalid_request_error",
            )
            return
        }

        val extracted = extractPromptAndSystemFromMessages(messagesElement)
        if (extracted.prompt.isBlank()) {
            writeOpenAiError(
                output = output,
                statusCode = 400,
                statusText = "Bad Request",
                message = "At least one non-empty non-system message is required.",
                type = "invalid_request_error",
            )
            return
        }

        val tinyModel = requireAvailableTinyModel(output) ?: return
        val maxOutputTokens = resolveMaxOutputTokens(jsonObj)
        val sampler = resolveSampler(jsonObj, tinyModel.config.defaultSampler)
        val stream = (jsonObj["stream"] as? JsonPrimitive)?.booleanOrNull == true
        val completionId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val createdSec = System.currentTimeMillis() / 1000L
        val modelId = tinyModel.spec.id

        if (!stream) {
            val result = try {
                withTimeout(requestTimeoutMillis.milliseconds) {
                    tinyModel.generate(
                        prompt = extracted.prompt,
                        systemPrompt = extracted.systemPrompt,
                        maxOutputTokens = maxOutputTokens,
                        sampler = sampler,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                tinyModel.cancelCurrent()
                writeOpenAiError(
                    output = output,
                    statusCode = 504,
                    statusText = "Gateway Timeout",
                    message = "TinyModel generation timed out after ${requestTimeoutMillis}ms.",
                    type = "timeout_error",
                )
                return
            }

            val payload = buildJsonObject {
                put("id", completionId)
                put("object", "chat.completion")
                put("created", createdSec)
                put("model", result.modelId)
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", 0)
                                put(
                                    "message",
                                    buildJsonObject {
                                        put("role", "assistant")
                                        put("content", result.text)
                                    },
                                )
                                put("finish_reason", "stop")
                            },
                        )
                    },
                )
            }
            writeJsonResponse(output, 200, "OK", payload.toString())
            return
        }

        // OpenAI SSE streaming response
        writeSseHeaders(output)
        writeSseEvent(
            output = output,
            data = buildStreamChunkJson(
                id = completionId,
                createdSec = createdSec,
                modelId = modelId,
                role = "assistant",
                content = "",
                finishReason = null,
            ),
        )

        try {
            withTimeout(requestTimeoutMillis.milliseconds) {
                tinyModel.generateStream(
                    prompt = extracted.prompt,
                    systemPrompt = extracted.systemPrompt,
                    maxOutputTokens = maxOutputTokens,
                    sampler = sampler,
                ).collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        writeSseEvent(
                            output = output,
                            data = buildStreamChunkJson(
                                id = completionId,
                                createdSec = createdSec,
                                modelId = modelId,
                                role = null,
                                content = chunk,
                                finishReason = null,
                            ),
                        )
                    }
                }
            }
            writeSseEvent(
                output = output,
                data = buildStreamChunkJson(
                    id = completionId,
                    createdSec = createdSec,
                    modelId = modelId,
                    role = null,
                    content = null,
                    finishReason = "stop",
                ),
            )
            writeSseEvent(output = output, data = "[DONE]")
        } catch (_: TimeoutCancellationException) {
            tinyModel.cancelCurrent()
            runCatching {
                writeSseEvent(
                    output = output,
                    data = buildStreamChunkJson(
                        id = completionId,
                        createdSec = createdSec,
                        modelId = modelId,
                        role = null,
                        content = null,
                        finishReason = "length",
                    ),
                )
                writeSseEvent(output = output, data = "[DONE]")
            }
        } catch (e: IOException) {
            tinyModel.cancelCurrent()
            throw e
        }
    }

    private fun requireAvailableTinyModel(output: OutputStream): TinyModel? {
        val tinyModel = tinyModelProvider()
        if (tinyModel == null || !tinyModel.isModelAvailable()) {
            writeOpenAiError(
                output = output,
                statusCode = 503,
                statusText = "Service Unavailable",
                message = "On-device TinyModel is not available on this device.",
                type = "service_unavailable",
            )
            return null
        }
        return tinyModel
    }

    private data class ParsedHttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private class HttpRequestParseException(
        val statusCode: Int,
        val statusText: String,
        message: String,
    ) : Exception(message)

    private fun readHttpRequest(input: BufferedInputStream): ParsedHttpRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            throw HttpRequestParseException(400, "Bad Request", "Malformed HTTP request line")
        }
        val method = parts[0].trim().uppercase()
        val rawTarget = parts[1].trim()
        val path = rawTarget.substringBefore('?').ifEmpty { "/" }

        val headers = LinkedHashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readAsciiLine(input)
                ?: throw HttpRequestParseException(400, "Bad Request", "Unexpected EOF in HTTP headers")
            if (line.isEmpty()) break
            headerBytes += line.length + 2
            if (headerBytes > MAX_TOTAL_HEADER_BYTES) {
                throw HttpRequestParseException(431, "Request Header Fields Too Large", "HTTP headers too large")
            }
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }

        val transferEncoding = headers["transfer-encoding"].orEmpty().lowercase()
        val bodyBytes = when {
            transferEncoding.contains("chunked") -> readChunkedBody(input)
            headers.containsKey("content-length") -> {
                val contentLength = headers["content-length"]?.toIntOrNull()
                    ?: throw HttpRequestParseException(400, "Bad Request", "Invalid Content-Length header")
                if (contentLength < 0) {
                    throw HttpRequestParseException(400, "Bad Request", "Negative Content-Length header")
                }
                if (contentLength > MAX_BODY_BYTES) {
                    throw HttpRequestParseException(413, "Payload Too Large", "Request body exceeds $MAX_BODY_BYTES bytes")
                }
                readExactBytes(input, contentLength)
            }
            else -> ByteArray(0)
        }

        return ParsedHttpRequest(
            method = method,
            path = path,
            body = bodyBytes.toString(StandardCharsets.UTF_8),
        )
    }

    private fun readAsciiLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawAny) baos.toString(StandardCharsets.ISO_8859_1.name()) else null
            }
            sawAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                if (baos.size() >= MAX_HEADER_LINE_BYTES) {
                    throw HttpRequestParseException(431, "Request Header Fields Too Large", "Header line too long")
                }
                baos.write(b)
            }
        }
        return baos.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) {
                throw EOFException("Unexpected EOF while reading HTTP body")
            }
            offset += read
        }
        return buffer
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input)
                ?: throw EOFException("Unexpected EOF while reading chunk size")
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw HttpRequestParseException(400, "Bad Request", "Invalid chunked encoding size")
            if (chunkSize == 0) {
                // Consume trailing headers until empty line
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (out.size() + chunkSize > MAX_BODY_BYTES) {
                throw HttpRequestParseException(413, "Payload Too Large", "Chunked body exceeds $MAX_BODY_BYTES bytes")
            }
            val chunk = readExactBytes(input, chunkSize)
            out.write(chunk)
            // Consume CRLF after chunk
            readAsciiLine(input)
        }
        return out.toByteArray()
    }

    private fun writeJsonResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        jsonBody: String,
    ) {
        val bodyBytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(header)
        output.write(bodyBytes)
        output.flush()
    }

    private fun writeNoContentResponse(output: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(header)
        output.flush()
    }

    private fun writeOpenAiError(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        message: String,
        type: String,
    ) {
        val payload = buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    put("message", message)
                    put("type", type)
                },
            )
        }
        writeJsonResponse(output, statusCode, statusText, payload.toString())
    }

    private fun writeSseHeaders(output: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(header)
        output.flush()
    }

    private fun writeSseEvent(output: OutputStream, data: String) {
        val frame = "data: $data\n\n".toByteArray(StandardCharsets.UTF_8)
        output.write(frame)
        output.flush()
    }

    private fun buildStreamChunkJson(
        id: String,
        createdSec: Long,
        modelId: String,
        role: String?,
        content: String?,
        finishReason: String?,
    ): String = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", createdSec)
        put("model", modelId)
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put(
                            "delta",
                            buildJsonObject {
                                if (role != null) put("role", role)
                                if (content != null) put("content", content)
                            },
                        )
                        if (finishReason != null) {
                            put("finish_reason", finishReason)
                        } else {
                            put("finish_reason", JsonPrimitive(null as String?))
                        }
                    },
                )
            },
        )
    }.toString()

    internal data class ExtractedChatPrompt(
        val systemPrompt: String?,
        val prompt: String,
    )

    companion object {
        const val ENV_BASE_URL = "MAID_TINY_MODEL_BASE_URL"
        const val ENV_GENERATE_URL = "MAID_TINY_MODEL_GENERATE_URL"
        const val ENV_HEALTH_URL = "MAID_TINY_MODEL_HEALTH_URL"
        const val ENV_MODEL_NAME = "MAID_TINY_MODEL_NAME"

        const val DEFAULT_MAX_OUTPUT_TOKENS = 512
        const val MAX_OUTPUT_TOKENS = 2048
        const val DEFAULT_REQUEST_TIMEOUT_MS = 90_000L
        private const val SOCKET_READ_TIMEOUT_MS = 15_000
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_TOTAL_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 512 * 1024

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun parseJsonObject(rawBody: String): JsonObject? {
            if (rawBody.isBlank()) return null
            return runCatching {
                jsonParser.parseToJsonElement(rawBody).jsonObject
            }.getOrNull()
        }

        internal fun extractPromptAndSystemFromMessages(messages: JsonArray): ExtractedChatPrompt {
            val systemParts = mutableListOf<String>()
            val conversationTurns = mutableListOf<Pair<String, String>>()

            for (element in messages) {
                val obj = element as? JsonObject ?: continue
                val role = obj["role"]?.asStringOrNull()?.trim()?.lowercase().orEmpty()
                val text = extractContentText(obj["content"]).trim()
                if (text.isEmpty()) continue
                when (role) {
                    "system", "developer" -> systemParts += text
                    else -> conversationTurns += role to text
                }
            }

            val systemPrompt = systemParts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
            val prompt = when {
                conversationTurns.isEmpty() -> ""
                conversationTurns.size == 1 && conversationTurns.first().first == "user" ->
                    conversationTurns.first().second
                else -> buildString {
                    conversationTurns.forEach { (role, content) ->
                        val label = when (role) {
                            "assistant" -> "Assistant"
                            "tool" -> "Tool"
                            else -> "User"
                        }
                        appendLine("$label: $content")
                    }
                }.trim()
            }
            return ExtractedChatPrompt(systemPrompt = systemPrompt, prompt = prompt)
        }

        private fun extractContentText(contentElement: JsonElement?): String {
            return when (contentElement) {
                null -> ""
                is JsonPrimitive -> contentElement.contentOrNull.orEmpty()
                is JsonArray -> contentElement.mapNotNull { part ->
                    val partObj = part as? JsonObject ?: return@mapNotNull null
                    val type = partObj["type"]?.asStringOrNull()?.lowercase().orEmpty()
                    if (type.isEmpty() || type == "text" || type == "input_text") {
                        partObj["text"]?.asStringOrNull()
                    } else {
                        null
                    }
                }.joinToString("\n")
                else -> ""
            }
        }

        private fun resolveMaxOutputTokens(jsonObj: JsonObject): Int {
            val raw = (jsonObj["max_tokens"]
                ?: jsonObj["max_completion_tokens"]
                ?: jsonObj["max_output_tokens"])
                ?.let { (it as? JsonPrimitive)?.intOrNull }
            return (raw ?: DEFAULT_MAX_OUTPUT_TOKENS).coerceIn(1, MAX_OUTPUT_TOKENS)
        }

        private fun resolveSampler(
            jsonObj: JsonObject,
            base: TinyModelSamplerConfig,
        ): TinyModelSamplerConfig {
            val temperature = (jsonObj["temperature"] as? JsonPrimitive)?.doubleOrNull
                ?.coerceIn(0.0, 2.0)
                ?: base.temperature
            val topP = (jsonObj["top_p"] as? JsonPrimitive)?.doubleOrNull
                ?.coerceIn(0.0, 1.0)
                ?: base.topP
            val topK = (jsonObj["top_k"] as? JsonPrimitive)?.intOrNull
                ?.coerceAtLeast(1)
                ?: base.topK
            return base.copy(
                topK = topK,
                topP = topP,
                temperature = temperature,
            )
        }

        private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}
