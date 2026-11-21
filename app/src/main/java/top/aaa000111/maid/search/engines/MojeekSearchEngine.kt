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
 * Mojeek 独立爬虫搜索引擎封装（`https://www.mojeek.com/search`）：
 * 拥有完全独立的网页索引（非 Google / Bing 聚合），纯静态 HTML，零追踪且低广告污染。
 */
class MojeekSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val id: String = ENGINE_ID
    override val displayName: String = "Mojeek"
    override val category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB
    override val priority: Int = 48

    override fun buildProbeUrl(): String = "https://www.mojeek.com/"

    override fun buildSearchUrl(query: SearchQuery): String {
        val encoded = urlEncode(query.normalizedQuery)
        val safeParam = if (query.safeSearch) "&safe=1" else ""
        return "https://www.mojeek.com/search?q=$encoded$safeParam"
    }

    override fun parseHtmlResults(
        document: Document,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val items = document.select("ul.results-standard > li, ul.results > li")
        val results = ArrayList<RawSearchResult>(items.size)
        for (item in items) {
            val link = item.selectFirst("h2 a.title[href]")
                ?: item.selectFirst("a.title[href]")
                ?: item.selectFirst("h2 a[href]")
                ?: continue

            val title = link.text().trim()
            val url = link.absUrl("href").ifBlank { link.attr("href").trim() }
            if (title.isEmpty() || url.isEmpty()) continue

            val snippet = item.selectFirst("p.s, p.desc, p")
                ?.text()
                ?.trim()
                .orEmpty()
            val displayUrl = item.selectFirst("p.i a.ob, a.ob, .url")
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
        return candidate.url.lowercase().startsWith("https://www.mojeek.com/")
    }

    companion object {
        const val ENGINE_ID = "mojeek"
    }
}
