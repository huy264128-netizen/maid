package top.aaa000111.maid.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.nio.charset.Charset

/**
 * 轻量 HTTP GET 抓取结果。
 */
data class HttpFetchResponse(
    val statusCode: Int,
    val finalUrl: String,
    val body: String,
    val contentType: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 搜索与网页抓取专用 HTTP 客户端抽象，便于注入 Ktor OkHttp 实现或单测 Mock 实现。
 */
interface SearchHttpClient : AutoCloseable {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long = DEFAULT_SEARCH_TIMEOUT_MS,
        maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    ): HttpFetchResponse

    companion object {
        const val DEFAULT_PROBE_TIMEOUT_MS = 3_500L
        const val DEFAULT_SEARCH_TIMEOUT_MS = 12_000L
        const val DEFAULT_PAGE_TIMEOUT_MS = 15_000L
        const val DEFAULT_MAX_BODY_BYTES = 2L * 1024L * 1024L

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * 基于 Ktor Client（Android 推荐使用 OkHttp 引擎，自动支持 HTTP/2、透明 Gzip 解压与系统代理）
 * 的 [SearchHttpClient] 实现。
 */
class KtorSearchHttpClient(
    private val client: HttpClient,
    private val ownsClient: Boolean = true,
) : SearchHttpClient {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maxBodyBytes: Long,
    ): HttpFetchResponse {
        val effectiveTimeout = timeoutMillis.coerceAtLeast(500L)
        val response = client.get(url) {
            timeout {
                requestTimeoutMillis = effectiveTimeout
                connectTimeoutMillis = effectiveTimeout
                socketTimeoutMillis = effectiveTimeout
            }
            if (headers.keys.none { it.equals(HttpHeaders.UserAgent, ignoreCase = true) }) {
                header(HttpHeaders.UserAgent, SearchHttpClient.DEFAULT_USER_AGENT)
            }
            if (headers.keys.none { it.equals(HttpHeaders.Accept, ignoreCase = true) }) {
                header(
                    HttpHeaders.Accept,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
            }
            if (headers.keys.none { it.equals(HttpHeaders.AcceptLanguage, ignoreCase = true) }) {
                header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            }
            headers.forEach { (key, value) ->
                // 不手动覆盖 Accept-Encoding，确保 OkHttp 自动协商并透明解压 gzip
                if (!key.equals(HttpHeaders.AcceptEncoding, ignoreCase = true)) {
                    header(key, value)
                }
            }
        }

        val cappedBytes = response.bodyAsChannel()
            .readRemaining(maxBodyBytes.coerceAtLeast(1024L))
            .readByteArray()
        val charset: Charset = response.charset() ?: Charsets.UTF_8
        val bodyText = String(cappedBytes, charset)
        val flattenedHeaders = buildMap {
            response.headers.entries().forEach { (key, values) ->
                put(key.lowercase(), values.joinToString(", "))
            }
        }
        return HttpFetchResponse(
            statusCode = response.status.value,
            finalUrl = response.call.request.url.toString(),
            body = bodyText,
            contentType = response.headers[HttpHeaders.ContentType].orEmpty(),
            headers = flattenedHeaders,
        )
    }

    override fun close() {
        if (ownsClient) {
            client.close()
        }
    }

    companion object {
        fun createDefault(): KtorSearchHttpClient {
            val httpClient = HttpClient(OkHttp) {
                followRedirects = true
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = SearchHttpClient.DEFAULT_SEARCH_TIMEOUT_MS
                    connectTimeoutMillis = SearchHttpClient.DEFAULT_SEARCH_TIMEOUT_MS
                    socketTimeoutMillis = SearchHttpClient.DEFAULT_SEARCH_TIMEOUT_MS
                }
                install(HttpCookies)
                engine {
                    config {
                        followRedirects(true)
                        followSslRedirects(true)
                        retryOnConnectionFailure(true)
                    }
                }
            }
            return KtorSearchHttpClient(client = httpClient, ownsClient = true)
        }
    }
}
