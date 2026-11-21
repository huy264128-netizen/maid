package top.aaa000111.maid.search

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLEncoder

/**
 * 搜索引擎抽象基类：
 * 1. 内置 `available` 可用性探测与 TTL 缓存/失败熔断机制，防止被墙、DNS/TLS 超时或反爬验证阻塞搜索；
 * 2. 按 [category] 归属不同检索领域（通用网页 / 学术论文 / 开源代码 / 技术社区 / 百科参考）；
 * 3. 封装标准化搜索管线：`HTTP 抓取 (Ktor)` -> `反爬/拦截检测` -> `解析 (默认 Ksoup HTML，子类可覆写为 XML/JSON)`
 *    -> `规则清洗与去重` -> `SearchResultFilter（默认由 TinyModel 批量判断采纳，不可用时自动安全回退）`。
 */
abstract class SearchEngine(
    protected val httpClient: SearchHttpClient,
    protected val defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    /** 引擎唯一标识（如 `searxng`、`duckduckgo`、`brave`、`bing`、`cn_bing`、`qwant_lite`、`mojeek`、`arxiv`、`github`、`hacker_news`、`wikipedia`） */
    abstract val id: String

    /** 人类可读名称 */
    abstract val displayName: String

    /** 所属搜索引擎分类 */
    abstract val category: SearchEngineCategory

    /** 同分类内的路由优先级（数值越小优先级越高） */
    open val priority: Int = 100

    /** 可用性探测单次超时（毫秒） */
    open val probeTimeoutMillis: Long = SearchHttpClient.DEFAULT_PROBE_TIMEOUT_MS

    /** 搜索请求单次超时（毫秒） */
    open val searchTimeoutMillis: Long = SearchHttpClient.DEFAULT_SEARCH_TIMEOUT_MS

    /** 探测成功后的缓存有效期（毫秒） */
    open val availabilitySuccessTtlMillis: Long = 5L * 60L * 1000L

    /** 探测失败或触发熔断后的冷却期（毫秒） */
    open val availabilityFailureTtlMillis: Long = 90L * 1000L

    @Volatile
    private var lastAvailability: SearchEngineAvailability? = null

    /**
     * 可选的配置前置校验（例如 SearXNG 关闭或未配置时直接返回原因，避免发起无效网络请求）。
     */
    protected open fun configurationUnavailableReason(): String? = null

    /**
     * 发起探测或搜索前的可选准备钩子（例如当本机 PRoot SearXNG 已安装且开启按需自启时拉起进程）。
     */
    protected open suspend fun prepareBeforeRequest() = Unit

    /**
     * 探测当前搜索引擎在当前网络环境下是否可用（是否被墙、超时或触发反爬拦截）。
     * 默认带 TTL 缓存，传入 `forceRefresh = true` 可强制重新探测。
     */
    suspend fun checkAvailability(forceRefresh: Boolean = false): SearchEngineAvailability {
        val configReason = configurationUnavailableReason()
        if (configReason != null) {
            val unavailable = SearchEngineAvailability(
                engineId = id,
                displayName = displayName,
                category = category,
                available = false,
                latencyMs = 0L,
                statusCode = null,
                reason = configReason,
                checkedAtMillis = clockMillis(),
            )
            lastAvailability = unavailable
            return unavailable
        }

        val now = clockMillis()
        if (!forceRefresh) {
            val cached = lastAvailability
            if (cached != null) {
                val ttl = if (cached.available) {
                    availabilitySuccessTtlMillis
                } else {
                    availabilityFailureTtlMillis
                }
                if (now - cached.checkedAtMillis < ttl) {
                    return cached
                }
            }
        }

        prepareBeforeRequest()

        val startedAt = clockMillis()
        val availability = try {
            val response = httpClient.get(
                url = buildProbeUrl(),
                headers = buildHeaders(query = null),
                timeoutMillis = probeTimeoutMillis,
                maxBodyBytes = MAX_PROBE_BODY_BYTES,
            )
            val latency = (clockMillis() - startedAt).coerceAtLeast(0L)
            val blockedReason = detectBlockedReason(response)
            when {
                response.statusCode !in 200..399 -> SearchEngineAvailability(
                    engineId = id,
                    displayName = displayName,
                    category = category,
                    available = false,
                    latencyMs = latency,
                    statusCode = response.statusCode,
                    reason = "HTTP ${response.statusCode}",
                    checkedAtMillis = clockMillis(),
                )
                blockedReason != null -> SearchEngineAvailability(
                    engineId = id,
                    displayName = displayName,
                    category = category,
                    available = false,
                    latencyMs = latency,
                    statusCode = response.statusCode,
                    reason = blockedReason,
                    checkedAtMillis = clockMillis(),
                )
                else -> SearchEngineAvailability(
                    engineId = id,
                    displayName = displayName,
                    category = category,
                    available = true,
                    latencyMs = latency,
                    statusCode = response.statusCode,
                    reason = "OK",
                    checkedAtMillis = clockMillis(),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val latency = (clockMillis() - startedAt).coerceAtLeast(0L)
            SearchEngineAvailability(
                engineId = id,
                displayName = displayName,
                category = category,
                available = false,
                latencyMs = latency,
                statusCode = null,
                reason = error.message?.take(120) ?: error::class.simpleName.orEmpty(),
                checkedAtMillis = clockMillis(),
            )
        }

        lastAvailability = availability
        return availability
    }

    /** 快捷判断该引擎当前是否可用。 */
    suspend fun isAvailable(forceRefresh: Boolean = false): Boolean =
        checkAvailability(forceRefresh = forceRefresh).available

    /** 读取最近一次可用性缓存（不会发起网络请求）。 */
    fun cachedAvailability(): SearchEngineAvailability? = lastAvailability

    /** 清空可用性缓存（例如网络切换或代理/实例配置切换后）。 */
    fun invalidateAvailabilityCache() {
        lastAvailability = null
    }

    /**
     * 执行完整搜索流程：配置校验 -> HTTP 拉取 -> 反爬检测 -> 解析 -> 规则清洗去重 -> Filter（默认 TinyModel 批量采纳）。
     */
    suspend fun search(
        query: SearchQuery,
        filter: SearchResultFilter = defaultFilter,
    ): SearchResponse {
        val configReason = configurationUnavailableReason()
        if (configReason != null) {
            markUnavailable(reason = configReason)
            throw SearchEngineUnavailableException(
                engineId = id,
                message = "[$displayName] $configReason",
            )
        }

        prepareBeforeRequest()

        val startedAt = clockMillis()
        val requestUrl = buildSearchUrl(query)
        val response = try {
            httpClient.get(
                url = requestUrl,
                headers = buildHeaders(query = query),
                timeoutMillis = searchTimeoutMillis,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val reason = error.message ?: error::class.simpleName ?: "Network error"
            markUnavailable(reason = reason)
            throw SearchEngineUnavailableException(
                engineId = id,
                message = "[$displayName] 搜索请求失败：$reason",
                cause = error,
            )
        }

        if (response.statusCode !in 200..299) {
            val reason = "HTTP ${response.statusCode}"
            markUnavailable(reason = reason, statusCode = response.statusCode)
            throw SearchEngineUnavailableException(
                engineId = id,
                statusCode = response.statusCode,
                message = "[$displayName] 返回异常状态码：$reason",
            )
        }

        val blockedReason = detectBlockedReason(response)
        if (blockedReason != null) {
            markUnavailable(reason = blockedReason, statusCode = response.statusCode)
            throw SearchEngineUnavailableException(
                engineId = id,
                statusCode = response.statusCode,
                message = "[$displayName] 疑似触发反爬或访问受限：$blockedReason",
            )
        }

        val parsed = parseResponse(response = response, query = query)
        val cleaned = cleanAndDeduplicate(parsed, maxCandidates = maxOf(query.maxResults * 2, 12))

        markAvailable(
            latencyMs = (clockMillis() - startedAt).coerceAtLeast(0L),
            statusCode = response.statusCode,
        )

        val filtered = filter.filter(query = query, candidates = cleaned)
        val elapsedMs = (clockMillis() - startedAt).coerceAtLeast(0L)
        return SearchResponse(
            query = query.normalizedQuery,
            engineId = id,
            category = category,
            results = filtered.results.take(query.maxResults),
            rawCandidateCount = cleaned.size,
            filterStage = filtered.stage,
            elapsedMs = elapsedMs,
            warning = filtered.warning,
        )
    }

    /** 构造轻量探测 URL（默认直接访问搜索页首页或轻量端点）。 */
    protected abstract fun buildProbeUrl(): String

    /** 构造搜索请求 URL。 */
    protected abstract fun buildSearchUrl(query: SearchQuery): String

    /**
     * 解析原始 HTTP 响应；默认使用 Ksoup 解析 HTML 并委托给 [parseHtmlResults]。
     * XML / JSON 接口类引擎可直接覆写此方法，同时完整复用基类的探测、熔断、清洗与 TinyModel 过滤。
     */
    protected open fun parseResponse(
        response: HttpFetchResponse,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val document = Ksoup.parse(html = response.body, baseUri = response.finalUrl)
        return parseHtmlResults(document, query)
    }

    /** 使用 Ksoup 解析 HTML 搜索结果页面，提取原始候选条目。 */
    protected open fun parseHtmlResults(
        document: Document,
        query: SearchQuery,
    ): List<RawSearchResult> = emptyList()

    /** 按需为特定搜索引擎定制请求头。 */
    protected open fun buildHeaders(query: SearchQuery?): Map<String, String> = emptyMap()

    /** 检测响应是否命中反爬验证码、JS 强制挑战或访问限制页。 */
    protected open fun detectBlockedReason(response: HttpFetchResponse): String? {
        if (response.statusCode == 403 || response.statusCode == 429 || response.statusCode == 503) {
            return "Rate limited or forbidden (HTTP ${response.statusCode})"
        }
        val lowerUrl = response.finalUrl.lowercase()
        if (lowerUrl.contains("/sorry/") || lowerUrl.contains("captcha") || lowerUrl.contains("challenge")) {
            return "Redirected to verification page (${response.finalUrl})"
        }
        val sample = response.body.take(8_000).lowercase()
        return when {
            sample.contains("cf-browser-verification") || sample.contains("just a moment...") ->
                "Cloudflare verification challenge"
            sample.contains("g-recaptcha") || sample.contains("h-captcha") || sample.contains("turnstile") ->
                "CAPTCHA challenge detected"
            sample.contains("unusual traffic from your computer network") ->
                "Unusual traffic block"
            else -> null
        }
    }

    /** 规则级广告或噪音判定，子类可按各自域名特征覆写扩展。 */
    protected open fun isAdOrNoise(candidate: RawSearchResult): Boolean {
        val urlLower = candidate.url.lowercase()
        return urlLower.contains("doubleclick.net") ||
            urlLower.contains("googleadservices.com") ||
            urlLower.contains("/aclick?") ||
            urlLower.contains("ad_domain=")
    }

    protected fun markUnavailable(
        reason: String,
        statusCode: Int? = null,
    ) {
        lastAvailability = SearchEngineAvailability(
            engineId = id,
            displayName = displayName,
            category = category,
            available = false,
            latencyMs = 0L,
            statusCode = statusCode,
            reason = reason,
            checkedAtMillis = clockMillis(),
        )
    }

    protected fun markAvailable(
        latencyMs: Long,
        statusCode: Int? = 200,
    ) {
        lastAvailability = SearchEngineAvailability(
            engineId = id,
            displayName = displayName,
            category = category,
            available = true,
            latencyMs = latencyMs,
            statusCode = statusCode,
            reason = "OK",
            checkedAtMillis = clockMillis(),
        )
    }

    private fun cleanAndDeduplicate(
        rawList: List<RawSearchResult>,
        maxCandidates: Int,
    ): List<RawSearchResult> {
        val seenUrls = HashSet<String>()
        val cleaned = ArrayList<RawSearchResult>(minOf(rawList.size, maxCandidates))

        for (item in rawList) {
            val title = normalizeWhitespace(item.title)
            val url = item.url.trim()
            val snippet = normalizeWhitespace(item.snippet)
            if (title.isEmpty() || !isValidHttpUrl(url)) continue
            val candidate = item.copy(
                title = title,
                url = url,
                snippet = snippet,
                displayUrl = normalizeWhitespace(item.displayUrl),
            )
            if (isAdOrNoise(candidate)) continue
            val canonicalKey = canonicalUrlKey(url)
            if (!seenUrls.add(canonicalKey)) continue
            cleaned += candidate.copy(rank = cleaned.size + 1)
            if (cleaned.size >= maxCandidates) break
        }
        return cleaned
    }

    companion object {
        private const val MAX_PROBE_BODY_BYTES = 128L * 1024L

        fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        fun normalizeWhitespace(text: String): String =
            text.replace(Regex("\\s+"), " ").trim()

        fun isValidHttpUrl(url: String): Boolean {
            val lower = url.trim().lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            return runCatching {
                val uri = URI(url.trim())
                !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }

        fun canonicalUrlKey(url: String): String {
            return runCatching {
                val uri = URI(url.trim())
                val host = uri.host.orEmpty().lowercase().removePrefix("www.")
                val path = uri.rawPath.orEmpty().trimEnd('/')
                val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
                "$host$path$query"
            }.getOrElse { url.trim().lowercase().trimEnd('/') }
        }
    }
}
