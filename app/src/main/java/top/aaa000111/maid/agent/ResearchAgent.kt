package top.aaa000111.maid.agent

import top.aaa000111.maid.prompt.PromptResources
import kotlinx.coroutines.currentCoroutineContext
import top.aaa000111.maid.learning.TaskExecutionContext
import top.aaa000111.maid.skill.withTaskSkills
import top.aaa000111.maid.skill.taskSkillGuidance

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.params.LLMParams
import top.aaa000111.maid.chat.nodeExecuteToolWithStatus
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.search.SearchEngineCategory
import top.aaa000111.maid.search.SearchEngineRouter
import top.aaa000111.maid.search.SearchFilterStage
import top.aaa000111.maid.search.SearchQuery
import top.aaa000111.maid.search.SearchResponse
import top.aaa000111.maid.search.WebPageFetcher
import top.aaa000111.maid.search.engines.ArxivSearchEngine
import top.aaa000111.maid.search.engines.GitHubSearchEngine
import top.aaa000111.maid.search.engines.HackerNewsSearchEngine
import top.aaa000111.maid.settings.AgentStepConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ResearchAgent] 单次信息调研任务执行结果。
 */
data class ResearchExecutionResult(
    val success: Boolean,
    val topic: String,
    val conclusion: String,
    val visitedSources: List<String> = emptyList(),
    val provider: LlmProvider? = null,
    val modelId: String = "",
    val searchCount: Int = 0,
    val pageReadCount: Int = 0,
)

/**
 * [ResearchAgent] 内部使用的分类联网检索与网页阅读工具集（8 个语义清晰的工具）：
 * 1. `searchWeb`：通用网页搜索（SearXNG / DuckDuckGo / Brave / Bing / CN Bing / Qwant Lite / Mojeek）；
 * 2. `searchAcademic`：学术论文搜索（arXiv 官方 XML API）；
 * 3. `searchCodeAndIssues`：开源仓库与 Issue 搜索（GitHub Search API）；
 * 4. `searchTechCommunity`：开发者社区讨论搜索（Hacker News Algolia API）；
 * 5. `searchEncyclopedia`：百科词条搜索（Wikipedia 中/英文自动路由）；
 * 6. `readWebPage`：Ksoup 清洗提取网页正文（适合阅读文章与文档）；
 * 7. `readRawPage`：原始页面/文档文本抓取，支持偏移分页（适合 HTML 源码、JSON/XML/Markdown 原始文件）；
 * 8. `checkSearchEngines`：并行探测全部或指定分类搜索引擎的可用性与延迟。
 */
@LLMDescription("面向 ResearchAgent 的分类联网检索、可用性探测与网页/原始文本阅读工具集")
class ResearchInternalToolSet(
    private val router: SearchEngineRouter,
    private val pageFetcher: WebPageFetcher,
) : ToolSet {

    private val searchCounter = AtomicInteger(0)
    private val pageReadCounter = AtomicInteger(0)
    private val collectedUrls = LinkedHashSet<String>()

    val searchCount: Int
        get() = searchCounter.get()

    val pageReadCount: Int
        get() = pageReadCounter.get()

    fun snapshotSources(): List<String> = synchronized(collectedUrls) {
        collectedUrls.toList()
    }

    @Tool
    @LLMDescription(
        "执行通用网页搜索（分类：GENERAL_WEB，涵盖 SearXNG / DuckDuckGo / Brave / Bing / CN Bing / Qwant Lite / Mojeek，" +
            "自动绕过被墙或限流引擎，经 Ksoup 清洗后默认由端侧 TinyModel 批量采纳相关条目）。",
    )
    suspend fun searchWeb(
        @LLMDescription("搜索关键词，建议简洁精准")
        query: String,
        @LLMDescription("返回最大结果条数（1~20），默认 8")
        maxResults: Int = SearchQuery.DEFAULT_MAX_RESULTS,
        @LLMDescription("可选：优先指定的通用引擎 ID（searxng / duckduckgo / brave / bing / cn_bing / qwant_lite / mojeek），留空自动路由")
        engineId: String = "",
        @LLMDescription("是否并行联合多个可用通用引擎并按 URL 去重，默认 false")
        multiEngine: Boolean = false,
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return "错误：搜索关键词不能为空。"
        val safeMax = maxResults.coerceIn(1, SearchQuery.MAX_ALLOWED_RESULTS)
        searchCounter.incrementAndGet()
        return runCatching {
            val searchQuery = SearchQuery(query = normalizedQuery, maxResults = safeMax)
            val response = if (multiEngine && engineId.isBlank()) {
                router.searchMultiEngine(
                    query = searchQuery,
                    category = SearchEngineCategory.GENERAL_WEB,
                    maxEngines = 2,
                    useTinyModelFilter = useTinyModelFilter,
                )
            } else {
                router.search(
                    query = searchQuery,
                    category = SearchEngineCategory.GENERAL_WEB,
                    preferredEngineId = engineId.trim().takeIf { it.isNotEmpty() },
                    useTinyModelFilter = useTinyModelFilter,
                )
            }
            recordSources(response)
            formatSearchResponse(response)
        }.getOrElse { error ->
            "通用网页搜索失败（query=\"$normalizedQuery\"）：${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("在学术论文源（arXiv）检索论文与预印本，返回标题、作者、发表日期、摘要与论文/PDF 链接。")
    suspend fun searchAcademic(
        @LLMDescription("学术检索关键词（建议使用英文，也可带 arXiv 字段前缀如 ti: / au: / cat:）")
        query: String,
        @LLMDescription("返回最大论文条数（1~20），默认 8")
        maxResults: Int = SearchQuery.DEFAULT_MAX_RESULTS,
        @LLMDescription("排序方式：relevance（相关性，默认）/ submittedDate（最新提交）/ lastUpdatedDate（最近更新）")
        sortBy: String = "relevance",
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return "错误：学术检索关键词不能为空。"
        val safeMax = maxResults.coerceIn(1, SearchQuery.MAX_ALLOWED_RESULTS)
        searchCounter.incrementAndGet()
        return runCatching {
            val response = router.search(
                query = SearchQuery(
                    query = normalizedQuery,
                    maxResults = safeMax,
                    extraParams = mapOf(ArxivSearchEngine.PARAM_SORT_BY to sortBy),
                ),
                category = SearchEngineCategory.ACADEMIC,
                useTinyModelFilter = useTinyModelFilter,
            )
            recordSources(response)
            formatSearchResponse(response)
        }.getOrElse { error ->
            "学术论文搜索失败（query=\"$normalizedQuery\"）：${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("在开发者源（GitHub）检索开源仓库或 Issue/PR 讨论，适合查开源库、报错排查与版本进展。")
    suspend fun searchCodeAndIssues(
        @LLMDescription("GitHub 检索关键词（支持常规语法如 language:kotlin、repo:owner/name 等）")
        query: String,
        @LLMDescription("检索范围：repositories（开源仓库，默认）或 issues（Issue 与 PR 讨论）")
        scope: String = GitHubSearchEngine.SCOPE_REPOSITORIES,
        @LLMDescription("返回最大结果条数（1~20），默认 8")
        maxResults: Int = SearchQuery.DEFAULT_MAX_RESULTS,
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return "错误：GitHub 检索关键词不能为空。"
        val safeMax = maxResults.coerceIn(1, SearchQuery.MAX_ALLOWED_RESULTS)
        searchCounter.incrementAndGet()
        return runCatching {
            val response = router.search(
                query = SearchQuery(
                    query = normalizedQuery,
                    maxResults = safeMax,
                    extraParams = mapOf(GitHubSearchEngine.PARAM_SCOPE to scope),
                ),
                category = SearchEngineCategory.DEVELOPER,
                useTinyModelFilter = useTinyModelFilter,
            )
            recordSources(response)
            formatSearchResponse(response)
        }.getOrElse { error ->
            "GitHub 搜索失败（query=\"$normalizedQuery\"）：${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("在技术社区（Hacker News）检索开发者真实讨论与行业趋势，返回热度、评论数、原文链接与 HN 讨论链接。")
    suspend fun searchTechCommunity(
        @LLMDescription("技术社区检索关键词（建议使用英文）")
        query: String,
        @LLMDescription("返回最大结果条数（1~20），默认 8")
        maxResults: Int = SearchQuery.DEFAULT_MAX_RESULTS,
        @LLMDescription("排序方式：relevance（相关性与热度，默认）或 date（最新发布）")
        sortBy: String = "relevance",
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return "错误：社区检索关键词不能为空。"
        val safeMax = maxResults.coerceIn(1, SearchQuery.MAX_ALLOWED_RESULTS)
        searchCounter.incrementAndGet()
        return runCatching {
            val response = router.search(
                query = SearchQuery(
                    query = normalizedQuery,
                    maxResults = safeMax,
                    extraParams = mapOf(HackerNewsSearchEngine.PARAM_SORT_BY to sortBy),
                ),
                category = SearchEngineCategory.TECH_COMMUNITY,
                useTinyModelFilter = useTinyModelFilter,
            )
            recordSources(response)
            formatSearchResponse(response)
        }.getOrElse { error ->
            "技术社区搜索失败（query=\"$normalizedQuery\"）：${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("在百科参考源（Wikipedia）查证基础概念、术语定义与背景知识，支持中/英文自动路由。")
    suspend fun searchEncyclopedia(
        @LLMDescription("要查证的百科词条或概念关键词")
        query: String,
        @LLMDescription("语种：auto（按查询自动判断，默认）/ zh（中文维基）/ en（英文维基）")
        language: String = "auto",
        @LLMDescription("返回最大条数（1~20），默认 6")
        maxResults: Int = 6,
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return "错误：百科检索关键词不能为空。"
        val safeMax = maxResults.coerceIn(1, SearchQuery.MAX_ALLOWED_RESULTS)
        val langParam = language.trim().takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) }
        searchCounter.incrementAndGet()
        return runCatching {
            val response = router.search(
                query = SearchQuery(
                    query = normalizedQuery,
                    maxResults = safeMax,
                    language = langParam,
                ),
                category = SearchEngineCategory.ENCYCLOPEDIA,
                useTinyModelFilter = useTinyModelFilter,
            )
            recordSources(response)
            formatSearchResponse(response)
        }.getOrElse { error ->
            "百科搜索失败（query=\"$normalizedQuery\"）：${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("抓取指定 http/https 网页并使用 Ksoup 去除导航/脚本等噪音、提取干净正文，适合阅读文章与文档。")
    suspend fun readWebPage(
        @LLMDescription("要阅读的完整网页 URL（必须是 http:// 或 https://）")
        url: String,
        @LLMDescription("最多返回的正文字符数（500~24000），默认 6000")
        maxChars: Int = WebPageFetcher.DEFAULT_MAX_CHARS,
    ): String {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isEmpty()) return "错误：网页 URL 不能为空。"
        pageReadCounter.incrementAndGet()
        return runCatching {
            val snapshot = pageFetcher.fetch(url = normalizedUrl, maxChars = maxChars)
            synchronized(collectedUrls) {
                collectedUrls += snapshot.finalUrl
            }
            buildString {
                appendLine("网页标题：${snapshot.title}")
                appendLine("最终 URL：${snapshot.finalUrl}")
                appendLine(
                    "状态：HTTP ${snapshot.statusCode}，清洗后正文 ${snapshot.originalCharLength} 字" +
                        if (snapshot.truncated) "（已截断）" else "",
                )
                appendLine()
                append(snapshot.content)
            }.trimEnd()
        }.getOrElse { error ->
            "网页读取失败（url=\"$normalizedUrl\"）：${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription(
        "抓取指定 http/https URL 的原始响应文本（不做 Ksoup 标签剥离），支持偏移分页；" +
            "适合读取原始 HTML 源码、JSON/XML/YAML/Markdown 原始文件、结构化元数据，或在 readWebPage 漏提内容时排查原始页面。",
    )
    suspend fun readRawPage(
        @LLMDescription("要抓取原始内容的完整 URL（必须是 http:// 或 https://）")
        url: String,
        @LLMDescription("本次最多返回的字符数（500~32000），默认 8000")
        maxChars: Int = WebPageFetcher.DEFAULT_RAW_MAX_CHARS,
        @LLMDescription("起始字符偏移量（用于翻页读取较长原始文档），默认 0")
        offsetChars: Int = 0,
    ): String {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isEmpty()) return "错误：原始页面 URL 不能为空。"
        pageReadCounter.incrementAndGet()
        return runCatching {
            val snapshot = pageFetcher.fetchRaw(
                url = normalizedUrl,
                maxChars = maxChars,
                offsetChars = offsetChars,
            )
            synchronized(collectedUrls) {
                collectedUrls += snapshot.finalUrl
            }
            val endOffset = snapshot.offsetChars + snapshot.content.length
            buildString {
                appendLine("最终 URL：${snapshot.finalUrl}")
                appendLine("Content-Type：${snapshot.contentType}")
                appendLine(
                    "状态：HTTP ${snapshot.statusCode} | 范围：${snapshot.offsetChars}..$endOffset / 共 ${snapshot.totalCharLength} 字符" +
                        if (snapshot.truncated) "（可继续传 offsetChars=$endOffset 翻页读取）" else "（已读完）",
                )
                appendLine()
                append(snapshot.content)
            }.trimEnd()
        }.getOrElse { error ->
            "原始页面读取失败（url=\"$normalizedUrl\"）：${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("并行探测全部或指定分类下各个搜索引擎是否可用（自动识别被墙、未配置、超时或反爬限制）及响应延迟。")
    suspend fun checkSearchEngines(
        @LLMDescription("可选分类过滤：all（全部，默认）/ general_web / academic / developer / tech_community / encyclopedia")
        category: String = "all",
        @LLMDescription("是否忽略本地缓存并强制重新发起网络探测，默认 false")
        forceRefresh: Boolean = false,
    ): String {
        val parsedCategory = SearchEngineCategory.fromKeyOrNull(category)
        val statuses = router.probeAll(
            category = parsedCategory,
            forceRefresh = forceRefresh,
        )
        return buildString {
            val scopeLabel = parsedCategory?.displayName ?: "全部分类"
            appendLine("搜索引擎可用性探测结果（$scopeLabel）：")
            statuses.forEach { status ->
                val badge = if (status.available) "可用" else "不可用"
                val codeInfo = status.statusCode?.let { "HTTP $it, " }.orEmpty()
                appendLine(
                    "- [${status.category.storageKey}] ${status.engineId} (${status.displayName}): $badge" +
                        " [${codeInfo}${status.latencyMs}ms] ${status.reason}",
                )
            }
        }.trimEnd()
    }

    private fun recordSources(response: SearchResponse) {
        synchronized(collectedUrls) {
            response.results.forEach { collectedUrls += it.url }
        }
    }

    companion object {
        internal fun formatSearchResponse(response: SearchResponse): String = buildString {
            val stageLabel = when (response.filterStage) {
                SearchFilterStage.NONE -> "未过滤"
                SearchFilterStage.RULE_ONLY -> "规则清洗"
                SearchFilterStage.TINY_MODEL_ACCEPTED -> "规则清洗 + TinyModel 批量采纳"
                SearchFilterStage.TINY_MODEL_FALLBACK -> "规则清洗（TinyModel 回退）"
            }
            appendLine(
                "搜索完成 | 分类：${response.category.displayName} | 引擎：${response.engineId} | " +
                    "过滤：$stageLabel | 返回 ${response.results.size}/${response.rawCandidateCount} 条 | 耗时 ${response.elapsedMs}ms",
            )
            response.warning?.takeIf { it.isNotBlank() }?.let { warning ->
                appendLine("提示：$warning")
            }
            if (response.results.isEmpty()) {
                appendLine("未找到有效结果，请尝试更换关键词或切换其他类型搜索工具。")
                return@buildString
            }
            appendLine()
            response.results.forEachIndexed { idx, item ->
                appendLine("[${idx + 1}] ${item.title}")
                appendLine("    URL: ${item.url}")
                if (item.snippet.isNotBlank()) {
                    appendLine("    摘要: ${item.snippet}")
                }
            }
        }.trimEnd()
    }
}

/**
 * 联网信息调研智能体（ResearchAgent）：
 * 1. 负责拆解调研问题、按领域调用分类搜索工具与页面阅读工具（[readWebPage] / [readRawPage]），
 *    交叉验证并输出结构化、带来源引用的结论；
 * 2. 模型绑定优先使用 [ModelRole.COMPLEX_PLANNING]（回退 [ModelRole.FAST_CHEAP]）；
 * 3. 可通过 [asToolRegistry] 作为 `executeResearchTask` 工具挂载给 [PlannerAgent] 与 [ChatAgent]。
 */
class ResearchAgent(
    private val modelRoles: List<ModelRole> = DEFAULT_MODEL_ROLES,
    private val modelBindingProvider: (suspend () -> ChatModelBinding)? = null,
    private val routerProvider: () -> SearchEngineRouter = { AppRuntime.getOrCreateSearchEngineRouter() },
    private val pageFetcherProvider: () -> WebPageFetcher = { AppRuntime.getOrCreateWebPageFetcher() },
    private val extraToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxIterations: Int? = null,
    private val temperature: Double = DEFAULT_TEMPERATURE,
    private val directImageInputProvider: (suspend () -> Boolean)? = null,
    private val imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
) {

    constructor(
        promptExecutor: PromptExecutor,
        model: LLModel,
        router: SearchEngineRouter,
        pageFetcher: WebPageFetcher,
        provider: LlmProvider? = null,
        extraToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        maxIterations: Int? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        directImageInputProvider: (suspend () -> Boolean)? = null,
        imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
    ) : this(
        modelRoles = DEFAULT_MODEL_ROLES,
        modelBindingProvider = {
            ChatModelBinding(
                executor = promptExecutor,
                model = model,
                provider = provider,
            )
        },
        routerProvider = { router },
        pageFetcherProvider = { pageFetcher },
        extraToolRegistry = extraToolRegistry,
        systemPrompt = systemPrompt,
        maxIterations = maxIterations,
        temperature = temperature,
        directImageInputProvider = directImageInputProvider,
        imageAttachmentResolver = imageAttachmentResolver,
    )

    val manager: ResearchAgentManager by lazy { ResearchAgentManager(this) }

    /** 将本 ResearchAgent 封装为 Koog `ToolRegistry`，供上层 Agent 调用。 */
    fun asToolRegistry(): ToolRegistry = ResearchAgentManager(this).asToolRegistry()

    /**
     * 对指定主题执行端到端联网搜索、正文核实并综合产出调研结论。
     */
    suspend fun research(
        topic: String,
        context: String = "",
        depth: String = "standard",
    ): ResearchExecutionResult {
        val normalizedTopic = topic.trim()
        require(normalizedTopic.isNotEmpty()) { "Research topic must not be blank" }

        val binding = modelBindingProvider?.invoke()
            ?: ChatAgent.resolveDefaultModelBinding(modelRoles)
        currentCoroutineContext()[TaskExecutionContext]?.recordModel(binding.model.id, binding.provider?.id)

        val internalTools = ResearchInternalToolSet(
            router = routerProvider(),
            pageFetcher = pageFetcherProvider(),
        )

        val useDirectImageInput = directImageInputProvider?.invoke()
            ?: DeviceActionAgent.isComplexPlanningSameAsComplexVision()
        val directImageToolSet = if (useDirectImageInput) {
            DirectImageReadToolSet(
                imageAttachmentResolver = imageAttachmentResolver ?: VisionToolSet::resolveImageAttachment,
            )
        } else {
            null
        }

        var effectiveToolRegistry = ToolRegistry {
            tools(internalTools)
        }
        if (directImageToolSet != null) {
            effectiveToolRegistry += ToolRegistry { tools(directImageToolSet) }
        }
        if (extraToolRegistry.tools.isNotEmpty()) {
            val existing = effectiveToolRegistry.tools.mapTo(HashSet()) { it.name }
            val additional = extraToolRegistry.tools.filter { it.name !in existing }
            if (additional.isNotEmpty()) {
                effectiveToolRegistry += ToolRegistry { tools(additional) }
            }
        }

        val normalizedDepth = normalizeDepth(depth)
        val formattedPrompt = buildResearchPrompt(
            topic = normalizedTopic,
            context = context.trim(),
            depth = normalizedDepth,
        )

        val resolvedMaxIterations = (maxIterations ?: defaultIterationsForDepth(normalizedDepth))
            .coerceAtLeast(4)

        val effectiveSystemPrompt = buildEffectiveSystemPrompt(
            baseSystemPrompt = systemPrompt + taskSkillGuidance(),
            hasDirectImageReadTool = directImageToolSet != null,
            maxIterations = resolvedMaxIterations,
        )

        val initialPrompt = prompt(
            id = PROMPT_ID,
            params = LLMParams(temperature = temperature),
        ) {
            system(effectiveSystemPrompt)
        }

        val agentConfig = AIAgentConfig(
            prompt = initialPrompt,
            model = binding.model,
            maxAgentIterations = resolvedMaxIterations,
        )

        val agent = AIAgent(
            promptExecutor = binding.executor,
            agentConfig = agentConfig,
            strategy = createStrategy(directImageToolSet = directImageToolSet),
            toolRegistry = withTaskSkills(effectiveToolRegistry),
            id = AGENT_NAME,
        )

        val conclusion = try {
            agent.run(formattedPrompt).trim()
        } finally {
            runCatching { agent.close() }
        }

        return ResearchExecutionResult(
            success = conclusion.isNotEmpty(),
            topic = normalizedTopic,
            conclusion = conclusion,
            visitedSources = internalTools.snapshotSources(),
            provider = binding.provider,
            modelId = binding.model.id,
            searchCount = internalTools.searchCount,
            pageReadCount = internalTools.pageReadCount,
        )
    }

    companion object {
        const val AGENT_NAME = "research_agent"
        const val PROMPT_ID = "research_agent_prompt"
        const val DEFAULT_TEMPERATURE = 0.2

        val DEFAULT_MODEL_ROLES: List<ModelRole> = listOf(
            ModelRole.COMPLEX_PLANNING,
            ModelRole.FAST_CHEAP,
        )

        val default: ResearchAgent by lazy { ResearchAgent() }

        val DEFAULT_SYSTEM_PROMPT: String
            get() = PromptResources.read("research/system.md")

        internal fun normalizeDepth(raw: String): String = when (raw.trim().lowercase()) {
            "quick", "fast", "浅", "快速" -> "quick"
            "deep", "thorough", "深入", "深度" -> "deep"
            else -> "standard"
        }

        internal fun defaultIterationsForDepth(depth: String): Int = when (depth) {
            "quick" -> 16
            "deep" -> 44
            else -> 30
        }

        internal fun buildEffectiveSystemPrompt(
            baseSystemPrompt: String,
            hasDirectImageReadTool: Boolean = false,
            maxIterations: Int = defaultIterationsForDepth("standard"),
        ): String = buildString {
            appendLine(baseSystemPrompt.trim())
            val safeMaxIterations = maxIterations.coerceAtLeast(1)
            val estimatedToolCalls = AgentStepConfig.estimateResearchToolCalls(safeMaxIterations)
            appendLine()
            appendLine("[当前执行步数限制]")
            append("当前联网调研任务的最大执行步数上限为 $safeMaxIterations 步（约支持 $estimatedToolCalls 次工具调用）。")
            append(PromptResources.read("research/step_budget.md"))
            if (hasDirectImageReadTool) {
                appendLine()
                appendLine()
                append(PromptResources.read("research/direct_images.md"))
            }
        }.trimEnd()

        internal fun buildResearchPrompt(
            topic: String,
            context: String,
            depth: String,
        ): String = buildString {
            appendLine("[调研主题]")
            appendLine(topic)
            if (context.isNotEmpty()) {
                appendLine()
                appendLine("[背景与约束]")
                appendLine(context)
            }
            appendLine()
            appendLine("[调研深度要求]")
            when (depth) {
                "quick" -> append(PromptResources.read("research/depth_quick.md"))
                "deep" -> append(PromptResources.read("research/depth_deep.md"))
                else -> append(PromptResources.read("research/depth_standard.md"))
            }
        }.trimEnd()

        internal fun createStrategy(
            directImageToolSet: DirectImageReadToolSet? = null,
        ): AIAgentGraphStrategy<String, String> = strategy(AGENT_NAME) {
            val nodeCallLLM by nodeLLMRequest(
                name = "research_llm_request",
                allowToolCalls = true,
            )
            val nodeExecuteTool by nodeExecuteToolWithStatus(
                name = "research_execute_tool",
                agentScopeLabel = "调研",
            )
            val nodeSendToolResult by nodeLLMSendToolResultWithDirectImages(
                name = "research_send_tool_result",
                directImageToolSet = directImageToolSet,
                allowToolCalls = true,
            )

            edge(nodeStart forwardTo nodeCallLLM)
            edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
            edge(nodeExecuteTool forwardTo nodeSendToolResult)
            edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
            edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
            edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
        }
    }
}
