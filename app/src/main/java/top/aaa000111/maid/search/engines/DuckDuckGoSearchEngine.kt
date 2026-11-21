package top.aaa000111.maid.search.engines

import com.fleeksoft.ksoup.nodes.Document
import top.aaa000111.maid.search.HttpFetchResponse
import top.aaa000111.maid.search.RawSearchResult
import top.aaa000111.maid.search.SearchEngine
import top.aaa000111.maid.search.SearchEngineCategory
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.SearchQuery
import top.aaa000111.maid.search.SearchResultFilter
import top.aaa000111.maid.search.TinyModelSearchResultFilter
import java.net.URI
import java.net.URLDecoder

/**
 * DuckDuckGo 静态 HTML 搜索引擎封装（`https://html.duckduckgo.com/html/`）：
 * 专为无 JavaScript 环境设计，结果干净、无广告干扰（会自动过滤 `.result--ad` 并解码 `uddg` 跳转链接）。
 */
class DuckDuckGoSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val id: String = ENGINE_ID
    override val displayName: String = "DuckDuckGo"
    override val category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB
    override val priority: Int = 10

    override fun buildProbeUrl(): String = BASE_URL

    override fun buildSearchUrl(query: SearchQuery): String {
        val encoded = urlEncode(query.normalizedQuery)
        val safeParam = if (query.safeSearch) "&kp=1" else ""
        return "$BASE_URL?q=$encoded$safeParam"
    }

    override fun buildHeaders(query: SearchQuery?): Map<String, String> = mapOf(
        "Referer" to BASE_URL,
    )

    override fun detectBlockedReason(response: HttpFetchResponse): String? {
        super.detectBlockedReason(response)?.let { return it }
        val sample = response.body.take(8_000).lowercase()
        if (sample.contains("anomaly-modal") ||
            sample.contains("bots use duckduckgo") ||
            sample.contains("please complete the following challenge")
        ) {
            return "DuckDuckGo anti-bot challenge detected"
        }
        return null
    }

    override fun parseHtmlResults(
        document: Document,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val blocks = document.select("div.result, div.web-result")
        val results = ArrayList<RawSearchResult>(blocks.size)
        for (block in blocks) {
            if (block.hasClass("result--ad") || block.selectFirst(".badge--ad") != null) {
                continue
            }
            val link = block.selectFirst("a.result__a")
                ?: block.selectFirst("h2.result__title a")
                ?: continue
            val title = link.text().trim()
            val rawHref = link.attr("href").trim()
            val resolvedUrl = resolveDuckDuckGoUrl(rawHref)
            if (title.isEmpty() || resolvedUrl.isEmpty()) continue

            val snippet = block.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            val displayUrl = block.selectFirst(".result__url")?.text()?.trim().orEmpty()
            results += RawSearchResult(
                title = title,
                url = resolvedUrl,
                snippet = snippet,
                engineId = id,
                displayUrl = displayUrl,
            )
        }
        return results
    }

    override fun isAdOrNoise(candidate: RawSearchResult): Boolean {
        if (super.isAdOrNoise(candidate)) return true
        val lower = candidate.url.lowercase()
        return lower.contains("duckduckgo.com/y.js") || lower.contains("duckduckgo.com/l/?")
    }

    companion object {
        const val ENGINE_ID = "duckduckgo"
        private const val BASE_URL = "https://html.duckduckgo.com/html/"

        /**
         * 解码 DuckDuckGo 常见跳转链接，如 `//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com&rut=...`
         */
        internal fun resolveDuckDuckGoUrl(rawHref: String): String {
            val trimmed = rawHref.trim()
            if (trimmed.isEmpty()) return ""
            val normalized = when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/") -> "https://duckduckgo.com$trimmed"
                else -> trimmed
            }
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized
            val host = uri.host.orEmpty().lowercase()
            if (host.endsWith("duckduckgo.com") && uri.path.orEmpty().startsWith("/l")) {
                val rawQuery = uri.rawQuery.orEmpty()
                val uddg = rawQuery.split('&')
                    .firstOrNull { it.startsWith("uddg=") }
                    ?.substringAfter("uddg=")
                if (!uddg.isNullOrBlank()) {
                    val decoded = runCatching {
                        URLDecoder.decode(uddg, Charsets.UTF_8.name())
                    }.getOrNull()
                    if (!decoded.isNullOrBlank()) {
                        return decoded
                    }
                }
            }
            return normalized
        }
    }
}
