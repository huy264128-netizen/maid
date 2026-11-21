package top.aaa000111.maid.search.engines

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import top.aaa000111.maid.search.HttpFetchResponse
import top.aaa000111.maid.search.RawSearchResult
import top.aaa000111.maid.search.SearchEngine
import top.aaa000111.maid.search.SearchEngineCategory
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.SearchQuery
import top.aaa000111.maid.search.SearchResultFilter
import top.aaa000111.maid.search.TinyModelSearchResultFilter

/**
 * arXiv 学术论文搜索引擎封装（官方免费 Atom/XML API：`https://export.arxiv.org/api/query`）：
 * - 使用 Ksoup `Parser.xmlParser()` 直接解析 Atom XML，无广告、无反爬，且国内直连通常可达；
 * - 支持 `extraParams["sortBy"]`：`relevance`（默认） / `submittedDate` / `lastUpdatedDate`。
 */
class ArxivSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val id: String = ENGINE_ID
    override val displayName: String = "arXiv"
    override val category: SearchEngineCategory = SearchEngineCategory.ACADEMIC
    override val priority: Int = 10

    override fun buildProbeUrl(): String =
        "https://export.arxiv.org/api/query?search_query=all:llm&start=0&max_results=1"

    override fun buildSearchUrl(query: SearchQuery): String {
        val raw = query.normalizedQuery
        // 若调用方已显式写了 arXiv 字段前缀（如 ti: / au: / cat: / all:），则保留原式，否则默认按 all: 检索
        val searchExpr = if (Regex("^(all|ti|au|abs|co|jr|cat|id):").containsMatchIn(raw)) {
            raw
        } else {
            "all:$raw"
        }
        val maxResults = (query.maxResults * 2).coerceIn(5, 25)
        val sortBy = normalizeSortBy(query.extraParams[PARAM_SORT_BY])
        return "https://export.arxiv.org/api/query?search_query=${urlEncode(searchExpr)}" +
            "&start=0&max_results=$maxResults&sortBy=$sortBy&sortOrder=descending"
    }

    override fun buildHeaders(query: SearchQuery?): Map<String, String> = mapOf(
        "Accept" to "application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8",
    )

    override fun parseResponse(
        response: HttpFetchResponse,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val document = Ksoup.parse(
            html = response.body,
            baseUri = response.finalUrl,
            parser = Parser.xmlParser(),
        )
        val entries = document.select("entry")
        val results = ArrayList<RawSearchResult>(entries.size)
        for (entry in entries) {
            val title = normalizeWhitespace(entry.selectFirst("title")?.text().orEmpty())
            val absUrl = entry.selectFirst("link[rel=alternate]")?.attr("href")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: entry.selectFirst("id")?.text()?.trim().orEmpty()
            if (title.isEmpty() || absUrl.isEmpty()) continue

            val authors = entry.select("author > name")
                .map { normalizeWhitespace(it.text()) }
                .filter { it.isNotEmpty() }
            val authorsText = when {
                authors.isEmpty() -> ""
                authors.size <= 3 -> authors.joinToString(", ")
                else -> "${authors.take(3).joinToString(", ")} et al."
            }
            val publishedDate = entry.selectFirst("published")?.text()?.trim()?.take(10).orEmpty()
            val primaryCat = entry.selectFirst("arxiv|primary_category, primary_category")
                ?.attr("term")
                ?.trim()
                .orEmpty()
            val summary = normalizeWhitespace(entry.selectFirst("summary")?.text().orEmpty())
            val pdfLink = entry.selectFirst("link[title=pdf]")?.attr("href")?.trim().orEmpty()

            val metaParts = listOfNotNull(
                publishedDate.takeIf { it.isNotEmpty() },
                primaryCat.takeIf { it.isNotEmpty() },
                authorsText.takeIf { it.isNotEmpty() },
            )
            val snippet = buildString {
                if (metaParts.isNotEmpty()) {
                    append("[${metaParts.joinToString(" | ")}] ")
                }
                append(summary.take(320))
                if (pdfLink.isNotEmpty()) {
                    append(" (PDF: $pdfLink)")
                }
            }.trim()

            results += RawSearchResult(
                title = title,
                url = absUrl,
                snippet = snippet,
                engineId = id,
                displayUrl = absUrl,
            )
        }
        return results
    }

    companion object {
        const val ENGINE_ID = "arxiv"
        const val PARAM_SORT_BY = "sortBy"

        internal fun normalizeSortBy(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "submitteddate", "submitted", "newest", "date" -> "submittedDate"
            "lastupdateddate", "updated" -> "lastUpdatedDate"
            else -> "relevance"
        }
    }
}
