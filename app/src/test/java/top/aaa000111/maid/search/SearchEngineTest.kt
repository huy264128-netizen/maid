package top.aaa000111.maid.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
import top.aaa000111.maid.tinymodel.TinyModel
import top.aaa000111.maid.tinymodel.TinyModelActiveBackend
import top.aaa000111.maid.tinymodel.TinyModelAssetSource
import top.aaa000111.maid.tinymodel.TinyModelConfig
import top.aaa000111.maid.tinymodel.TinyModelConversationHandle
import top.aaa000111.maid.tinymodel.TinyModelConversationParams
import top.aaa000111.maid.tinymodel.TinyModelEngineHandle
import top.aaa000111.maid.tinymodel.TinyModelEngineInitParams
import top.aaa000111.maid.tinymodel.TinyModelFileStore
import top.aaa000111.maid.tinymodel.TinyModelRuntimeBackend
import top.aaa000111.maid.tinymodel.TinyModelSpec
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SearchEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeHttpClient(
        private val handler: suspend (url: String, headers: Map<String, String>) -> HttpFetchResponse,
    ) : SearchHttpClient {
        val requestedUrls = mutableListOf<String>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMillis: Long,
            maxBodyBytes: Long,
        ): HttpFetchResponse {
            requestedUrls += url
            return handler(url, headers)
        }

        override fun close() = Unit
    }

    private fun createFakeTinyModel(
        replyProvider: (String) -> String,
        generateCalls: AtomicInteger = AtomicInteger(0),
    ): TinyModel {
        val spec = TinyModelSpec(
            id = "test-tiny-filter",
            displayName = "Test Tiny Filter",
            assetPath = "models/test.litertlm",
            fileName = "test.litertlm",
            supportsGpu = false,
            maxContextTokens = 2048,
            defaultMaxNumTokens = 2048,
            isolateEnginePerConversation = true,
        )
        val assetSource = object : TinyModelAssetSource {
            override fun list(dirPath: String): List<String> =
                if (dirPath == "models") listOf("test.litertlm") else emptyList()

            override fun exists(assetPath: String): Boolean = assetPath == "models/test.litertlm"

            override fun open(assetPath: String): InputStream {
                if (!exists(assetPath)) throw FileNotFoundException(assetPath)
                return ByteArrayInputStream("fake-model-bytes".toByteArray())
            }

            override fun lengthOrNull(assetPath: String): Long? = if (exists(assetPath)) 16L else null
        }
        val fileStore = TinyModelFileStore(
            filesDir = tempFolder.newFolder("tiny-files-${generateCalls.hashCode()}"),
            cacheRootDir = tempFolder.newFolder("tiny-cache-${generateCalls.hashCode()}"),
            assetSource = assetSource,
        )
        val backend = object : TinyModelRuntimeBackend {
            override fun createAndInitializeEngine(params: TinyModelEngineInitParams): TinyModelEngineHandle {
                return object : TinyModelEngineHandle {
                    override val activeBackend: TinyModelActiveBackend = TinyModelActiveBackend.CPU
                    override val modelPath: String = params.modelPath
                    override fun createConversation(params: TinyModelConversationParams): TinyModelConversationHandle {
                        return object : TinyModelConversationHandle {
                            override fun sendMessageAsync(
                                text: String,
                                maxOutputTokens: Int?,
                            ): Flow<String> = flow {
                                generateCalls.incrementAndGet()
                                emit(replyProvider(text))
                            }

                            override fun cancelProcess() = Unit
                            override fun close() = Unit
                        }
                    }

                    override fun close() = Unit
                }
            }
        }
        return TinyModel(
            config = TinyModelConfig(spec = spec),
            fileStore = fileStore,
            runtimeBackend = backend,
        )
    }

    @Test
    fun ktorSearchHttpClient_fetchesViaKtorMockEngine_andRespectsBodyLimit() = runBlocking {
        var capturedUserAgent: String? = null
        val mockEngine = MockEngine { request ->
            capturedUserAgent = request.headers[HttpHeaders.UserAgent]
            respond(
                content = "<html><title>Ktor OK</title><body>${"A".repeat(4096)}</body></html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
            )
        }
        val ktorClient = HttpClient(mockEngine)
        val client = KtorSearchHttpClient(client = ktorClient, ownsClient = true)
        try {
            val response = client.get(
                url = "https://example.org/test",
                maxBodyBytes = 1024L,
            )
            assertEquals(200, response.statusCode)
            assertEquals("https://example.org/test", response.finalUrl)
            assertTrue(response.body.startsWith("<html><title>Ktor OK</title>"))
            assertTrue(response.body.length <= 1024)
            assertTrue(!capturedUserAgent.isNullOrBlank())
        } finally {
            client.close()
        }
    }

    @Test
    fun availabilityProbe_detectsWalledAndCaptchaEngines_andRespectsTtlCache() = runBlocking {
        val now = AtomicLong(1_000L)
        var shouldFail = true
        val http = FakeHttpClient { url, _ ->
            if (shouldFail) {
                throw IOException("Connection reset by peer (GFW)")
            }
            HttpFetchResponse(
                statusCode = 200,
                finalUrl = url,
                body = "<html><body>DuckDuckGo HTML</body></html>",
            )
        }
        val engine = DuckDuckGoSearchEngine(
            httpClient = http,
            defaultFilter = RuleOnlySearchResultFilter,
            clockMillis = { now.get() },
        )

        val first = engine.checkAvailability()
        assertFalse(first.available)
        assertTrue(first.reason.contains("Connection reset"))
        assertEquals(1, http.requestedUrls.size)

        shouldFail = false
        now.addAndGet(10_000L)
        val cached = engine.checkAvailability()
        assertFalse(cached.available)
        assertEquals(1, http.requestedUrls.size)

        val refreshed = engine.checkAvailability(forceRefresh = true)
        assertTrue(refreshed.available)
        assertEquals(2, http.requestedUrls.size)

        val captchaEngine = BraveSearchEngine(
            httpClient = FakeHttpClient { url, _ ->
                HttpFetchResponse(
                    statusCode = 200,
                    finalUrl = url,
                    body = "<html><body>Just a moment... cf-browser-verification</body></html>",
                )
            },
            defaultFilter = RuleOnlySearchResultFilter,
        )
        val captchaStatus = captchaEngine.checkAvailability()
        assertFalse(captchaStatus.available)
        assertTrue(captchaStatus.reason.contains("Cloudflare"))
    }

    @Test
    fun defaultRouter_excludesBaidu_andCoversAllFiveCategories() {
        val router = SearchEngineRouter.createDefault(
            httpClient = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, "") },
            ownsHttpClient = false,
            filter = RuleOnlySearchResultFilter,
        )
        val ids = router.engines.map { it.id }
        assertFalse("Default engines must not include Baidu", ids.any { it.contains("baidu", ignoreCase = true) })
        assertEquals(11, router.engines.size)

        assertEquals(
            listOf(
                SearXngSearchEngine.ENGINE_ID,
                DuckDuckGoSearchEngine.ENGINE_ID,
                BraveSearchEngine.ENGINE_ID,
                BingSearchEngine.GLOBAL_ENGINE_ID,
                BingSearchEngine.CN_ENGINE_ID,
                QwantLiteSearchEngine.ENGINE_ID,
                MojeekSearchEngine.ENGINE_ID,
            ),
            router.enginesIn(SearchEngineCategory.GENERAL_WEB).map { it.id },
        )
        assertEquals(listOf(ArxivSearchEngine.ENGINE_ID), router.enginesIn(SearchEngineCategory.ACADEMIC).map { it.id })
        assertEquals(listOf(GitHubSearchEngine.ENGINE_ID), router.enginesIn(SearchEngineCategory.DEVELOPER).map { it.id })
        assertEquals(listOf(HackerNewsSearchEngine.ENGINE_ID), router.enginesIn(SearchEngineCategory.TECH_COMMUNITY).map { it.id })
        assertEquals(listOf(WikipediaSearchEngine.ENGINE_ID), router.enginesIn(SearchEngineCategory.ENCYCLOPEDIA).map { it.id })
    }

    @Test
    fun searxngEngine_skipsNetworkWhenUnconfigured_andParsesHtmlAndJsonWhenConfigured() = runBlocking {
        val unconfiguredHttp = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, "") }
        val unconfigured = SearXngSearchEngine(
            httpClient = unconfiguredHttp,
            defaultFilter = RuleOnlySearchResultFilter,
            baseUrlProvider = { null },
        )
        val unconfiguredStatus = unconfigured.checkAvailability()
        assertFalse(unconfiguredStatus.available)
        assertEquals(0, unconfiguredHttp.requestedUrls.size)

        val searxHtml = """
            <html><body>
              <article class="result">
                <h3><a href="https://searxng.org/docs">SearXNG Documentation</a></h3>
                <p class="content">Privacy-respecting metasearch engine.</p>
              </article>
            </body></html>
        """.trimIndent()
        val htmlEngine = SearXngSearchEngine(
            httpClient = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, searxHtml, contentType = "text/html") },
            defaultFilter = RuleOnlySearchResultFilter,
            baseUrlProvider = { "https://search.myhost.example/" },
        )
        val htmlResp = htmlEngine.search(SearchQuery("searxng"))
        assertEquals(1, htmlResp.results.size)
        assertEquals("https://searxng.org/docs", htmlResp.results.single().url)

        val searxJson = """
            {
              "results": [
                {
                  "title": "Kotlin Multiplatform",
                  "url": "https://kotlinlang.org/multiplatform/",
                  "content": "Share code across platforms.",
                  "engine": "google"
                }
              ]
            }
        """.trimIndent()
        val jsonEngine = SearXngSearchEngine(
            httpClient = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, searxJson, contentType = "application/json") },
            defaultFilter = RuleOnlySearchResultFilter,
            baseUrlProvider = { "https://search.myhost.example" },
            preferJsonFormat = true,
        )
        val jsonResp = jsonEngine.search(SearchQuery("kotlin"))
        assertEquals(1, jsonResp.results.size)
        assertEquals("https://kotlinlang.org/multiplatform/", jsonResp.results.single().url)
        assertTrue(jsonResp.results.single().snippet.contains("[google]"))
    }

    @Test
    fun qwantLiteAndMojeekEngines_parseCleanStaticHtmlWithKsoup() = runBlocking {
        val qwantHtml = """
            <html><body>
              <section class="results">
                <article class="result">
                  <h2><a href="https://developer.android.com/kotlin">Android Kotlin</a></h2>
                  <p class="desc">Build better Android apps faster with Kotlin.</p>
                  <span class="url">developer.android.com/kotlin</span>
                </article>
                <article class="result ad">
                  <h2><a href="https://ads.example/promo">Sponsored</a></h2>
                </article>
              </section>
            </body></html>
        """.trimIndent()
        val qwant = QwantLiteSearchEngine(
            httpClient = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, qwantHtml) },
            defaultFilter = RuleOnlySearchResultFilter,
        )
        val qwantResp = qwant.search(SearchQuery("Android Kotlin"))
        assertEquals(1, qwantResp.results.size)
        assertEquals("https://developer.android.com/kotlin", qwantResp.results.single().url)

        val mojeekHtml = """
            <html><body>
              <ul class="results-standard">
                <li>
                  <h2><a class="title" href="https://ktor.io/">Ktor Framework</a></h2>
                  <p class="s">Asynchronous web framework for Kotlin.</p>
                  <p class="i"><a class="ob" href="https://ktor.io/">ktor.io</a></p>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        val mojeek = MojeekSearchEngine(
            httpClient = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, mojeekHtml) },
            defaultFilter = RuleOnlySearchResultFilter,
        )
        val mojeekResp = mojeek.search(SearchQuery("Ktor"))
        assertEquals(1, mojeekResp.results.size)
        assertEquals("https://ktor.io/", mojeekResp.results.single().url)
    }

    @Test
    fun duckDuckGoAndBingEngines_decodeRedirectUrls_andSkipAds() = runBlocking {
        val ddgHtml = """
            <html><body>
              <div class="result result--ad">
                <a class="result__a" href="https://duckduckgo.com/y.js?ad_domain=spam.com">Sponsored Ad</a>
              </div>
              <div class="result web-result">
                <h2 class="result__title">
                  <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fhome.html&rut=abc">Kotlin Docs</a>
                </h2>
                <a class="result__snippet">Official Kotlin documentation.</a>
              </div>
            </body></html>
        """.trimIndent()
        val ddg = DuckDuckGoSearchEngine(
            httpClient = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, ddgHtml) },
            defaultFilter = RuleOnlySearchResultFilter,
        )
        assertEquals("https://kotlinlang.org/docs/home.html", ddg.search(SearchQuery("kotlin")).results.single().url)

        val targetUrl = "https://developer.android.com/develop/ui/compose"
        val encodedTarget = "a1" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(targetUrl.toByteArray(Charsets.UTF_8))
        val bingHtml = """
            <html><body>
              <ol id="b_results">
                <li class="b_ad"><h2><a href="https://www.bing.com/aclick?ld=123">Fake Ad</a></h2></li>
                <li class="b_algo">
                  <h2><a href="https://cn.bing.com/ck/a?p=1&u=$encodedTarget&ntb=1">Jetpack Compose Guide</a></h2>
                  <div class="b_caption"><p>Modern declarative UI toolkit for Android.</p></div>
                </li>
              </ol>
            </body></html>
        """.trimIndent()
        val cnBing = CnBingSearchEngine(
            httpClient = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, bingHtml) },
            defaultFilter = RuleOnlySearchResultFilter,
        )
        assertEquals(targetUrl, cnBing.search(SearchQuery("Compose")).results.single().url)
    }

    @Test
    fun verticalEngines_arxivGithubHackerNewsWikipedia_parseStructuredResults() = runBlocking {
        // 1. arXiv XML
        val arxivXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:arxiv="http://arxiv.org/schemas/atom">
              <entry>
                <id>http://arxiv.org/abs/2501.00001v1</id>
                <published>2025-01-10T12:00:00Z</published>
                <title>Liquid Foundation Models on Edge Devices</title>
                <summary>We present compact hybrid models suitable for mobile inference.</summary>
                <author><name>Alice Smith</name></author>
                <author><name>Bob Chen</name></author>
                <arxiv:primary_category term="cs.CL"/>
                <link rel="alternate" href="https://arxiv.org/abs/2501.00001" type="text/html"/>
                <link title="pdf" href="https://arxiv.org/pdf/2501.00001" type="application/pdf"/>
              </entry>
            </feed>
        """.trimIndent()
        val arxivHttp = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, arxivXml, "application/atom+xml") }
        val arxiv = ArxivSearchEngine(arxivHttp, RuleOnlySearchResultFilter)
        val arxivResp = arxiv.search(
            SearchQuery("Liquid Foundation Models", extraParams = mapOf(ArxivSearchEngine.PARAM_SORT_BY to "submittedDate")),
        )
        assertTrue(arxivHttp.requestedUrls.last().contains("sortBy=submittedDate"))
        assertEquals("https://arxiv.org/abs/2501.00001", arxivResp.results.single().url)
        assertTrue(arxivResp.results.single().snippet.contains("Alice Smith, Bob Chen"))
        assertTrue(arxivResp.results.single().snippet.contains("https://arxiv.org/pdf/2501.00001"))

        // 2. GitHub repos & issues JSON
        val ghRepoJson = """
            {
              "items": [
                {
                  "full_name": "google-ai-edge/LiteRT-LM",
                  "html_url": "https://github.com/google-ai-edge/LiteRT-LM",
                  "stargazers_count": 4200,
                  "language": "C++",
                  "updated_at": "2026-10-01T00:00:00Z",
                  "description": "On-device LLM runtime for mobile."
                }
              ]
            }
        """.trimIndent()
        val ghIssueJson = """
            {
              "items": [
                {
                  "title": "OpenCL backend fallback on Mali GPU",
                  "html_url": "https://github.com/google-ai-edge/LiteRT-LM/issues/99",
                  "repository_url": "https://api.github.com/repos/google-ai-edge/LiteRT-LM",
                  "state": "open",
                  "comments": 7,
                  "updated_at": "2026-10-15T00:00:00Z",
                  "body": "Steps to reproduce GPU fallback."
                }
              ]
            }
        """.trimIndent()
        val ghHttp = FakeHttpClient { url, _ ->
            if (url.contains("/search/issues")) {
                HttpFetchResponse(200, url, ghIssueJson, "application/json")
            } else {
                HttpFetchResponse(200, url, ghRepoJson, "application/json")
            }
        }
        val github = GitHubSearchEngine(ghHttp, RuleOnlySearchResultFilter)
        val repoResp = github.search(SearchQuery("LiteRT-LM"))
        assertEquals("google-ai-edge/LiteRT-LM", repoResp.results.single().title)
        assertTrue(repoResp.results.single().snippet.contains("★4200"))

        val issueResp = github.search(
            SearchQuery("OpenCL Mali", extraParams = mapOf(GitHubSearchEngine.PARAM_SCOPE to "issues")),
        )
        assertEquals("https://github.com/google-ai-edge/LiteRT-LM/issues/99", issueResp.results.single().url)
        assertTrue(issueResp.results.single().snippet.contains("7 comments"))

        // 3. Hacker News Algolia JSON
        val hnJson = """
            {
              "hits": [
                {
                  "objectID": "41234567",
                  "title": "Running 350M Models Locally on Android",
                  "url": "https://blog.example.org/edge-llm",
                  "points": 312,
                  "num_comments": 88,
                  "author": "devuser",
                  "created_at": "2026-09-20T08:00:00Z"
                }
              ]
            }
        """.trimIndent()
        val hnHttp = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, hnJson, "application/json") }
        val hn = HackerNewsSearchEngine(hnHttp, RuleOnlySearchResultFilter)
        val hnResp = hn.search(
            SearchQuery("edge llm", extraParams = mapOf(HackerNewsSearchEngine.PARAM_SORT_BY to "date")),
        )
        assertTrue(hnHttp.requestedUrls.last().contains("search_by_date"))
        assertEquals("https://blog.example.org/edge-llm", hnResp.results.single().url)
        assertTrue(hnResp.results.single().snippet.contains("312 pts"))
        assertTrue(hnResp.results.single().snippet.contains("https://news.ycombinator.com/item?id=41234567"))

        // 4. Wikipedia CJK / EN routing
        assertEquals("zh.wikipedia.org", WikipediaSearchEngine.resolveWikipediaHost(SearchQuery("量子计算")))
        assertEquals("en.wikipedia.org", WikipediaSearchEngine.resolveWikipediaHost(SearchQuery("Kotlin coroutines")))
    }

    @Test
    fun tinyModelFilter_executesSingleBatchCall_andFallsBackSafelyWhenMalformed() = runBlocking {
        val callCounter = AtomicInteger(0)
        val fakeTinyModel = createFakeTinyModel(
            replyProvider = { "2, 4" },
            generateCalls = callCounter,
        )
        try {
            val candidates = listOf(
                RawSearchResult("SEO Farm Junk", "https://junk.example/1", "Clickbait", "bing"),
                RawSearchResult("Official LiteRT-LM Docs", "https://ai.google.dev/litert-lm", "Official guide", "bing"),
                RawSearchResult("Unrelated Forum", "https://forum.example/3", "Random chat", "bing"),
                RawSearchResult("LiteRT-LM GitHub Releases", "https://github.com/google-ai-edge/LiteRT-LM", "Releases", "bing"),
            )
            val filter = TinyModelSearchResultFilter(tinyModelProvider = { fakeTinyModel })
            val filtered = filter.filter(SearchQuery("LiteRT-LM Android"), candidates)

            assertEquals(1, callCounter.get())
            assertEquals(SearchFilterStage.TINY_MODEL_ACCEPTED, filtered.stage)
            assertEquals(
                listOf("https://ai.google.dev/litert-lm", "https://github.com/google-ai-edge/LiteRT-LM"),
                filtered.results.map { it.url },
            )

            val brokenModel = createFakeTinyModel(replyProvider = { "Sorry, I cannot comply." })
            try {
                val fallbackFilter = TinyModelSearchResultFilter(tinyModelProvider = { brokenModel })
                val fallback = fallbackFilter.filter(SearchQuery("LiteRT-LM Android", maxResults = 2), candidates)
                assertEquals(SearchFilterStage.TINY_MODEL_FALLBACK, fallback.stage)
                assertEquals(2, fallback.results.size)
            } finally {
                brokenModel.close()
            }
        } finally {
            fakeTinyModel.close()
        }
    }

    @Test
    fun router_isolatesCategories_andFallsBackWithinSameCategory() = runBlocking {
        val http = FakeHttpClient { url, _ ->
            when {
                url.contains("duckduckgo.com") -> throw IOException("Blocked")
                url.contains("cn.bing.com") -> HttpFetchResponse(
                    200,
                    url,
                    """<ol id="b_results"><li class="b_algo"><h2><a href="https://example.org/cn">CN Result</a></h2></li></ol>""",
                )
                url.contains("export.arxiv.org") -> HttpFetchResponse(
                    200,
                    url,
                    """<feed><entry><title>Paper A</title><link rel="alternate" href="https://arxiv.org/abs/1"/></entry></feed>""",
                )
                else -> HttpFetchResponse(404, url, "Not Found")
            }
        }
        val router = SearchEngineRouter(
            engines = listOf(
                DuckDuckGoSearchEngine(http, RuleOnlySearchResultFilter),
                CnBingSearchEngine(http, RuleOnlySearchResultFilter),
                ArxivSearchEngine(http, RuleOnlySearchResultFilter),
            ),
            defaultTinyModelFilter = RuleOnlySearchResultFilter,
        )

        val webResp = router.search(
            query = SearchQuery("Kotlin"),
            category = SearchEngineCategory.GENERAL_WEB,
            useTinyModelFilter = false,
        )
        assertEquals(BingSearchEngine.CN_ENGINE_ID, webResp.engineId)
        assertEquals(SearchEngineCategory.GENERAL_WEB, webResp.category)

        val academicResp = router.search(
            query = SearchQuery("Transformer"),
            category = SearchEngineCategory.ACADEMIC,
            useTinyModelFilter = false,
        )
        assertEquals(ArxivSearchEngine.ENGINE_ID, academicResp.engineId)
        assertEquals(SearchEngineCategory.ACADEMIC, academicResp.category)
    }

    @Test
    fun webPageFetcher_readsCleanTextAndRawPaginatedSource_andBlocksLocalTargets() = runBlocking {
        val rawBody = """
            <html>
              <head><title>Ktor Android Guide</title><script>var secret_meta = {"ver":"3.2.2"};</script></head>
              <body>
                <nav>Home | Login</nav>
                <article><h1>Using Ktor on Android</h1><p>Transparent gzip enabled.</p></article>
              </body>
            </html>
        """.trimIndent()

        val fetcher = WebPageFetcher(
            httpClient = FakeHttpClient { url, _ ->
                HttpFetchResponse(200, url, rawBody, contentType = "text/html; charset=utf-8")
            },
        )

        // 1. 清洗模式去掉 script 和 nav
        val cleaned = fetcher.fetch("https://ktor.io/docs/android")
        assertTrue(cleaned.content.contains("Using Ktor on Android"))
        assertFalse(cleaned.content.contains("secret_meta"))
        assertFalse(cleaned.content.contains("Home | Login"))

        // 2. 原始模式保留 script/HTML 标签并支持 offsetChars 分页
        val rawPage1 = fetcher.fetchRaw("https://ktor.io/docs/android", maxChars = 500, offsetChars = 0)
        assertTrue(rawPage1.content.contains("<script>var secret_meta"))
        assertEquals("text/html; charset=utf-8", rawPage1.contentType)
        assertEquals(rawBody.length, rawPage1.totalCharLength)
        assertFalse(rawPage1.truncated)

        val rawLong = "0123456789".repeat(120) // 1200 chars
        val rawLongFetcher = WebPageFetcher(
            httpClient = FakeHttpClient { url, _ ->
                HttpFetchResponse(200, url, rawLong, contentType = "application/json")
            },
        )
        val firstSlice = rawLongFetcher.fetchRaw("https://api.example.org/data.json", maxChars = 500, offsetChars = 0)
        assertTrue(firstSlice.truncated)
        assertEquals(500, firstSlice.content.length)
        val secondSlice = rawLongFetcher.fetchRaw("https://api.example.org/data.json", maxChars = 500, offsetChars = 500)
        assertEquals(500, secondSlice.offsetChars)
        assertTrue(secondSlice.truncated)
        val thirdSlice = rawLongFetcher.fetchRaw("https://api.example.org/data.json", maxChars = 500, offsetChars = 1000)
        assertEquals(200, thirdSlice.content.length)
        assertFalse(thirdSlice.truncated)

        // 3. fetchRaw 同样拦截本机与内网地址
        var blockedLocal = false
        try {
            fetcher.fetchRaw("http://127.0.0.1:8080/internal")
        } catch (_: IllegalArgumentException) {
            blockedLocal = true
        }
        assertTrue(blockedLocal)
    }

    @Test
    fun searxngConfigModes_andProotLocalLazyAutoStart_workAsExpected() = runBlocking {
        // 1. 默认 DISABLED 零开销
        val disabledCfg = SearchSettingsConfig()
        assertEquals(SearxngMode.DISABLED, disabledCfg.searxngMode)
        assertEquals(null, disabledCfg.effectiveSearxngBaseUrl)
        assertFalse(disabledCfg.preferJsonFormat)

        // 2. EXTERNAL 模式规范化地址
        val extCfg = SearchSettingsConfig(
            searxngMode = SearxngMode.EXTERNAL,
            searxngBaseUrl = "searx.example.org/",
        )
        assertEquals("https://searx.example.org", extCfg.effectiveSearxngBaseUrl)
        assertFalse(extCfg.preferJsonFormat)

        // 3. PROOT_LOCAL 模式走 127.0.0.1:<port> 并优先 JSON，端口越界自动钳位
        val prootCfg = SearchSettingsConfig(
            searxngMode = SearxngMode.PROOT_LOCAL,
            localProotPort = 9091,
            autoStartLocalProot = true,
        )
        assertEquals("http://127.0.0.1:9091", prootCfg.effectiveSearxngBaseUrl)
        assertTrue(prootCfg.preferJsonFormat)
        assertEquals(SearchSettingsConfig.MIN_PORT, SearchSettingsConfig(localProotPort = 80).safeLocalProotPort)

        // 4. PROOT_LOCAL 未安装时跳过网络且不触发自启
        val autoStartCalls = AtomicInteger(0)
        val notInstalledHttp = FakeHttpClient { url, _ -> HttpFetchResponse(200, url, "") }
        val notInstalledEngine = SearXngSearchEngine(
            httpClient = notInstalledHttp,
            defaultFilter = RuleOnlySearchResultFilter,
            configProvider = { prootCfg },
            localInstalledCheck = { false },
            localAutoStartHook = {
                autoStartCalls.incrementAndGet()
                true
            },
        )
        val notInstalledStatus = notInstalledEngine.checkAvailability()
        assertFalse(notInstalledStatus.available)
        assertEquals(0, notInstalledHttp.requestedUrls.size)
        assertEquals(0, autoStartCalls.get())

        // 5. PROOT_LOCAL 已安装 + autoStartLocalProot=true 时，search() 先按需拉起再走 format=json
        val searxJson = """
            {"results":[{"title":"PRoot Linux","url":"https://proot-me.github.io/","content":"Chroot in user space.","engine":"duckduckgo"}]}
        """.trimIndent()
        var startedPort = -1
        val prootHttp = FakeHttpClient { url, _ ->
            HttpFetchResponse(200, url, searxJson, contentType = "application/json")
        }
        val prootEngine = SearXngSearchEngine(
            httpClient = prootHttp,
            defaultFilter = RuleOnlySearchResultFilter,
            configProvider = { prootCfg },
            localInstalledCheck = { true },
            localAutoStartHook = { port ->
                startedPort = port
                autoStartCalls.incrementAndGet()
                true
            },
        )
        val resp = prootEngine.search(SearchQuery("proot"))
        assertEquals(1, autoStartCalls.get())
        assertEquals(9091, startedPort)
        assertEquals(1, resp.results.size)
        assertTrue(prootHttp.requestedUrls.single().startsWith("http://127.0.0.1:9091/search?"))
        assertTrue(prootHttp.requestedUrls.single().contains("format=json"))

        // 6. PROOT_LOCAL 关闭按需自启时，不调用 localAutoStartHook
        val manualStartCalls = AtomicInteger(0)
        val manualCfg = prootCfg.copy(autoStartLocalProot = false)
        val manualEngine = SearXngSearchEngine(
            httpClient = FakeHttpClient { url, _ ->
                HttpFetchResponse(200, url, searxJson, contentType = "application/json")
            },
            defaultFilter = RuleOnlySearchResultFilter,
            configProvider = { manualCfg },
            localInstalledCheck = { true },
            localAutoStartHook = {
                manualStartCalls.incrementAndGet()
                true
            },
        )
        manualEngine.search(SearchQuery("proot"))
        assertEquals(0, manualStartCalls.get())

        // 7. 验证 ProotSearXngManager 生成的单机精简配置与 waitress 启动命令
        val yaml = ProotSearXngManager.buildSettingsYaml(port = 9091, secretKey = "deadbeef")
        assertTrue(yaml.contains("port: 9091"))
        assertTrue(yaml.contains("bind_address: \"127.0.0.1\""))
        assertTrue(yaml.contains("limiter: false"))
        assertTrue(yaml.contains("- json"))

        val launchCmd = ProotSearXngManager.buildLaunchCommand(port = 9091)
        assertTrue(launchCmd.contains("waitress-serve"))
        assertTrue(launchCmd.contains("--host=127.0.0.1"))
        assertTrue(launchCmd.contains("--port=9091"))
    }
}
