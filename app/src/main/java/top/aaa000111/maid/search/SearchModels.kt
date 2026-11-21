package top.aaa000111.maid.search

/**
 * 搜索引擎按用途划分的垂直/通用分类：
 * 路由器仅在同分类引擎之间进行故障转移，防止通用搜索误落入学术或代码接口。
 */
enum class SearchEngineCategory(
    val storageKey: String,
    val displayName: String,
) {
    /** 通用网页搜索（SearXNG / DuckDuckGo / Brave / Bing / CN Bing / Qwant Lite / Mojeek） */
    GENERAL_WEB("general_web", "通用网页搜索"),

    /** 学术论文与预印本（arXiv） */
    ACADEMIC("academic", "学术论文"),

    /** 开源仓库、代码与 Issue（GitHub） */
    DEVELOPER("developer", "开源代码与 Issue"),

    /** 开发者与技术社区讨论（Hacker News） */
    TECH_COMMUNITY("tech_community", "技术社区讨论"),

    /** 百科与词条参考（Wikipedia） */
    ENCYCLOPEDIA("encyclopedia", "百科参考"),
    ;

    companion object {
        fun fromKeyOrNull(raw: String?): SearchEngineCategory? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty() || normalized == "all") return null
            return entries.firstOrNull {
                it.storageKey.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}

/**
 * 搜索引擎查询参数。
 */
data class SearchQuery(
    val query: String,
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    val language: String? = null,
    val safeSearch: Boolean = false,
    val extraParams: Map<String, String> = emptyMap(),
) {
    init {
        require(query.isNotBlank()) { "Search query must not be blank" }
        require(maxResults > 0) { "maxResults must be > 0, got $maxResults" }
    }

    val normalizedQuery: String
        get() = query.trim().replace(Regex("\\s+"), " ")

    companion object {
        const val DEFAULT_MAX_RESULTS = 8
        const val MAX_ALLOWED_RESULTS = 20
    }
}

/**
 * 经 Ksoup / XML / JSON 解析并完成基础规则清洗后的候选条目。
 */
data class RawSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val engineId: String,
    val displayUrl: String = "",
    val rank: Int = 0,
)

/**
 * 最终返回给上层（如 ResearchAgent）的搜索结果条目。
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val engineId: String,
    val displayUrl: String = "",
    val rank: Int = 0,
    val acceptedByTinyModel: Boolean? = null,
)

/**
 * 搜索引擎过滤阶段状态。
 */
enum class SearchFilterStage {
    /** 未启用额外过滤 */
    NONE,

    /** 仅经过 Ksoup / 解析器与规则清洗 */
    RULE_ONLY,

    /** 经预筛后由 TinyModel 批量判断采纳 */
    TINY_MODEL_ACCEPTED,

    /** TinyModel 未就绪、超时或输出异常，已安全回退为规则清洗结果 */
    TINY_MODEL_FALLBACK,
}

/**
 * 单次搜索引擎可用性探测结果（用于防墙、防超时、防反爬拦截）。
 */
data class SearchEngineAvailability(
    val engineId: String,
    val displayName: String,
    val category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB,
    val available: Boolean,
    val latencyMs: Long = 0L,
    val statusCode: Int? = null,
    val reason: String = "",
    val checkedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * 一次搜索调用的完整响应。
 */
data class SearchResponse(
    val query: String,
    val engineId: String,
    val category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB,
    val results: List<SearchResult>,
    val rawCandidateCount: Int,
    val filterStage: SearchFilterStage,
    val elapsedMs: Long,
    val warning: String? = null,
)

/**
 * 经 Ksoup 清洗去噪后的网页正文抓取结果（供 ResearchAgent 深入核验来源细节）。
 */
data class WebPageSnapshot(
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    val content: String,
    val statusCode: Int,
    val truncated: Boolean,
    val originalCharLength: Int,
)

/**
 * 未经 Ksoup 剔除标签的原始网页/文档文本抓取结果（适用于 HTML 源码、JSON/XML/Markdown 原始文件、结构化元数据等）。
 */
data class RawWebPageSnapshot(
    val requestedUrl: String,
    val finalUrl: String,
    val contentType: String,
    val statusCode: Int,
    val content: String,
    val offsetChars: Int,
    val totalCharLength: Int,
    val truncated: Boolean,
)

/**
 * 搜索引擎不可用异常（网络不可达、被墙、超时或触发反爬验证）。
 */
class SearchEngineUnavailableException(
    val engineId: String,
    val statusCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
