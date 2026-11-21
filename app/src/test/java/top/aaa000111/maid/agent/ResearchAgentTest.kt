package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aaa000111.maid.search.HttpFetchResponse
import top.aaa000111.maid.search.RuleOnlySearchResultFilter
import top.aaa000111.maid.search.SearchEngineRouter
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.WebPageFetcher

class ResearchAgentTest {

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gpt-5",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 128_000L,
    )

    private class ScriptedPromptExecutor(
        private val onExecute: suspend (turn: Int, prompt: Prompt, tools: List<ToolDescriptor>) -> List<Message.Response>,
    ) : PromptExecutor {
        var turns = 0
        val capturedToolNames = mutableListOf<List<String>>()
        val capturedPrompts = mutableListOf<Prompt>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> {
            turns += 1
            capturedPrompts += prompt
            capturedToolNames += tools.map { it.name }
            return onExecute(turns, prompt, tools)
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = emptyFlow()

        override suspend fun moderate(
            prompt: Prompt,
            model: LLModel,
        ): ModerationResult = throw UnsupportedOperationException()
    }

    private fun createStubSearchEnv(): Pair<SearchEngineRouter, WebPageFetcher> {
        val fakeHttp = object : SearchHttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMillis: Long,
                maxBodyBytes: Long,
            ): HttpFetchResponse {
                return when {
                    url.contains("html.duckduckgo.com") -> HttpFetchResponse(
                        statusCode = 200,
                        finalUrl = url,
                        body = """
                            <html><body>
                              <div class="result web-result">
                                <a class="result__a" href="https://example.org/litert-lm-release">LiteRT-LM 0.19 Release Notes</a>
                                <a class="result__snippet">LiteRT-LM adds on-device Gemma and LFM2.5 support.</a>
                              </div>
                            </body></html>
                        """.trimIndent(),
                    )
                    url.contains("export.arxiv.org") -> HttpFetchResponse(
                        statusCode = 200,
                        finalUrl = url,
                        body = """
                            <feed>
                              <entry>
                                <title>Hybrid Short-Conv Edge LLMs</title>
                                <published>2026-02-01T00:00:00Z</published>
                                <summary>Efficient 350M models for mobile CPUs.</summary>
                                <link rel="alternate" href="https://arxiv.org/abs/2602.00001"/>
                              </entry>
                            </feed>
                        """.trimIndent(),
                        contentType = "application/atom+xml",
                    )
                    url == "https://example.org/litert-lm-release" -> HttpFetchResponse(
                        statusCode = 200,
                        finalUrl = url,
                        body = """
                            <html>
                              <head><title>LiteRT-LM 0.19 Release Notes</title></head>
                              <body>
                                <article>
                                  <h1>LiteRT-LM 0.19</h1>
                                  <p>Supports Kotlin Coroutines streaming inference on Android CPU and OpenCL GPU.</p>
                                </article>
                              </body>
                            </html>
                        """.trimIndent(),
                        contentType = "text/html; charset=utf-8",
                    )
                    url == "https://raw.githubusercontent.com/example/repo/main/version.json" -> HttpFetchResponse(
                        statusCode = 200,
                        finalUrl = url,
                        body = """{"latest":"0.19.1","minSdk":31}""",
                        contentType = "application/json",
                    )
                    else -> HttpFetchResponse(404, url, "Not Found")
                }
            }

            override fun close() = Unit
        }

        val router = SearchEngineRouter.createDefault(
            httpClient = fakeHttp,
            ownsHttpClient = false,
            filter = RuleOnlySearchResultFilter,
            searxngBaseUrlProvider = { null },
        )
        val fetcher = WebPageFetcher(httpClient = fakeHttp)
        return router to fetcher
    }

    @Test
    fun researchAgent_exposesAllEightCategorizedTools_andExecutesSearchReadAndReadRaw() = runBlocking {
        val (router, fetcher) = createStubSearchEnv()
        val executor = ScriptedPromptExecutor { turn, prompt, _ ->
            when (turn) {
                2, 4, 6 -> waitForAgentTask(prompt, "waitSearchTask")
                1 -> listOf(
                    Message.Tool.Call(
                        id = "call_web_1",
                        tool = "searchWeb",
                        content = """{"query":"LiteRT-LM Android","maxResults":5,"useTinyModelFilter":false}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
                3 -> {
                    val toolMsg = prompt.messages.last { it is Message.Tool.Result }
                    assertTrue(toolMsg.content.contains("https://example.org/litert-lm-release"))
                    listOf(
                        Message.Tool.Call(
                            id = "call_academic_1",
                            tool = "searchAcademic",
                            content = """{"query":"Hybrid Short-Conv Edge LLMs","maxResults":3,"useTinyModelFilter":false}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                }
                5 -> {
                    val academicMsg = prompt.messages.last { it is Message.Tool.Result }
                    assertTrue(academicMsg.content.contains("https://arxiv.org/abs/2602.00001"))
                    listOf(
                        Message.Tool.Call(
                            id = "call_raw_1",
                            tool = "readRawPage",
                            content = """{"url":"https://raw.githubusercontent.com/example/repo/main/version.json","maxChars":2000,"offsetChars":0}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                }
                else -> {
                    val rawMsg = prompt.messages.last { it is Message.Tool.Result }
                    assertTrue(
                        "Unexpected readRawPage output: ${rawMsg.content}",
                        rawMsg.content.contains("application/json") &&
                            rawMsg.content.contains("0.19.1") &&
                            rawMsg.content.contains("minSdk"),
                    )
                    listOf(
                        Message.Assistant(
                            content = "**核心结论**：LiteRT-LM 最新版本为 0.19.1，支持端侧 350M 混合短卷积模型。\n参考来源：https://example.org/litert-lm-release",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                }
            }
        }

        val researchAgent = ResearchAgent(
            promptExecutor = executor,
            model = testModel,
            router = router,
            pageFetcher = fetcher,
        )

        val result = researchAgent.research(
            topic = "LiteRT-LM 最新版本与端侧混合架构论文进展",
            depth = "standard",
        )

        assertTrue(result.success)
        assertEquals(2, result.searchCount)
        assertEquals(1, result.pageReadCount)
        assertTrue(result.visitedSources.contains("https://example.org/litert-lm-release"))
        assertTrue(result.visitedSources.contains("https://arxiv.org/abs/2602.00001"))
        assertTrue(result.visitedSources.contains("https://raw.githubusercontent.com/example/repo/main/version.json"))
        assertTrue(result.conclusion.contains("0.19.1"))

        val exposedTools = executor.capturedToolNames.first()
        assertTrue(
            exposedTools.containsAll(
                listOf(
                    "searchWeb",
                    "searchAcademic",
                    "searchCodeAndIssues",
                    "searchTechCommunity",
                    "searchEncyclopedia",
                    "readWebPage",
                    "readRawPage",
                    "checkSearchEngines",
                    "waitSearchTask",
                ),
            ),
        )
    }

    @Test
    fun plannerAgent_exposesExecuteResearchTaskTool_andDelegatesToResearchAgent() = runBlocking {
        val (router, fetcher) = createStubSearchEnv()
        val researchExecutor = ScriptedPromptExecutor { _, _, _ ->
            listOf(
                Message.Assistant(
                    content = "**核心结论**：已完成多源查证。",
                    metaInfo = ResponseMetaInfo.Empty,
                ),
            )
        }
        val researchAgent = ResearchAgent(
            promptExecutor = researchExecutor,
            model = testModel,
            router = router,
            pageFetcher = fetcher,
        )

        val plannerExecutor = ScriptedPromptExecutor { turn, prompt, _ ->
            if (turn == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "planner_research_1",
                        tool = "executeResearchTask",
                        content = """{"topic":"Ksoup 与 Ktor 在 Android 中的结合方式","depth":"quick"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else if (turn == 2) {
                waitForAgentTask(prompt, "waitResearchTask")
            } else {
                val researchToolResult = prompt.messages.last { it is Message.Tool.Result }
                assertTrue(researchToolResult.content.contains("已完成多源查证"))
                listOf(
                    Message.Tool.Call(
                        id = "planner_finish_1",
                        tool = "finish",
                        content = """{"action":"final_exit","summary":"调研并总结完毕"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val planner = PlannerAgent(
            promptExecutor = plannerExecutor,
            model = testModel,
            researchAgent = researchAgent,
            visionToolSet = null,
        )

        val controlToolSet = PlannerControlToolSet(
            memory = PlannerLifecycleMemory(goal = "调研 Ksoup 与 Ktor"),
        )
        val outcome = planner.runCheckpoint(
            promptText = "请先调研 Ksoup 和 Ktor 在 Android 上的最佳实践。",
            controlToolSet = controlToolSet,
        )

        assertTrue(outcome is CheckpointOutcome.FinalExit)
        assertTrue(plannerExecutor.capturedToolNames.first().contains("executeResearchTask"))
        val systemPrompt = plannerExecutor.capturedPrompts.first().messages.first().content
        assertTrue(systemPrompt.contains("executeResearchTask"))
    }
}
