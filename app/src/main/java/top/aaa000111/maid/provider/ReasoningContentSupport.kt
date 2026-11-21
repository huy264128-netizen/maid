package top.aaa000111.maid.provider

import io.ktor.client.HttpClient
import io.ktor.client.call.save
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 线程安全的有界 LRU 缓存，用于按 `tool_call_id` 暂存服务端在思考模式（Thinking Mode）下返回的 `reasoning_content`。
 *
 * 原因：Koog 的 `OpenAIMessage.Assistant` 当前未建模 `reasoning_content` 字段，导致工具调用响应被反序列化后丢失思维链；
 * 当后续携工具结果再次请求 DeepSeek / GLM / Kimi / Qwen / 豆包等开启思考模式的接口时，
 * 服务端会报错：`The 'reasoning_content' in the thinking mode must be passed back to the API.`。
 */
internal class ReasoningContentCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val entries = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > maxEntries
        }
    }

    fun put(toolCallId: String, reasoningContent: String) {
        val normalizedId = toolCallId.trim()
        if (normalizedId.isEmpty()) return
        synchronized(lock) {
            entries[normalizedId] = reasoningContent
        }
    }

    fun get(toolCallId: String): String? {
        val normalizedId = toolCallId.trim()
        if (normalizedId.isEmpty()) return null
        synchronized(lock) {
            return entries[normalizedId]
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 512
    }
}

/**
 * Ktor 客户端插件与 JSON 改写工具：
 * 1. 从 Chat Completions 响应的 `choices[*].message` 中提取 `reasoning_content`（或 `reasoning`），按 `tool_calls[*].id` 缓存；
 * 2. 在后续请求 `messages[*]` 中，为包含对应 `tool_calls` 且缺失 `reasoning_content` 的 `assistant` 消息回填 `reasoning_content`。
 */
internal object ReasoningContentSupport {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal val sharedCache = ReasoningContentCache()

    val ReasoningContentPlugin = createClientPlugin("MaidReasoningContentPlugin") {
        on(Send) { request ->
            val rawBody = when (val body = request.body) {
                is TextContent -> body.text
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                is String -> body
                else -> null
            }

            val isStreamingRequest = rawBody?.let(::isStreamingChatRequest) ?: false
            if (rawBody != null) {
                val rewritten = injectReasoningIntoRequest(rawBody, sharedCache)
                if (rewritten != null) {
                    val contentType = (request.body as? OutgoingContent)?.contentType
                        ?: ContentType.Application.Json
                    request.setBody(TextContent(rewritten, contentType))
                }
            }

            val call = proceed(request)
            val isJsonResponse = call.response.contentType()?.withoutParameters()?.let { ct ->
                ct == ContentType.Application.Json || ct.contentSubtype.equals("json", ignoreCase = true)
            } == true

            if (!isStreamingRequest && call.response.status.isSuccess() && isJsonResponse) {
                val savedCall = call.save()
                val responseText = runCatching { savedCall.response.bodyAsText() }.getOrNull()
                if (!responseText.isNullOrBlank()) {
                    captureReasoningFromResponse(responseText, sharedCache)
                }
                savedCall
            } else {
                call
            }
        }
    }

    fun createReasoningAwareHttpClient(): HttpClient =
        ProviderHttpClient.createReasoningAwareHttpClient()

    internal fun isStreamingChatRequest(requestBody: String): Boolean {
        val root = runCatching { json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull()
            ?: return false
        return (root["stream"] as? JsonPrimitive)?.booleanOrNull == true
    }

    internal fun captureReasoningFromResponse(
        responseBody: String,
        cache: ReasoningContentCache = sharedCache,
    ): Int {
        val root = runCatching { json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
            ?: return 0
        val choices = root["choices"] as? JsonArray ?: return 0

        var capturedCount = 0
        for (choice in choices) {
            val message = (choice as? JsonObject)?.get("message") as? JsonObject ?: continue
            val reasoningElement = message["reasoning_content"] ?: message["reasoning"]
            val reasoningPrimitive = reasoningElement as? JsonPrimitive ?: continue
            if (!reasoningPrimitive.isString) continue
            val reasoningContent = reasoningPrimitive.content

            val toolCalls = message["tool_calls"] as? JsonArray ?: continue
            for (toolCall in toolCalls) {
                val callId = (toolCall as? JsonObject)
                    ?.get("id")
                    ?.let { it as? JsonPrimitive }
                    ?.takeIf { it.isString }
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: continue
                cache.put(callId, reasoningContent)
                capturedCount += 1
            }
        }
        return capturedCount
    }

    internal fun injectReasoningIntoRequest(
        requestBody: String,
        cache: ReasoningContentCache = sharedCache,
    ): String? {
        val root = runCatching { json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull()
            ?: return null
        val messages = root["messages"] as? JsonArray ?: return null

        var modified = false
        val updatedMessages = messages.map { element ->
            val msgObj = element as? JsonObject ?: return@map element
            val role = (msgObj["role"] as? JsonPrimitive)?.contentOrNull
            if (role != "assistant" || "reasoning_content" in msgObj) {
                return@map element
            }

            val toolCalls = msgObj["tool_calls"] as? JsonArray
            if (toolCalls.isNullOrEmpty()) {
                return@map element
            }

            val matchedReasoning = toolCalls.firstNotNullOfOrNull { call ->
                val callId = (call as? JsonObject)
                    ?.get("id")
                    ?.let { it as? JsonPrimitive }
                    ?.takeIf { it.isString }
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (callId != null) cache.get(callId) else null
            } ?: return@map element

            modified = true
            val updatedMsgMap = LinkedHashMap(msgObj).apply {
                put("reasoning_content", JsonPrimitive(matchedReasoning))
            }
            JsonObject(updatedMsgMap)
        }

        if (!modified) return null

        val updatedRoot = LinkedHashMap(root).apply {
            put("messages", JsonArray(updatedMessages))
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(updatedRoot))
    }
}
