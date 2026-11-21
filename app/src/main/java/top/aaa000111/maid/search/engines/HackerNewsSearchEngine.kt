package top.aaa000111.maid.search.engines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.aaa000111.maid.search.HttpFetchResponse
import top.aaa000111.maid.search.RawSearchResult
import top.aaa000111.maid.search.SearchEngine
import top.aaa000111.maid.search.SearchEngineCategory
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.SearchQuery
import top.aaa000111.maid.search.SearchResultFilter
import top.aaa000111.maid.search.TinyModelSearchResultFilter

/**
 * Hacker News 技术社区讨论搜索引擎封装（官方免费 Algolia Search API）：
 * - 免 Key、极速响应、零广告；
 * - 支持 `extraParams["sortBy"]`：`relevance`（默认，走 `/api/v1/search`）或 `date`（最新，走 `/api/v1/search_by_date`）；
 * - 返回讨论标题、点赞数、评论数、作者、发布日期、外部原文链接与 HN 讨论帖链接。
 */
class HackerNewsSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val id: String = ENGINE_ID
    override val displayName: String = "Hacker News"
    override val category: SearchEngineCategory = SearchEngineCategory.TECH_COMMUNITY
    override val priority: Int = 10

    override fun buildProbeUrl(): String =
        "https://hn.algolia.com/api/v1/search?query=android&tags=story&hitsPerPage=1"

    override fun buildSearchUrl(query: SearchQuery): String {
        val endpoint = if (isSortByDate(query.extraParams[PARAM_SORT_BY])) {
            "search_by_date"
        } else {
            "search"
        }
        val hitsPerPage = (query.maxResults * 2).coerceIn(6, 20)
        return "https://hn.algolia.com/api/v1/$endpoint?query=${urlEncode(query.normalizedQuery)}" +
            "&tags=story&hitsPerPage=$hitsPerPage"
    }

    override fun buildHeaders(query: SearchQuery?): Map<String, String> = mapOf(
        "Accept" to "application/json",
    )

    override fun parseResponse(
        response: HttpFetchResponse,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: return emptyList()
        val hits = root["hits"]?.jsonArray ?: return emptyList()
        val results = ArrayList<RawSearchResult>(hits.size)

        for (element in hits) {
            val obj = element as? JsonObject ?: continue
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: obj["story_title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) continue

            val objectId = obj["objectID"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val hnDiscussionUrl = if (objectId.isNotEmpty()) {
                "https://news.ycombinator.com/item?id=$objectId"
            } else {
                ""
            }
            val articleUrl = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val primaryUrl = articleUrl.ifEmpty { hnDiscussionUrl }
            if (primaryUrl.isEmpty()) continue

            val points = obj["points"]?.jsonPrimitive?.intOrNull ?: 0
            val numComments = obj["num_comments"]?.jsonPrimitive?.intOrNull ?: 0
            val author = obj["author"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull?.take(10).orEmpty()
            val storyText = normalizeWhitespace(
                obj["story_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ).take(220)

            val meta = listOfNotNull(
                "${points} pts",
                "${numComments} comments",
                author.takeIf { it.isNotEmpty() }?.let { "by $it" },
                createdAt.takeIf { it.isNotEmpty() },
            ).joinToString(" | ")

            val snippet = buildString {
                append("[$meta]")
                if (storyText.isNotEmpty()) {
                    append(" $storyText")
                }
                if (hnDiscussionUrl.isNotEmpty() && hnDiscussionUrl != primaryUrl) {
                    append(" | HN 讨论: $hnDiscussionUrl")
                }
            }.trim()

            results += RawSearchResult(
                title = title,
                url = primaryUrl,
                snippet = snippet,
                engineId = id,
                displayUrl = primaryUrl,
            )
        }
        return results
    }

    companion object {
        const val ENGINE_ID = "hacker_news"
        const val PARAM_SORT_BY = "sortBy"

        private val json = Json { ignoreUnknownKeys = true }

        internal fun isSortByDate(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
            "date", "newest", "latest", "time" -> true
            else -> false
        }
    }
}
