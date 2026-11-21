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

/**
 * Brave Search 搜索引擎封装（`https://search.brave.com/search`）：
 * 独立网页索引，静态 HTML 结构清晰、SEO 垃圾少，适合深度资料调研。
 */
class BraveSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val id: String = ENGINE_ID
    override val displayName: String = "Brave Search"
    override val category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB
    override val priority: Int = 20

    override fun buildProbeUrl(): String = "https://search.brave.com/"

    override fun buildSearchUrl(query: SearchQuery): String {
        val encoded = urlEncode(query.normalizedQuery)
        val safeParam = if (query.safeSearch) "&safesearch=strict" else ""
        return "https://search.brave.com/search?q=$encoded&source=web$safeParam"
    }

    override fun detectBlockedReason(response: HttpFetchResponse): String? {
        super.detectBlockedReason(response)?.let { return it }
        val sample = response.body.take(8_000).lowercase()
        if (sample.contains("verify you are human") || sample.contains("pow-challenge")) {
            return "Brave Search anti-bot challenge detected"
        }
        return null
    }

    override fun parseHtmlResults(
        document: Document,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val blocks = document.select("div.snippet[data-type=web], div[data-type=web], div.result-wrapper")
        val results = ArrayList<RawSearchResult>(blocks.size)
        for (block in blocks) {
            if (block.hasClass("standalone-ad") || block.selectFirst(".ad-badge") != null) {
                continue
            }
            val link = block.selectFirst("a.heading-serpresult")
                ?: block.selectFirst("a.result-header")
                ?: block.selectFirst("a[href^=http]")
                ?: continue

            val href = link.absUrl("href").ifBlank { link.attr("href").trim() }
            val title = block.selectFirst(".title, .snippet-title")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: link.text().trim()
            if (title.isEmpty() || href.isEmpty()) continue

            val snippet = block.selectFirst(".snippet-description, .generic-snippet, .content, p")
                ?.text()
                ?.trim()
                .orEmpty()
            val displayUrl = block.selectFirst(".snippet-url, cite, .url")
                ?.text()
                ?.trim()
                .orEmpty()

            results += RawSearchResult(
                title = title,
                url = href,
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
        return lower.startsWith("https://search.brave.com/")
    }

    companion object {
        const val ENGINE_ID = "brave"
    }
}
