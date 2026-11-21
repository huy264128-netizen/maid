package top.aaa000111.maid.provider

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatGptApiException(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 面向 OpenAI "Sign in with ChatGPT"（ChatGPT 订阅额度）的 OpenAI Responses API 执行器。
 *
 * 严格遵循 ChatGPT Plan Usage 接口约束：
 * - 调用 `POST https://api.openai.com/v1/responses`，使用 OAuth Bearer `access_token`；
 * - 强制设置 `"store": false` 与 `"stream": true` 并解析 SSE 流式事件；
 * - 不在 `input` 中发送 `role: "system"`，而是通过顶层 `"instructions"` 字段传递系统提示词；
 * - 不发送 `previous_response_id`、`temperature`、`top_p`、`max_output_tokens`；
 * - 完整支持工具调用（`function_call` / `function_call_output`）与多模态图片（`input_image`）；
 * - 遇到 HTTP 401 时触发一次串行刷新令牌并自动重试。
 */
class ChatGptResponsesPromptExecutor(
    private val accessTokenProvider: suspend (forceRefresh: Boolean) -> String = { forceRefresh ->
        ChatGptAuthRepository.getValidAccessToken(forceRefresh)
    },
    private val onSessionInvalidated: suspend (reason: String) -> Unit = { reason ->
        ChatGptAuthRepository.invalidateSession(reason)
    },
    private val httpClient: HttpClient = ProviderHttpClient.sharedStreamingHttpClient,
    private val responsesUrl: String = DEFAULT_RESPONSES_URL,
    private val onReasoningSummaryDelta: ((String) -> Unit)? = null,
) : PromptExecutor {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<StreamFrame.ToolCall>()
        var endMetaInfo = ResponseMetaInfo.Empty
        var finishReason: String? = "stop"

        executeStreaming(prompt, model, tools).collect { frame ->
            when (frame) {
                is StreamFrame.Append -> textBuilder.append(frame.text)
                is StreamFrame.ToolCall -> toolCalls += frame
                is StreamFrame.End -> {
                    endMetaInfo = frame.metaInfo
                    finishReason = frame.finishReason ?: finishReason
                }
            }
        }

        val responses = mutableListOf<Message.Response>()
        val assistantText = textBuilder.toString()
        if (assistantText.isNotEmpty() || toolCalls.isEmpty()) {
            responses += Message.Assistant(
                content = assistantText,
                metaInfo = endMetaInfo,
                finishReason = finishReason,
            )
        }
        toolCalls.forEach { call ->
            responses += Message.Tool.Call(
                id = call.id,
                tool = call.name,
                content = call.content,
                metaInfo = endMetaInfo,
            )
        }
        return responses
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        val requestJson = buildRequestPayload(prompt, model, tools)
        val requestBodyString = json.encodeToString(JsonObject.serializer(), requestJson)

        var attempt = 0
        while (true) {
            val forceRefresh = attempt > 0
            val accessToken = accessTokenProvider(forceRefresh)
            var shouldRetryAfterRefresh = false

            httpClient.preparePost(responsesUrl) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.Accept, "text/event-stream")
                setBody(TextContent(requestBodyString, ContentType.Application.Json))
            }.execute { response ->
                val statusCode = response.status.value
                if (!response.status.isSuccess()) {
                    val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                    val (errCode, errMessage) = parseErrorPayload(errorBody)
                    if (statusCode == 401 && attempt == 0) {
                        shouldRetryAfterRefresh = true
                        return@execute
                    }
                    if (isSessionInvalidError(statusCode, errCode)) {
                        runCatching {
                            onSessionInvalidated(
                                formatUserFacingError(statusCode, errCode, errMessage),
                            )
                        }
                    }
                    throw ChatGptApiException(
                        message = formatUserFacingError(statusCode, errCode, errMessage),
                        statusCode = statusCode,
                        errorCode = errCode,
                    )
                }

                val channel = response.bodyAsChannel()
                val state = SseAccumulator()
                var currentEvent: String? = null
                val dataLines = mutableListOf<String>()

                suspend fun dispatchEvent() {
                    if (dataLines.isEmpty()) {
                        currentEvent = null
                        return
                    }
                    val payload = dataLines.joinToString("\n").trim()
                    dataLines.clear()
                    val eventName = currentEvent
                    currentEvent = null
                    if (payload.isEmpty() || payload == "[DONE]") return

                    val frames = handleSseEvent(eventName, payload, state)
                    for (frame in frames) {
                        emit(frame)
                    }
                }

                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    when {
                        line.isEmpty() -> dispatchEvent()
                        line.startsWith(":") -> Unit // SSE 注释心跳
                        line.startsWith("event:") -> {
                            currentEvent = line.substringAfter("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            dataLines += line.substringAfter("data:").trimStart()
                        }
                    }
                }
                dispatchEvent()

                // 兜底输出尚未发送的挂起函数调用（若服务端未发送 output_item.done / response.completed）
                state.flushPendingToolCalls().forEach { callFrame ->
                    emit(callFrame)
                }

                val finishReason = state.finishReason
                    ?: if (state.emittedToolCallIds.isNotEmpty()) "tool_calls" else "stop"
                emit(StreamFrame.End(finishReason = finishReason, metaInfo = state.metaInfo))
            }

            if (shouldRetryAfterRefresh) {
                attempt += 1
                continue
            }
            break
        }
    }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult {
        throw UnsupportedOperationException(
            "ChatGPT plan usage executor does not support moderation API",
        )
    }

    private class PendingFunctionCall(
        val itemId: String?,
        var callId: String?,
        var name: String?,
        val arguments: StringBuilder = StringBuilder(),
    )

    private class SseAccumulator {
        var receivedTextDelta: Boolean = false
        var receivedReasoningSummaryDelta: Boolean = false
        val reasoningSummary: StringBuilder = StringBuilder()
        var finishReason: String? = null
        var metaInfo: ResponseMetaInfo = ResponseMetaInfo.Empty
        val pendingCallsByItemId = LinkedHashMap<String, PendingFunctionCall>()
        val emittedToolCallIds = LinkedHashSet<String>()

        fun flushPendingToolCalls(): List<StreamFrame.ToolCall> {
            val frames = mutableListOf<StreamFrame.ToolCall>()
            pendingCallsByItemId.values.forEach { pending ->
                val callId = pending.callId?.takeIf { it.isNotBlank() }
                    ?: pending.itemId?.takeIf { it.isNotBlank() }
                    ?: "call_${emittedToolCallIds.size + 1}"
                val name = pending.name?.takeIf { it.isNotBlank() } ?: return@forEach
                if (emittedToolCallIds.add(callId)) {
                    val args = pending.arguments.toString().ifBlank { "{}" }
                    frames += StreamFrame.ToolCall(id = callId, name = name, content = args)
                }
            }
            pendingCallsByItemId.clear()
            return frames
        }
    }

    private suspend fun handleSseEvent(
        eventHeader: String?,
        rawData: String,
        state: SseAccumulator,
    ): List<StreamFrame> {
        val dataObj = runCatching { json.parseToJsonElement(rawData).jsonObject }.getOrNull()
            ?: return emptyList()
        val eventType = dataObj["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: eventHeader.orEmpty()
        val frames = mutableListOf<StreamFrame>()

        when (eventType) {
            "response.output_text.delta" -> {
                val delta = dataObj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    state.receivedTextDelta = true
                    frames += StreamFrame.Append(text = delta)
                }
            }

            "response.reasoning_summary_text.delta" -> {
                val delta = dataObj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    state.receivedReasoningSummaryDelta = true
                    state.reasoningSummary.append(delta)
                    onReasoningSummaryDelta?.invoke(delta)
                }
            }

            "response.reasoning_summary_text.done" -> {
                val fullText = dataObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (fullText.isNotEmpty() && !state.receivedReasoningSummaryDelta) {
                    state.receivedReasoningSummaryDelta = true
                    state.reasoningSummary.append(fullText)
                    onReasoningSummaryDelta?.invoke(fullText)
                }
            }

            "response.output_item.added" -> {
                val item = dataObj["item"] as? JsonObject ?: return emptyList()
                if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                    val itemId = item["id"]?.jsonPrimitive?.contentOrNull
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                    val name = item["name"]?.jsonPrimitive?.contentOrNull
                    val initialArgs = item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val key = itemId ?: callId ?: "item_${state.pendingCallsByItemId.size}"
                    val entry = state.pendingCallsByItemId.getOrPut(key) {
                        PendingFunctionCall(itemId = itemId, callId = callId, name = name)
                    }
                    if (!callId.isNullOrBlank()) entry.callId = callId
                    if (!name.isNullOrBlank()) entry.name = name
                    if (initialArgs.isNotEmpty() && entry.arguments.isEmpty()) {
                        entry.arguments.append(initialArgs)
                    }
                }
            }

            "response.function_call_arguments.delta" -> {
                val itemId = dataObj["item_id"]?.jsonPrimitive?.contentOrNull
                val delta = dataObj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!itemId.isNullOrBlank() && delta.isNotEmpty()) {
                    val entry = state.pendingCallsByItemId.getOrPut(itemId) {
                        PendingFunctionCall(itemId = itemId, callId = null, name = null)
                    }
                    entry.arguments.append(delta)
                }
            }

            "response.function_call_arguments.done" -> {
                val itemId = dataObj["item_id"]?.jsonPrimitive?.contentOrNull
                val fullArgs = dataObj["arguments"]?.jsonPrimitive?.contentOrNull
                val name = dataObj["name"]?.jsonPrimitive?.contentOrNull
                if (!itemId.isNullOrBlank()) {
                    val entry = state.pendingCallsByItemId.getOrPut(itemId) {
                        PendingFunctionCall(itemId = itemId, callId = null, name = name)
                    }
                    if (!name.isNullOrBlank()) entry.name = name
                    if (fullArgs != null) {
                        entry.arguments.clear()
                        entry.arguments.append(fullArgs)
                    }
                }
            }

            "response.output_item.done" -> {
                val item = dataObj["item"] as? JsonObject ?: return emptyList()
                collectCompletedOutputItem(item, state, frames)
            }

            "response.completed", "response.incomplete" -> {
                val responseObj = dataObj["response"] as? JsonObject
                if (responseObj != null) {
                    extractUsageMetaInfo(responseObj)?.let { state.metaInfo = it }
                    val outputArray = responseObj["output"] as? JsonArray
                    outputArray?.forEach { elem ->
                        val item = elem as? JsonObject ?: return@forEach
                        collectCompletedOutputItem(item, state, frames)
                    }
                }
                state.finishReason = when {
                    eventType == "response.incomplete" -> "length"
                    state.emittedToolCallIds.isNotEmpty() -> "tool_calls"
                    else -> "stop"
                }
            }

            "response.failed", "error" -> {
                val responseError = (dataObj["response"] as? JsonObject)?.get("error") as? JsonObject
                val directError = dataObj["error"] as? JsonObject
                val errorObj = responseError ?: directError ?: dataObj
                val errCode = errorObj["code"]?.jsonPrimitive?.contentOrNull
                val errMessage = errorObj["message"]?.jsonPrimitive?.contentOrNull
                if (isSessionInvalidError(statusCode = null, errorCode = errCode)) {
                    runCatching {
                        onSessionInvalidated(formatUserFacingError(null, errCode, errMessage))
                    }
                }
                throw ChatGptApiException(
                    message = formatUserFacingError(null, errCode, errMessage),
                    errorCode = errCode,
                )
            }
        }
        return frames
    }

    private fun collectCompletedOutputItem(
        item: JsonObject,
        state: SseAccumulator,
        frames: MutableList<StreamFrame>,
    ) {
        when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "function_call" -> {
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull
                val tracked = itemId?.let { state.pendingCallsByItemId.remove(it) }
                val callId = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: tracked?.callId?.takeIf { it.isNotBlank() }
                    ?: itemId?.takeIf { it.isNotBlank() }
                    ?: "call_${state.emittedToolCallIds.size + 1}"
                val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: tracked?.name?.takeIf { it.isNotBlank() }
                    ?: return
                val args = item["arguments"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: tracked?.arguments?.toString()?.takeIf { it.isNotBlank() }
                    ?: "{}"
                if (state.emittedToolCallIds.add(callId)) {
                    frames += StreamFrame.ToolCall(id = callId, name = name, content = args)
                }
            }

            "message" -> {
                if (!state.receivedTextDelta) {
                    val contentParts = item["content"] as? JsonArray ?: return
                    val combined = buildString {
                        for (part in contentParts) {
                            val obj = part as? JsonObject ?: continue
                            val partType = obj["type"]?.jsonPrimitive?.contentOrNull
                            if (partType == "output_text" || partType == "text") {
                                obj["text"]?.jsonPrimitive?.contentOrNull?.let { append(it) }
                            }
                        }
                    }
                    if (combined.isNotEmpty()) {
                        state.receivedTextDelta = true
                        frames += StreamFrame.Append(text = combined)
                    }
                }
            }
            "reasoning" -> {
                if (!state.receivedReasoningSummaryDelta) {
                    val summaryParts = item["summary"] as? JsonArray ?: return
                    val combined = buildString {
                        for (part in summaryParts) {
                            val obj = part as? JsonObject ?: continue
                            val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (text.isNotEmpty()) {
                                if (isNotEmpty()) append("\n")
                                append(text)
                            }
                        }
                    }
                    if (combined.isNotEmpty()) {
                        state.receivedReasoningSummaryDelta = true
                        state.reasoningSummary.append(combined)
                        onReasoningSummaryDelta?.invoke(combined)
                    }
                }
            }
        }
    }

    private fun extractUsageMetaInfo(responseObj: JsonObject): ResponseMetaInfo? {
        val usage = responseObj["usage"] as? JsonObject ?: return null
        val inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull
        val outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull
        val totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull
            ?: if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
        return ResponseMetaInfo.Empty.copy(
            totalTokensCount = totalTokens,
            inputTokensCount = inputTokens,
            outputTokensCount = outputTokens,
        )
    }

    internal companion object {
        const val DEFAULT_RESPONSES_URL = "https://api.openai.com/v1/responses"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * 将 Koog [Prompt] 与 [ToolDescriptor] 列表转换为严格满足 ChatGPT Plan Usage 约束的
         * OpenAI Responses API 请求体（`store=false`、`stream=true`、顶层 `instructions`、无不支持参数）。
         */
        internal fun buildRequestPayload(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): JsonObject {
            val systemParts = mutableListOf<String>()
            val inputItems = mutableListOf<JsonObject>()
            var fallbackCallCounter = 0

            prompt.messages.forEach { message ->
                when (message) {
                    is Message.System -> {
                        val trimmed = message.content.trim()
                        if (trimmed.isNotEmpty()) {
                            systemParts += trimmed
                        }
                    }

                    is Message.User -> {
                        val contentArray = buildJsonArray {
                            if (message.content.isNotEmpty()) {
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("input_text"))
                                        put("text", JsonPrimitive(message.content))
                                    },
                                )
                            }
                            message.attachments.forEach { attachment ->
                                convertAttachmentToInputContent(attachment)?.let { add(it) }
                            }
                        }
                        if (contentArray.isNotEmpty()) {
                            inputItems += buildJsonObject {
                                put("type", JsonPrimitive("message"))
                                put("role", JsonPrimitive("user"))
                                put("content", contentArray)
                            }
                        }
                    }

                    is Message.Assistant -> {
                        if (message.content.isNotEmpty()) {
                            inputItems += buildJsonObject {
                                put("type", JsonPrimitive("message"))
                                put("role", JsonPrimitive("assistant"))
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", JsonPrimitive("output_text"))
                                                put("text", JsonPrimitive(message.content))
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }

                    is Message.Tool.Call -> {
                        fallbackCallCounter += 1
                        val callId = message.id?.takeIf { it.isNotBlank() }
                            ?: "call_$fallbackCallCounter"
                        inputItems += buildJsonObject {
                            put("type", JsonPrimitive("function_call"))
                            put("call_id", JsonPrimitive(callId))
                            put("name", JsonPrimitive(message.tool))
                            put("arguments", JsonPrimitive(message.content.ifBlank { "{}" }))
                        }
                    }

                    is Message.Tool.Result -> {
                        fallbackCallCounter += 1
                        val callId = message.id?.takeIf { it.isNotBlank() }
                            ?: "call_$fallbackCallCounter"
                        inputItems += buildJsonObject {
                            put("type", JsonPrimitive("function_call_output"))
                            put("call_id", JsonPrimitive(callId))
                            put("output", JsonPrimitive(message.content))
                        }
                    }
                }
            }

            val instructions = systemParts.joinToString("\n\n").takeIf { it.isNotEmpty() }

            return buildJsonObject {
                put("model", JsonPrimitive(model.id.trim()))
                put("store", JsonPrimitive(false))
                put("stream", JsonPrimitive(true))
                if (instructions != null) {
                    put("instructions", JsonPrimitive(instructions))
                }
                put("input", JsonArray(inputItems))
                if (tools.isNotEmpty()) {
                    put(
                        "tools",
                        buildJsonArray {
                            tools.forEach { tool ->
                                add(convertToolDescriptor(tool))
                            }
                        },
                    )
                }
            }
        }

        private fun convertAttachmentToInputContent(attachment: Attachment): JsonObject? {
            return when (attachment) {
                is Attachment.Image -> {
                    when (val content = attachment.content) {
                        is AttachmentContent.URL -> buildJsonObject {
                            put("type", JsonPrimitive("input_image"))
                            put("image_url", JsonPrimitive(content.url))
                        }
                        is AttachmentContent.Binary -> {
                            val mime = attachment.mimeType.ifBlank { "image/jpeg" }
                            val dataUri = "data:$mime;base64,${content.asBase64()}"
                            buildJsonObject {
                                put("type", JsonPrimitive("input_image"))
                                put("image_url", JsonPrimitive(dataUri))
                            }
                        }
                        is AttachmentContent.PlainText -> buildJsonObject {
                            put("type", JsonPrimitive("input_text"))
                            put("text", JsonPrimitive(content.text))
                        }
                    }
                }

                is Attachment.File,
                is Attachment.Audio,
                is Attachment.Video -> {
                    when (val content = attachment.content) {
                        is AttachmentContent.PlainText -> buildJsonObject {
                            put("type", JsonPrimitive("input_text"))
                            put("text", JsonPrimitive(content.text))
                        }
                        is AttachmentContent.URL -> buildJsonObject {
                            put("type", JsonPrimitive("input_text"))
                            put("text", JsonPrimitive(content.url))
                        }
                        is AttachmentContent.Binary -> null
                    }
                }
            }
        }

        internal fun convertToolDescriptor(tool: ToolDescriptor): JsonObject {
            val allParams = tool.requiredParameters + tool.optionalParameters
            val requiredNames = tool.requiredParameters.map { it.name }.distinct()
            val parametersSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        allParams.forEach { param ->
                            put(param.name, convertParameterSchema(param))
                        }
                    },
                )
                if (requiredNames.isNotEmpty()) {
                    put(
                        "required",
                        buildJsonArray {
                            requiredNames.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
                put("additionalProperties", JsonPrimitive(false))
            }
            return buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(tool.name))
                if (tool.description.isNotBlank()) {
                    put("description", JsonPrimitive(tool.description))
                }
                put("parameters", parametersSchema)
            }
        }

        private fun convertParameterSchema(param: ToolParameterDescriptor): JsonObject {
            val base = convertParameterTypeSchema(param.type)
            if (param.description.isBlank()) return base
            return JsonObject(
                LinkedHashMap(base).apply {
                    put("description", JsonPrimitive(param.description))
                },
            )
        }

        private fun convertParameterTypeSchema(type: ToolParameterType): JsonObject {
            return when (type) {
                ToolParameterType.String -> buildJsonObject {
                    put("type", JsonPrimitive("string"))
                }
                ToolParameterType.Integer -> buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                }
                ToolParameterType.Float -> buildJsonObject {
                    put("type", JsonPrimitive("number"))
                }
                ToolParameterType.Boolean -> buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                }
                is ToolParameterType.Enum -> buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put(
                        "enum",
                        buildJsonArray {
                            type.entries.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
                is ToolParameterType.List -> buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", convertParameterTypeSchema(type.itemsType))
                }
                is ToolParameterType.Object -> buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            type.properties.forEach { prop ->
                                put(prop.name, convertParameterSchema(prop))
                            }
                        },
                    )
                    if (type.requiredProperties.isNotEmpty()) {
                        put(
                            "required",
                            buildJsonArray {
                                type.requiredProperties.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                    }
                    when {
                        type.additionalPropertiesType != null -> {
                            put(
                                "additionalProperties",
                                convertParameterTypeSchema(type.additionalPropertiesType!!),
                            )
                        }
                        type.additionalProperties != null -> {
                            put("additionalProperties", JsonPrimitive(type.additionalProperties!!))
                        }
                    }
                }
            }
        }

        private fun parseErrorPayload(rawBody: String): Pair<String?, String?> {
            if (rawBody.isBlank()) return null to null
            val root = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
                ?: return null to rawBody.trim().take(200)
            val errObj = root["error"] as? JsonObject
            val code = errObj?.get("code")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: root["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: root["error"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            val message = errObj?.get("message")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: root["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: root["error_description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            return code to message
        }

        private fun isSessionInvalidError(statusCode: Int?, errorCode: String?): Boolean {
            return statusCode == 401 ||
                errorCode.equals("subscription_sharing_invalid_user", ignoreCase = true) ||
                errorCode.equals("invalid_grant", ignoreCase = true)
        }

        internal fun formatUserFacingError(
            statusCode: Int?,
            errorCode: String?,
            errorMessage: String?,
        ): String {
            return when {
                errorCode.equals("subscription_sharing_usage_limit_exceeded", ignoreCase = true) ||
                    statusCode == 429 -> {
                    val detail = errorMessage?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                    "ChatGPT 订阅额度已达上限，请稍后再试或切换其他模型服务商$detail"
                }

                errorCode.equals("subscription_sharing_user_not_eligible", ignoreCase = true) ||
                    statusCode == 403 -> {
                    val detail = errorMessage?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                    "当前 ChatGPT 账号或订阅计划暂不支持使用订阅额度调用接口$detail"
                }

                isSessionInvalidError(statusCode, errorCode) -> {
                    val detail = errorMessage?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                    "ChatGPT 登录会话已失效，请前往设置中的「Sign in with ChatGPT」重新登录$detail"
                }

                else -> {
                    val statusPrefix = statusCode?.let { "HTTP $it " }.orEmpty()
                    val detail = listOfNotNull(
                        errorCode?.takeIf { it.isNotBlank() },
                        errorMessage?.takeIf { it.isNotBlank() },
                    ).joinToString("：")
                    if (detail.isNotEmpty()) {
                        "ChatGPT 接口调用失败（${statusPrefix}$detail）"
                    } else {
                        "ChatGPT 接口调用失败${statusCode?.let { "（HTTP $it）" }.orEmpty()}"
                    }
                }
            }
        }
    }
}
