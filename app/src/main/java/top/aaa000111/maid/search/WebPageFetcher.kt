package top.aaa000111.maid.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fleeksoft.ksoup.Ksoup
import java.net.InetAddress
import java.net.URI

/**
 * 基于 Ktor + Ksoup 的网页抓取器：
 * - [fetch]：去除脚本、样式、导航与页脚噪音，优先提取 `<article>` / `<main>` 正文，适合阅读常规文章；
 * - [fetchRaw]：返回未经 Ksoup 清洗的原始响应文本（支持偏移分页），适合阅读原始 HTML、JSON、XML、Markdown、代码文件或排查页面源码。
 */
class WebPageFetcher(
    private val httpClient: SearchHttpClient,
    private val timeoutMillis: Long = SearchHttpClient.DEFAULT_PAGE_TIMEOUT_MS,
    private val context: Context? = null,
) {
    /**
     * 抓取指定网页并用 Ksoup 清洗提取干净正文。
     */
    suspend fun fetch(
        url: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): WebPageSnapshot {
        val normalizedUrl = validateTargetUrl(url)
        val response = httpClient.get(
            url = normalizedUrl,
            timeoutMillis = timeoutMillis,
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("网页抓取失败（HTTP ${response.statusCode}）：$normalizedUrl")
        }

        val safeMaxChars = maxChars.coerceIn(500, MAX_ALLOWED_CHARS)
        val contentType = response.contentType.lowercase()
        val isPlainText = contentType.startsWith("text/plain") ||
            contentType.contains("json") ||
            contentType.contains("markdown")

        val (title, extractedText) = if (isPlainText) {
            normalizedUrl to normalizeReadableText(response.body)
        } else {
            extractHtml(response.body, response.finalUrl)
        }

        val truncated = extractedText.length > safeMaxChars
        val finalContent = if (truncated) {
            extractedText.take(safeMaxChars).trimEnd() + "\n...(内容已截断)"
        } else {
            extractedText
        }

        return WebPageSnapshot(
            requestedUrl = normalizedUrl,
            finalUrl = response.finalUrl,
            title = title,
            content = finalContent,
            statusCode = response.statusCode,
            truncated = truncated,
            originalCharLength = extractedText.length,
        )
    }

    /**
     * 抓取指定 URL 的原始响应文本（不做 Ksoup 标签剥离），支持按 [offsetChars] 分片翻页读取。
     */
    suspend fun fetchRaw(
        url: String,
        maxChars: Int = DEFAULT_RAW_MAX_CHARS,
        offsetChars: Int = 0,
    ): RawWebPageSnapshot {
        val normalizedUrl = validateTargetUrl(url)
        val response = httpClient.get(
            url = normalizedUrl,
            timeoutMillis = timeoutMillis,
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("原始页面抓取失败（HTTP ${response.statusCode}）：$normalizedUrl")
        }

        val safeMaxChars = maxChars.coerceIn(500, MAX_ALLOWED_RAW_CHARS)
        val rawBody = response.body
        val totalLen = rawBody.length
        val safeOffset = offsetChars.coerceIn(0, totalLen)
        val endExclusive = (safeOffset + safeMaxChars).coerceAtMost(totalLen)
        val slice = rawBody.substring(safeOffset, endExclusive)
        val truncated = endExclusive < totalLen

        return RawWebPageSnapshot(
            requestedUrl = normalizedUrl,
            finalUrl = response.finalUrl,
            contentType = response.contentType.ifBlank { "unknown" },
            statusCode = response.statusCode,
            content = slice,
            offsetChars = safeOffset,
            totalCharLength = totalLen,
            truncated = truncated,
        )
    }

    internal data class RenderedPage(val finalUrl: String, val title: String, val content: String)

    internal suspend fun fetchRendered(url: String, maxChars: Int): RenderedPage {
        val loader = RenderedWebPageLoader(checkNotNull(context) { "WebView 仅在 Android 应用运行时可用" })
        val page = loader.load(validateTargetUrl(url), timeoutMillis)
        return withContext(Dispatchers.Default) {
            val (title, text) = extractHtml(page.html, page.url)
            check(text.isNotBlank()) { "渲染后没有提取到正文" }
            val limit = maxChars.coerceIn(500, MAX_ALLOWED_CHARS)
            val content = if (text.length > limit) text.take(limit).trimEnd() + "\n...(内容已截断)" else text
            RenderedPage(page.url, title, content)
        }
    }

    private fun extractHtml(html: String, finalUrl: String): Pair<String, String> {
        val document = Ksoup.parse(html = html, baseUri = finalUrl)
        val title = SearchEngine.normalizeWhitespace(document.title()).ifEmpty { finalUrl }
        document.select(
            "script, style, noscript, svg, canvas, iframe, nav, footer, header, aside, form, " +
                "[role=navigation], [role=banner], [role=contentinfo], .advertisement, .ads",
        ).remove()

        val primaryRoot = document.selectFirst("article")
            ?: document.selectFirst("main")
            ?: document.selectFirst("[role=main]")
            ?: document.body()

        val paragraphs = primaryRoot
            .select("h1, h2, h3, h4, p, li, pre, blockquote, td")
            .map { SearchEngine.normalizeWhitespace(it.text()) }
            .filter { it.length >= 2 }
            .distinct()

        val extractedText = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString(separator = "\n")
        } else {
            normalizeReadableText(primaryRoot.text())
        }
        return title to extractedText
    }

    private fun validateTargetUrl(url: String): String {
        val normalizedUrl = url.trim()
        require(SearchEngine.isValidHttpUrl(normalizedUrl)) {
            "仅支持有效的 http/https 网页链接：$normalizedUrl"
        }
        require(!isDisallowedLocalTarget(normalizedUrl)) {
            "禁止抓取本机或内网保留地址：$normalizedUrl"
        }
        return normalizedUrl
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 6_000
        const val MAX_ALLOWED_CHARS = 24_000
        const val DEFAULT_RAW_MAX_CHARS = 8_000
        const val MAX_ALLOWED_RAW_CHARS = 32_000

        internal fun normalizeReadableText(raw: String): String {
            return raw
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        internal fun isDisallowedLocalTarget(url: String): Boolean {
            val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            if (host.isEmpty()) return true
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) {
                return true
            }
            // 如果是纯 IP 字面量，拦截私网/环回/链路本地地址（不触发在线 DNS 解析）
            val isIpLiteral = host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || host.contains(":")
            if (isIpLiteral) {
                val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
                if (address.isLoopbackAddress ||
                    address.isAnyLocalAddress ||
                    address.isLinkLocalAddress ||
                    address.isSiteLocalAddress
                ) {
                    return true
                }
            }
            return false
        }
    }
}
