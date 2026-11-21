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
 * Wikipedia 百科搜索引擎封装：
 * - 根据查询是否包含中文字符自动路由至 `zh.wikipedia.org` 或 `en.wikipedia.org`；
 * - 静态 HTML 结构极度稳定且信息权威，非常适合 ResearchAgent 查证基础概念与事实；
 * - 无代理环境下会由基类的 `isAvailable()` 自动探测跳过。
 */
class WikipediaSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val id: String = ENGINE_ID
    override val displayName: String = "Wikipedia"
    override val category: SearchEngineCategory = SearchEngineCategory.ENCYCLOPEDIA
    override val priority: Int = 10

    override fun buildProbeUrl(): String = "https://en.wikipedia.org/wiki/Main_Page"

    override fun buildSearchUrl(query: SearchQuery): String {
        val host = resolveWikipediaHost(query)
        val encoded = urlEncode(query.normalizedQuery)
        val limit = query.maxResults.coerceIn(5, 20)
        return "https://$host/w/index.php?search=$encoded&title=Special%3ASearch&fulltext=1&limit=$limit"
    }

    override fun parseHtmlResults(
        document: Document,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val items = document.select("li.mw-search-result")
        val results = ArrayList<RawSearchResult>(items.size)
        for (item in items) {
            val link = item.selectFirst(".mw-search-result-heading a[href]") ?: continue
            val title = link.text().trim()
            val url = link.absUrl("href").ifBlank { link.attr("href").trim() }
            if (title.isEmpty() || url.isEmpty()) continue

            val snippet = item.selectFirst(".searchresult")?.text()?.trim().orEmpty()
            val meta = item.selectFirst(".mw-search-result-data")?.text()?.trim().orEmpty()
            val combinedSnippet = listOf(snippet, meta).filter { it.isNotBlank() }.joinToString(" · ")

            results += RawSearchResult(
                title = title,
                url = url,
                snippet = combinedSnippet,
                engineId = id,
                displayUrl = url,
            )
        }
        return results
    }

    companion object {
        const val ENGINE_ID = "wikipedia"

        internal fun resolveWikipediaHost(query: SearchQuery): String {
            val lang = query.language?.trim()?.lowercase().orEmpty()
            if (lang.startsWith("zh")) return "zh.wikipedia.org"
            if (lang.startsWith("en")) return "en.wikipedia.org"
            val hasCjk = query.normalizedQuery.any { ch ->
                ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'
            }
            return if (hasCjk) "zh.wikipedia.org" else "en.wikipedia.org"
        }
    }
}
