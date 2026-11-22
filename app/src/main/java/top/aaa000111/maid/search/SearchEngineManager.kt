package top.aaa000111.maid.search

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.agent.AgentManager
import top.aaa000111.maid.agent.ResearchInternalToolSet
import top.aaa000111.maid.search.engines.GitHubSearchEngine

/** 每次调研持有一个实例；具体搜索、阅读和来源记录由原工具集完成。 */
class SearchEngineManager(
    private val tools: ResearchInternalToolSet,
) : AgentManager("SEARCH", "search_engine") {
    @Tool
    @LLMDescription("立即提交异步任务并返回 ID；必须调用 waitSearchTask 获取结果。执行通用网页搜索（分类：GENERAL_WEB，涵盖 SearXNG / DuckDuckGo / Brave / Bing / CN Bing / Qwant Lite / Mojeek，" +
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
    ): String = submitTask { tools.searchWeb(query, maxResults, engineId, multiEngine, useTinyModelFilter) }

    @Tool
    @LLMDescription("立即提交异步任务并返回 ID；必须调用 waitSearchTask 获取结果。在学术论文源（arXiv）检索论文与预印本，返回标题、作者、发表日期、摘要与论文/PDF 链接。")
    suspend fun searchAcademic(
        @LLMDescription("学术检索关键词（建议使用英文，也可带 arXiv 字段前缀如 ti: / au: / cat:）")
        query: String,
        @LLMDescription("返回最大论文条数（1~20），默认 8")
        maxResults: Int = SearchQuery.DEFAULT_MAX_RESULTS,
        @LLMDescription("排序方式：relevance（相关性，默认）/ submittedDate（最新提交）/ lastUpdatedDate（最近更新）")
        sortBy: String = "relevance",
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String = submitTask { tools.searchAcademic(query, maxResults, sortBy, useTinyModelFilter) }

    @Tool
    @LLMDescription("立即提交异步任务并返回 ID；必须调用 waitSearchTask 获取结果。在开发者源（GitHub）检索开源仓库或 Issue/PR 讨论，适合查开源库、报错排查与版本进展。")
    suspend fun searchCodeAndIssues(
        @LLMDescription("GitHub 检索关键词（支持常规语法如 language:kotlin、repo:owner/name 等）")
        query: String,
        @LLMDescription("检索范围：repositories（开源仓库，默认）或 issues（Issue 与 PR 讨论）")
        scope: String = GitHubSearchEngine.SCOPE_REPOSITORIES,
        @LLMDescription("返回最大结果条数（1~20），默认 8")
        maxResults: Int = SearchQuery.DEFAULT_MAX_RESULTS,
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String = submitTask { tools.searchCodeAndIssues(query, scope, maxResults, useTinyModelFilter) }

    @Tool
    @LLMDescription("立即提交异步任务并返回 ID；必须调用 waitSearchTask 获取结果。在技术社区（Hacker News）检索开发者真实讨论与行业趋势，返回热度、评论数、原文链接与 HN 讨论链接。")
    suspend fun searchTechCommunity(
        @LLMDescription("技术社区检索关键词（建议使用英文）")
        query: String,
        @LLMDescription("返回最大结果条数（1~20），默认 8")
        maxResults: Int = SearchQuery.DEFAULT_MAX_RESULTS,
        @LLMDescription("排序方式：relevance（相关性与热度，默认）或 date（最新发布）")
        sortBy: String = "relevance",
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String = submitTask { tools.searchTechCommunity(query, maxResults, sortBy, useTinyModelFilter) }

    @Tool
    @LLMDescription("立即提交异步任务并返回 ID；必须调用 waitSearchTask 获取结果。在百科参考源（Wikipedia）查证基础概念、术语定义与背景知识，支持中/英文自动路由。")
    suspend fun searchEncyclopedia(
        @LLMDescription("要查证的百科词条或概念关键词")
        query: String,
        @LLMDescription("语种：auto（按查询自动判断，默认）/ zh（中文维基）/ en（英文维基）")
        language: String = "auto",
        @LLMDescription("返回最大条数（1~20），默认 6")
        maxResults: Int = 6,
        @LLMDescription("是否启用端侧 TinyModel 批量相关性过滤，默认 true")
        useTinyModelFilter: Boolean = true,
    ): String = submitTask { tools.searchEncyclopedia(query, language, maxResults, useTinyModelFilter) }

    @Tool
    @LLMDescription("立即提交异步任务并返回 ID；必须调用 waitSearchTask 获取结果。抓取指定 http/https 网页并使用 Ksoup 去除导航/脚本等噪音、提取干净正文，适合阅读文章与文档。")
    suspend fun readWebPage(
        @LLMDescription("要阅读的完整网页 URL（必须是 http:// 或 https://）")
        url: String,
        @LLMDescription("最多返回的正文字符数（500~24000），默认 6000")
        maxChars: Int = WebPageFetcher.DEFAULT_MAX_CHARS,
    ): String = submitTask { tools.readWebPage(url, maxChars) }

    @Tool
    @LLMDescription("立即提交异步任务并返回 ID；必须调用 waitSearchTask 获取结果。抓取指定 http/https URL 的原始响应文本（不做 Ksoup 标签剥离），支持偏移分页；" +
            "适合读取原始 HTML 源码、JSON/XML/YAML/Markdown 原始文件、结构化元数据，或在 readWebPage 漏提内容时排查原始页面。",
    )
    suspend fun readRawPage(
        @LLMDescription("要抓取原始内容的完整 URL（必须是 http:// 或 https://）")
        url: String,
        @LLMDescription("本次最多返回的字符数（500~32000），默认 8000")
        maxChars: Int = WebPageFetcher.DEFAULT_RAW_MAX_CHARS,
        @LLMDescription("起始字符偏移量（用于翻页读取较长原始文档），默认 0")
        offsetChars: Int = 0,
    ): String = submitTask { tools.readRawPage(url, maxChars, offsetChars) }

    @Tool
    @LLMDescription("立即提交异步任务并返回 ID；必须调用 waitSearchTask 获取结果。并行探测全部或指定分类下各个搜索引擎是否可用（自动识别被墙、未配置、超时或反爬限制）及响应延迟。")
    suspend fun checkSearchEngines(
        @LLMDescription("可选分类过滤：all（全部，默认）/ general_web / academic / developer / tech_community / encyclopedia")
        category: String = "all",
        @LLMDescription("是否忽略本地缓存并强制重新发起网络探测，默认 false")
        forceRefresh: Boolean = false,
    ): String = submitTask { tools.checkSearchEngines(category, forceRefresh) }

    @Tool
    @LLMDescription("用系统 WebView 执行网页 JavaScript，再用 Ksoup 提取正文，适合动态网页。返回任务 ID，请调用 waitSearchTask 取结果。")
    suspend fun readRenderedWebPage(
        @LLMDescription("公开网页的完整 HTTPS URL") url: String,
        @LLMDescription("最多返回的正文字符数（500~24000），默认 6000")
        maxChars: Int = WebPageFetcher.DEFAULT_MAX_CHARS,
    ): String = submitTask { tools.readRenderedWebPage(url, maxChars) }

    @Tool
    @LLMDescription("等待本 Manager 的搜索、阅读或探测任务，返回最终文本结果；可重复等待。")
    suspend fun waitSearchTask(
        @LLMDescription("搜索工具返回的任务 ID") taskId: String,
    ): String = waitTask(taskId)
}
