package top.aaa000111.maid.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.aaa000111.maid.search.engines.ArxivSearchEngine
import top.aaa000111.maid.search.engines.BingSearchEngine
import top.aaa000111.maid.search.engines.BraveSearchEngine
import top.aaa000111.maid.search.engines.CnBingSearchEngine
import top.aaa000111.maid.search.engines.DuckDuckGoSearchEngine
import top.aaa000111.maid.search.engines.GitHubSearchEngine
import top.aaa000111.maid.search.engines.HackerNewsSearchEngine
import top.aaa000111.maid.search.engines.MojeekSearchEngine
import top.aaa000111.maid.search.engines.QwantLiteSearchEngine
import top.aaa000111.maid.search.engines.SearXngSearchEngine
import top.aaa000111.maid.search.engines.WikipediaSearchEngine

/**
 * 多引擎分类路由与聚合调度器：
 * - 按 [SearchEngineCategory] 隔离不同领域引擎，确保通用网页回退不会误落入学术或代码接口；
 * - 并行执行各引擎 `available` 探测，自动避开被墙、未配置或触发反爬的引擎；
 * - 支持同分类内自动故障转移与多引擎并行联合检索去重。
 */
class SearchEngineRouter(
    val engines: List<SearchEngine>,
    private val ownedHttpClient: SearchHttpClient? = null,
    private val defaultTinyModelFilter: SearchResultFilter = TinyModelSearchResultFilter(),
) : AutoCloseable {

    init {
        require(engines.isNotEmpty()) { "SearchEngineRouter requires at least one SearchEngine" }
    }

    /** 返回指定分类（或全部）引擎，按优先级升序排列。 */
    fun enginesIn(category: SearchEngineCategory? = null): List<SearchEngine> {
        val matched = if (category == null) {
            engines
        } else {
            engines.filter { it.category == category }
        }
        return matched.sortedWith(compareBy<SearchEngine> { it.category.ordinal }.thenBy { it.priority })
    }

    /** 并行探测全部或指定分类搜索引擎的可用性。 */
    suspend fun probeAll(
        category: SearchEngineCategory? = null,
        forceRefresh: Boolean = false,
    ): List<SearchEngineAvailability> = coroutineScope {
        enginesIn(category)
            .map { engine ->
                async { engine.checkAvailability(forceRefresh = forceRefresh) }
            }
            .awaitAll()
    }

    /** 清空全部（或指定引擎）的可用性缓存（例如网络切换或修改 SearXNG 实例地址后）。 */
    fun invalidateAllAvailabilityCaches() {
        engines.forEach { it.invalidateAvailabilityCache() }
    }

    /** 根据 ID 查找指定引擎。 */
    fun findEngine(engineId: String): SearchEngine? {
        val normalized = engineId.trim()
        if (normalized.isEmpty()) return null
        return engines.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
    }

    /**
     * 在指定 [category] 分类下使用最合适的可用引擎执行搜索；若遇阻则严格在同分类内顺延回退。
     */
    suspend fun search(
        query: SearchQuery,
        category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB,
        preferredEngineId: String? = null,
        useTinyModelFilter: Boolean = true,
        forceProbeRefresh: Boolean = false,
    ): SearchResponse {
        val filter = if (useTinyModelFilter) defaultTinyModelFilter else RuleOnlySearchResultFilter
        val orderedEngines = resolveCandidateEngines(
            category = category,
            preferredEngineId = preferredEngineId,
            forceProbeRefresh = forceProbeRefresh,
        )
        if (orderedEngines.isEmpty()) {
            throw SearchEngineUnavailableException(
                engineId = preferredEngineId ?: category.storageKey,
                message = "分类「${category.displayName}」下尚未注册任何可用搜索引擎。",
            )
        }

        val failures = mutableListOf<String>()
        for (engine in orderedEngines) {
            try {
                val response = engine.search(query = query, filter = filter)
                if (response.results.isNotEmpty() || orderedEngines.size == 1) {
                    return response
                }
                failures += "${engine.id}: 返回 0 条有效结果"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failures += "${engine.id}: ${error.message ?: error::class.simpleName}"
            }
        }

        throw SearchEngineUnavailableException(
            engineId = preferredEngineId ?: category.storageKey,
            message = "分类「${category.displayName}」下所有搜索引擎均不可用或未返回结果：${failures.joinToString("; ")}",
        )
    }

    /**
     * 在指定 [category] 内并行向最多 [maxEngines] 个可用引擎发起搜索，合并去重后统一执行（可选的）TinyModel 批量筛选。
     */
    suspend fun searchMultiEngine(
        query: SearchQuery,
        category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB,
        maxEngines: Int = 2,
        useTinyModelFilter: Boolean = true,
    ): SearchResponse = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val candidates = resolveCandidateEngines(
            category = category,
            preferredEngineId = null,
            forceProbeRefresh = false,
        ).take(maxEngines.coerceAtLeast(1))

        if (candidates.size <= 1) {
            return@coroutineScope search(
                query = query,
                category = category,
                preferredEngineId = candidates.firstOrNull()?.id,
                useTinyModelFilter = useTinyModelFilter,
            )
        }

        val perEngineQuery = query.copy(maxResults = maxOf(query.maxResults, 6))
        val deferred = candidates.map { engine ->
            async {
                runCatching {
                    engine.search(query = perEngineQuery, filter = RuleOnlySearchResultFilter)
                }.getOrNull()
            }
        }
        val succeeded = deferred.awaitAll().filterNotNull()
        if (succeeded.isEmpty()) {
            return@coroutineScope search(
                query = query,
                category = category,
                preferredEngineId = null,
                useTinyModelFilter = useTinyModelFilter,
                forceProbeRefresh = true,
            )
        }

        val seenKeys = HashSet<String>()
        val mergedRaw = mutableListOf<RawSearchResult>()
        val usedEngineIds = mutableListOf<String>()
        for (resp in succeeded) {
            if (resp.results.isNotEmpty()) {
                usedEngineIds += resp.engineId
            }
            for (item in resp.results) {
                val key = SearchEngine.canonicalUrlKey(item.url)
                if (seenKeys.add(key)) {
                    mergedRaw += RawSearchResult(
                        title = item.title,
                        url = item.url,
                        snippet = item.snippet,
                        engineId = item.engineId,
                        displayUrl = item.displayUrl,
                        rank = mergedRaw.size + 1,
                    )
                }
            }
        }

        val filter = if (useTinyModelFilter) defaultTinyModelFilter else RuleOnlySearchResultFilter
        val filtered = filter.filter(query = query, candidates = mergedRaw)
        SearchResponse(
            query = query.normalizedQuery,
            engineId = usedEngineIds.ifEmpty { candidates.map { it.id } }.joinToString("+"),
            category = category,
            results = filtered.results.take(query.maxResults),
            rawCandidateCount = mergedRaw.size,
            filterStage = filtered.stage,
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            warning = filtered.warning,
        )
    }

    private suspend fun resolveCandidateEngines(
        category: SearchEngineCategory,
        preferredEngineId: String?,
        forceProbeRefresh: Boolean,
    ): List<SearchEngine> {
        val categoryEngines = enginesIn(category)
        if (categoryEngines.isEmpty()) return emptyList()

        val preferred = preferredEngineId?.let { id ->
            categoryEngines.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
        }
        if (preferred != null) {
            val probe = preferred.checkAvailability(forceRefresh = forceProbeRefresh)
            if (probe.available) {
                val rest = categoryEngines.filter { it.id != preferred.id }
                return listOf(preferred) + rest
            }
        }

        val probeResults = probeAll(category = category, forceRefresh = forceProbeRefresh)
        val availabilityById = probeResults.associateBy { it.engineId }
        val available = categoryEngines.filter { availabilityById[it.id]?.available == true }
        val unavailable = categoryEngines.filter { availabilityById[it.id]?.available != true }
        return if (available.isNotEmpty()) {
            if (preferred != null && preferred in available) {
                listOf(preferred) + available.filter { it.id != preferred.id }
            } else {
                available
            }
        } else {
            // 全部探测失败时仍按优先级顺序尝试一次，避免探测端点偶发误判导致彻底罢工
            unavailable
        }
    }

    override fun close() {
        ownedHttpClient?.close()
    }

    companion object {
        /**
         * 创建默认搜索引擎路由器（11 个干净引擎，涵盖 5 大垂直/通用分类，不含百度）：
         * - GENERAL_WEB: SearXNG (自定义) / DuckDuckGo / Brave / Bing / CN Bing / Qwant Lite / Mojeek
         * - ACADEMIC: arXiv
         * - DEVELOPER: GitHub Search (repos & issues)
         * - TECH_COMMUNITY: Hacker News
         * - ENCYCLOPEDIA: Wikipedia
         */
        fun createDefault(
            httpClient: SearchHttpClient = KtorSearchHttpClient.createDefault(),
            ownsHttpClient: Boolean = true,
            filter: SearchResultFilter = TinyModelSearchResultFilter(),
            searxngBaseUrlProvider: (() -> String?)? = null,
        ): SearchEngineRouter {
            val searxngEngine = if (searxngBaseUrlProvider != null) {
                SearXngSearchEngine(
                    httpClient = httpClient,
                    defaultFilter = filter,
                    baseUrlProvider = searxngBaseUrlProvider,
                )
            } else {
                SearXngSearchEngine(
                    httpClient = httpClient,
                    defaultFilter = filter,
                    configProvider = { SearchConfigRepository.cachedConfig.value },
                )
            }
            val defaultEngines = listOf(
                // GENERAL_WEB
                searxngEngine,
                DuckDuckGoSearchEngine(httpClient = httpClient, defaultFilter = filter),
                BraveSearchEngine(httpClient = httpClient, defaultFilter = filter),
                BingSearchEngine(httpClient = httpClient, defaultFilter = filter),
                CnBingSearchEngine(httpClient = httpClient, defaultFilter = filter),
                QwantLiteSearchEngine(httpClient = httpClient, defaultFilter = filter),
                MojeekSearchEngine(httpClient = httpClient, defaultFilter = filter),
                // ACADEMIC
                ArxivSearchEngine(httpClient = httpClient, defaultFilter = filter),
                // DEVELOPER
                GitHubSearchEngine(httpClient = httpClient, defaultFilter = filter),
                // TECH_COMMUNITY
                HackerNewsSearchEngine(httpClient = httpClient, defaultFilter = filter),
                // ENCYCLOPEDIA
                WikipediaSearchEngine(httpClient = httpClient, defaultFilter = filter),
            )
            return SearchEngineRouter(
                engines = defaultEngines,
                ownedHttpClient = if (ownsHttpClient) httpClient else null,
                defaultTinyModelFilter = filter,
            )
        }
    }
}
