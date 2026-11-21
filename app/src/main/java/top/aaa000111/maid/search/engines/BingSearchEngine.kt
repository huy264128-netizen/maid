package top.aaa000111.maid.search.engines

import com.fleeksoft.ksoup.nodes.Document
import top.aaa000111.maid.search.RawSearchResult
import top.aaa000111.maid.search.SearchEngine
import top.aaa000111.maid.search.SearchEngineCategory
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.SearchQuery
import top.aaa000111.maid.search.SearchResultFilter
import top.aaa000111.maid.search.TinyModelSearchResultFilter
import java.net.URI
import java.util.Base64

/**
 * Bing 搜索引擎基类封装（支持国际版 `www.bing.com` 与国内直连版 `cn.bing.com`）：
 * - 自动过滤 `.b_ad` 等广告位；
 * - 自动解码 Bing `bing.com/ck/a?...&u=a1<base64url>` 跳转链接，还原真实目标 URL。
 */
open class BingSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    private val host: String = GLOBAL_HOST,
    override val id: String = GLOBAL_ENGINE_ID,
    override val displayName: String = "Bing",
    override val priority: Int = 30,
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : SearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    clockMillis = clockMillis,
) {
    override val category: SearchEngineCategory = SearchEngineCategory.GENERAL_WEB
    override fun buildProbeUrl(): String = "https://$host/"

    override fun buildSearchUrl(query: SearchQuery): String {
        val encoded = urlEncode(query.normalizedQuery)
        val count = (query.maxResults * 2).coerceIn(10, 20)
        val safeParam = if (query.safeSearch) "&adlt=strict" else ""
        return "https://$host/search?q=$encoded&count=$count$safeParam"
    }

    override fun buildHeaders(query: SearchQuery?): Map<String, String> = mapOf(
        "Referer" to "https://$host/",
    )

    override fun parseHtmlResults(
        document: Document,
        query: SearchQuery,
    ): List<RawSearchResult> {
        val blocks = document.select("li.b_algo")
        val results = ArrayList<RawSearchResult>(blocks.size)
        for (block in blocks) {
            if (block.hasClass("b_ad") || block.selectFirst(".b_adSlug") != null) {
                continue
            }
            val link = block.selectFirst("h2 a[href]")
                ?: block.selectFirst("a.tilk[href]")
                ?: continue

            val title = link.text().trim()
            val rawHref = link.absUrl("href").ifBlank { link.attr("href").trim() }
            val resolvedUrl = resolveBingUrl(rawHref)
            if (title.isEmpty() || resolvedUrl.isEmpty()) continue

            val snippet = block.selectFirst(".b_caption p, p.b_lineclamp2, p.b_lineclamp3, p.b_lineclamp4, .b_algoSlug")
                ?.text()
                ?.trim()
                .orEmpty()
            val displayUrl = block.selectFirst("cite")?.text()?.trim().orEmpty()

            results += RawSearchResult(
                title = title,
                url = resolvedUrl,
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
        return lower.contains("bing.com/aclick") || lower.contains("bing.com/ck/a")
    }

    companion object {
        const val GLOBAL_ENGINE_ID = "bing"
        const val CN_ENGINE_ID = "cn_bing"
        const val GLOBAL_HOST = "www.bing.com"
        const val CN_HOST = "cn.bing.com"

        /**
         * 解码 Bing 点击追踪链接（如 `https://www.bing.com/ck/a?...&u=a1aHR0cHM6Ly9leGFtcGxlLmNvbQ`）。
         */
        internal fun resolveBingUrl(rawHref: String): String {
            val trimmed = rawHref.trim()
            if (trimmed.isEmpty()) return ""
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
            val hostLower = uri.host.orEmpty().lowercase()
            if (hostLower.endsWith("bing.com") && uri.path.orEmpty().startsWith("/ck/a")) {
                val uParam = uri.rawQuery.orEmpty()
                    .split('&')
                    .firstOrNull { it.startsWith("u=") }
                    ?.substringAfter("u=")
                if (!uParam.isNullOrBlank() && uParam.startsWith("a1") && uParam.length > 2) {
                    val b64 = uParam.substring(2)
                    val decoded = runCatching {
                        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                        String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
                    }.getOrNull()
                    if (!decoded.isNullOrBlank() && isValidHttpUrl(decoded)) {
                        return decoded
                    }
                }
            }
            return trimmed
        }
    }
}

/**
 * Bing 中国直连版（`cn.bing.com`）：
 * 在国内无代理环境下可稳定访问，且结果质量明显优于国内内容农场生态，作为国内直连优先回退引擎。
 */
class CnBingSearchEngine(
    httpClient: SearchHttpClient,
    defaultFilter: SearchResultFilter = TinyModelSearchResultFilter(),
    clockMillis: () -> Long = { System.currentTimeMillis() },
) : BingSearchEngine(
    httpClient = httpClient,
    defaultFilter = defaultFilter,
    host = CN_HOST,
    id = CN_ENGINE_ID,
    displayName = "Bing (中国版)",
    priority = 40,
    clockMillis = clockMillis,
)
