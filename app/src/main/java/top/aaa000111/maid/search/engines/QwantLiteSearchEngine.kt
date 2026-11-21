package top.aaa000111.maid.search.engines

import com.fleeksoft.ksoup.nodes.Document
import top.aaa000111.maid.search.RawSearchResult
import top.aaa000111.maid.search.SearchEngine
import top.aaa000111.maid.search.SearchEngineCategory
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.SearchQuery
import top.aaa000111.maid.search.SearchResultFilter
import top.aaa000111.maid.search.TinyModelSearchResultFilter

/**
 * Qwant Lite 搜索引擎封装（`https://lite.qwant.com/`）：
 * 欧洲隐私搜索引擎专为无 JavaScript 环境提供的极简 HTML 版本，结果干净、结构稳定。
 */
class QwantLiteSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val id: String = ENGINE_ID
    override val displayName: String = "Qwant Lite"
    override val category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB
    override val priority: Int = 45

    override fun buildProbeUrl(): String = "https://lite.qwant.com/"

    override fun buildSearchUrl(query: SearchQuery): String {
        val encoded = urlEncode(query.normalizedQuery)
        val safeParam = if (query.safeSearch) "&s=2" else "&s=0"
        return "https://lite.qwant.com/?q=$encoded&t=web$safeParam"
    }

    override fun parseHtmlResults(
        document: Document,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val articles = document.select("section.results article, article.result, div.result")
        val results = ArrayList<RawSearchResult>(articles.size)
        for (article in articles) {
            if (article.hasClass("ad") || article.selectFirst(".ad-label, .sponsored") != null) {
                continue
            }
            val link = article.selectFirst("h2 a[href]")
                ?: article.selectFirst("span.title a[href]")
                ?: article.selectFirst("a[href^=http]")
                ?: continue

            val title = link.text().trim()
            val url = link.absUrl("href").ifBlank { link.attr("href").trim() }
            if (title.isEmpty() || url.isEmpty()) continue

            val snippet = article.selectFirst("p.desc, p.description, p")
                ?.text()
                ?.trim()
                .orEmpty()
            val displayUrl = article.selectFirst("span.url, cite, .url")
                ?.text()
                ?.trim()
                .orEmpty()

            results += RawSearchResult(
                title = title,
                url = url,
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
        return lower.startsWith("https://lite.qwant.com/") || lower.startsWith("https://www.qwant.com/")
    }

    companion object {
        const val ENGINE_ID = "qwant_lite"
    }
}
