package top.aaa000111.maid.search.engines

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.aaa000111.maid.search.HttpFetchResponse
import top.aaa000111.maid.search.ProotSearXngManager
import top.aaa000111.maid.search.RawSearchResult
import top.aaa000111.maid.search.SearchConfigRepository
import top.aaa000111.maid.search.SearchEngine
import top.aaa000111.maid.search.SearchEngineCategory
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.SearchQuery
import top.aaa000111.maid.search.SearchResultFilter
import top.aaa000111.maid.search.SearchSettingsConfig
import top.aaa000111.maid.search.SearxngMode
import top.aaa000111.maid.search.TinyModelSearchResultFilter

/**
 * SearXNG 可选元搜索引擎封装：
 * - 支持三种模式（通过 [SearchSettingsConfig] 配置，默认关闭）：
 *   1. `DISABLED`：直接跳过，零网络与内存开销；
 *   2. `EXTERNAL`：连接用户自建或信任的远程 SearXNG 实例；
 *   3. `PROOT_LOCAL`：连接内置 PRoot Linux 容器按需托管的本机 SearXNG（`http://127.0.0.1:<port>`），
 *      默认优先请求 `format=json`，若开启「按需自动启动」且已安装则在探测/搜索前自动拉起。
 */
class SearXngSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    private val configProvider: () -> SearchSettingsConfig = {
        SearchConfigRepository.cachedConfig.value
    },
    private val localInstalledCheck: () -> Boolean = { ProotSearXngManager.isInstalledQuick() },
    private val localAutoStartHook: suspend (port: Int) -> Boolean = { port ->
        ProotSearXngManager.ensureRunningIfInstalled(port)
    },
    private val forcePreferJsonFormat: Boolean? = null,
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    /** 便于单测或直接传入固定 URL 的便捷构造函数。 */
    constructor(
        httpClient: SearchHttpClient,
        defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
        baseUrlProvider: () -> String?,
        preferJsonFormat: Boolean = false,
        clockMillis: () -> Long = { System.currentTimeMillis() },
    ) : this(
        httpClient = httpClient,
        defaultFilter = defaultFilter,
        configProvider = {
            val url = SearchSettingsConfig.normalizeSearxngUrl(baseUrlProvider())
            if (url == null) {
                SearchSettingsConfig(searxngMode = SearxngMode.DISABLED)
            } else {
                SearchSettingsConfig(
                    searxngMode = SearxngMode.EXTERNAL,
                    searxngBaseUrl = url,
                )
            }
        },
        forcePreferJsonFormat = preferJsonFormat,
        clockMillis = clockMillis,
    )

    override val id: String = ENGINE_ID
    override val displayName: String = "SearXNG"
    override val category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB
    override val priority: Int = 5

    override fun configurationUnavailableReason(): String? {
        val cfg = configProvider()
        return when (cfg.searxngMode) {
            SearxngMode.DISABLED ->
                "SearXNG 当前已关闭（可在设置中按需启用外部实例或本机 PRoot 托管）"
            SearxngMode.EXTERNAL ->
                if (cfg.normalizedExternalBaseUrl == null) {
                    "外部 SearXNG 实例地址未配置或无效"
                } else {
                    null
                }
            SearxngMode.PROOT_LOCAL ->
                if (!localInstalledCheck()) {
                    "本机 PRoot SearXNG 尚未安装（请在设置中安装或切换其他模式）"
                } else {
                    null
                }
        }
    }

    override suspend fun prepareBeforeRequest() {
        val cfg = configProvider()
        if (cfg.searxngMode == SearxngMode.PROOT_LOCAL && cfg.autoStartLocalProot) {
            runCatching {
                localAutoStartHook(cfg.safeLocalProotPort)
            }
        }
    }

    override fun buildProbeUrl(): String {
        val base = configProvider().effectiveSearxngBaseUrl.orEmpty()
        return "$base/"
    }

    override fun buildSearchUrl(query: SearchQuery): String {
        val cfg = configProvider()
        val base = cfg.effectiveSearxngBaseUrl.orEmpty()
        val encoded = urlEncode(query.normalizedQuery)
        val useJson = forcePreferJsonFormat ?: cfg.preferJsonFormat
        val formatParam = if (useJson) "&format=json" else ""
        val safeParam = if (query.safeSearch) "&safesearch=1" else "&safesearch=0"
        val langParam = query.language?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "&language=${urlEncode(it)}" }.orEmpty()
        return "$base/search?q=$encoded&categories=general$formatParam$safeParam$langParam"
    }

    override fun parseResponse(
        response: HttpFetchResponse,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val trimmed = response.body.trimStart()
        val isJson = response.contentType.contains("json", ignoreCase = true) ||
            trimmed.startsWith("{")
        if (isJson) {
            val jsonResults = parseJsonResults(response.body)
            if (jsonResults.isNotEmpty()) return jsonResults
        }
        val document = Ksoup.parse(html = response.body, baseUri = response.finalUrl)
        return parseHtmlResults(document, query)
    }

    override fun parseHtmlResults(
        document: Document,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val articles = document.select("article.result, div.result")
        val results = ArrayList<RawSearchResult>(articles.size)
        for (article in articles) {
            val link = article.selectFirst("h3 a[href]")
                ?: article.selectFirst("h4 a[href]")
                ?: article.selectFirst("a.url_header[href]")
                ?: article.selectFirst("a[href^=http]")
                ?: continue

            val url = link.absUrl("href").ifBlank { link.attr("href").trim() }
            val title = link.text().trim()
                .ifEmpty { article.selectFirst("h3, h4")?.text()?.trim().orEmpty() }
            if (title.isEmpty() || url.isEmpty()) continue

            val snippet = article.selectFirst("p.content, .content, .result-content")
                ?.text()
                ?.trim()
                .orEmpty()
            val displayUrl = article.selectFirst("a.url_wrapper, .url_wrapper, .url")
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

    private fun parseJsonResults(body: String): List<RawSearchResult> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val items = root["results"]?.jsonArray ?: return emptyList()
        val results = ArrayList<RawSearchResult>(items.size)
        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty() || url.isEmpty()) continue
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val subEngine = obj["engine"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val snippet = if (subEngine.isNotEmpty() && content.isNotEmpty()) {
                "[$subEngine] $content"
            } else {
                content
            }
            results += RawSearchResult(
                title = title,
                url = url,
                snippet = snippet,
                engineId = id,
                displayUrl = url,
            )
        }
        return results
    }

    companion object {
        const val ENGINE_ID = "searxng"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
