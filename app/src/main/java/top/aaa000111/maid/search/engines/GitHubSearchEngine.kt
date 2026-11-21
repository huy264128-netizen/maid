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
 * GitHub 开源仓库与 Issue 搜索引擎封装（官方公开 Search REST API，免 Key 匿名可用）：
 * - 支持 `extraParams["scope"]`：`repositories`（默认，搜开源仓库）或 `issues`（搜 Issue / PR 讨论）；
 * - 使用 `kotlinx.serialization.json` 解析结构化字段（星标数、语言、状态、更新时间与摘要）。
 */
class GitHubSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val id: String = ENGINE_ID
    override val displayName: String = "GitHub Search"
    override val category: SearchEngineCategory = SearchEngineCategory.DEVELOPER
    override val priority: Int = 10

    override fun buildProbeUrl(): String = "https://api.github.com/rate_limit"

    override fun buildSearchUrl(query: SearchQuery): String {
        val scope = normalizeScope(query.extraParams[PARAM_SCOPE])
        val endpoint = if (scope == SCOPE_ISSUES) "issues" else "repositories"
        val perPage = (query.maxResults * 2).coerceIn(5, 20)
        return "https://api.github.com/search/$endpoint?q=${urlEncode(query.normalizedQuery)}&per_page=$perPage"
    }

    override fun buildHeaders(query: SearchQuery?): Map<String, String> = mapOf(
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "Maid-ResearchAgent/1.0",
    )

    override fun parseResponse(
        response: HttpFetchResponse,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: return emptyList()
        val items = root["items"]?.jsonArray ?: return emptyList()
        val scope = normalizeScope(query.extraParams[PARAM_SCOPE])
        val results = ArrayList<RawSearchResult>(items.size)

        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (htmlUrl.isEmpty()) continue

            if (scope == SCOPE_ISSUES) {
                val issueTitle = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (issueTitle.isEmpty()) continue
                val state = obj["state"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val comments = obj["comments"]?.jsonPrimitive?.intOrNull ?: 0
                val updated = obj["updated_at"]?.jsonPrimitive?.contentOrNull?.take(10).orEmpty()
                val bodyPreview = normalizeWhitespace(
                    obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ).take(260)
                val repoPath = obj["repository_url"]?.jsonPrimitive?.contentOrNull
                    ?.substringAfter("/repos/")
                    .orEmpty()
                val meta = listOfNotNull(
                    repoPath.takeIf { it.isNotEmpty() },
                    state.takeIf { it.isNotEmpty() },
                    "${comments} comments",
                    updated.takeIf { it.isNotEmpty() },
                ).joinToString(" | ")
                results += RawSearchResult(
                    title = if (repoPath.isNotEmpty()) "[$repoPath] $issueTitle" else issueTitle,
                    url = htmlUrl,
                    snippet = if (bodyPreview.isNotEmpty()) "[$meta] $bodyPreview" else "[$meta]",
                    engineId = id,
                    displayUrl = htmlUrl,
                )
            } else {
                val fullName = obj["full_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (fullName.isEmpty()) continue
                val stars = obj["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0
                val language = obj["language"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val updated = obj["updated_at"]?.jsonPrimitive?.contentOrNull?.take(10).orEmpty()
                val description = normalizeWhitespace(
                    obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ).take(280)
                val meta = listOfNotNull(
                    "★$stars",
                    language.takeIf { it.isNotEmpty() },
                    updated.takeIf { it.isNotEmpty() }?.let { "updated $it" },
                ).joinToString(" | ")
                results += RawSearchResult(
                    title = fullName,
                    url = htmlUrl,
                    snippet = if (description.isNotEmpty()) "[$meta] $description" else "[$meta]",
                    engineId = id,
                    displayUrl = htmlUrl,
                )
            }
        }
        return results
    }

    companion object {
        const val ENGINE_ID = "github"
        const val PARAM_SCOPE = "scope"
        const val SCOPE_REPOSITORIES = "repositories"
        const val SCOPE_ISSUES = "issues"

        private val json = Json { ignoreUnknownKeys = true }

        internal fun normalizeScope(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "issues", "issue", "pr", "prs", "bugs" -> SCOPE_ISSUES
            else -> SCOPE_REPOSITORIES
        }
    }
}
