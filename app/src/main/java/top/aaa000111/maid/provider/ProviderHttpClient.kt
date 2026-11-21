package top.aaa000111.maid.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.formUrlEncode
import java.util.concurrent.TimeUnit

/**
 * 统一的 LLM Provider HTTP 网络栈工厂（基于 Ktor + OkHttp 引擎）。
 *
 * 集中管理连接池、超时与推理思考链（ReasoningContentSupport）插件，
 * 同时为模型列表拉取、OAuth 令牌交换 / JWKS 获取、以及 Responses API 流式推理提供可复用客户端。
 */
internal object ProviderHttpClient {

    private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 15L
    private const val DEFAULT_READ_TIMEOUT_SECONDS = 25L
    private const val STREAMING_READ_TIMEOUT_SECONDS = 180L

    /** 面向常规 JSON / 表单请求（模型列表、OAuth Discovery / Token / JWKS）的共享 Ktor 客户端 */
    val sharedHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                config {
                    connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    writeTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
    }

    /** 面向长连接 SSE 流式推理（如 ChatGPT Responses API）的共享 Ktor 客户端 */
    val sharedStreamingHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                config {
                    connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    readTimeout(STREAMING_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    writeTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
    }

    /** 为 OpenAI 兼容思考模式客户端构建挂载 [ReasoningContentSupport.ReasoningContentPlugin] 的 Ktor 客户端 */
    fun createReasoningAwareHttpClient(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                readTimeout(STREAMING_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                writeTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        install(ReasoningContentSupport.ReasoningContentPlugin)
    }
}

/**
 * 基于统一 Ktor [HttpClient] 的模型列表拉取传输实现，替代零散的 `HttpURLConnection`。
 */
class KtorModelHttpTransport(
    private val httpClient: HttpClient = ProviderHttpClient.sharedHttpClient,
) : ModelHttpTransport {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): ModelHttpResponse {
        val response = httpClient.get(url) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            headers.forEach { (key, value) ->
                header(key, value)
            }
        }
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return ModelHttpResponse(
            statusCode = response.status.value,
            body = body,
        )
    }
}

/**
 * 基于统一 Ktor [HttpClient] 的 Sign in with ChatGPT OAuth/OIDC/JWKS 网络实现。
 */
class KtorChatGptOAuthClient(
    private val httpClient: HttpClient = ProviderHttpClient.sharedHttpClient,
) : ChatGptOAuthHttpClient {

    override suspend fun get(url: String): ChatGptHttpResponse {
        val response = httpClient.get(url) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return ChatGptHttpResponse(
            statusCode = response.status.value,
            body = body,
        )
    }

    override suspend fun postForm(
        url: String,
        formParams: Map<String, String>,
    ): ChatGptHttpResponse {
        val encoded = Parameters.build {
            formParams.forEach { (key, value) ->
                append(key, value)
            }
        }.formUrlEncode()
        val response = httpClient.post(url) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(TextContent(encoded, ContentType.Application.FormUrlEncoded))
        }
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return ChatGptHttpResponse(
            statusCode = response.status.value,
            body = body,
        )
    }
}
