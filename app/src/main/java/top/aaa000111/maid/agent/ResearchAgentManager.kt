package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

class ResearchAgentManager(
    private val agent: ResearchAgent = ResearchAgent(),
) : AgentManager("RESEARCH", "research_agent") {
    suspend fun research(
        topic: String,
        context: String = "",
        depth: String = "standard",
    ): ResearchExecutionResult = agent.research(topic, context, depth)

    @Tool
    @LLMDescription("Start web research with source verification. Returns a task ID immediately. Call waitResearchTask with that ID to obtain the conclusion and sources before reporting completion.")
    suspend fun executeResearchTask(
        @LLMDescription("Research topic or fact-checking question") topic: String,
        @LLMDescription("Background, date range or focus") context: String = "",
        @LLMDescription("quick, standard or deep") depth: String = "standard",
    ): String = submitTask { formatResult(research(topic, context, depth)) }

    @Tool
    @LLMDescription("Wait for a task ID returned by executeResearchTask and return its conclusion and sources.")
    suspend fun waitResearchTask(@LLMDescription("Task ID returned by this manager") taskId: String): String = waitTask(taskId)

    private fun formatResult(result: ResearchExecutionResult): String = buildString {
            appendLine(result.conclusion.ifBlank { "调研完成，但未生成文本结论。" })
            if (result.visitedSources.isNotEmpty()) {
                appendLine()
                appendLine("[调研过程检索/阅读的来源（共 ${result.visitedSources.size} 个，检索 ${result.searchCount} 次，阅读页面 ${result.pageReadCount} 次）]")
                result.visitedSources.take(10).forEach { url ->
                    appendLine("- $url")
                }
            }
        }.trimEnd()

    companion object {
        val default get() = ResearchAgent.default.manager
    }
}
