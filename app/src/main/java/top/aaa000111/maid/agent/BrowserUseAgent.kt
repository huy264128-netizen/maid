package top.aaa000111.maid.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.prompt
import android.content.Context
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import top.aaa000111.maid.browser.BrowserUseToolSet
import top.aaa000111.maid.browser.HeadlessBrowser
import top.aaa000111.maid.learning.TaskExecutionContext
import top.aaa000111.maid.prompt.PromptResources
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.skill.taskSkillGuidance
import top.aaa000111.maid.skill.withTaskSkills

internal class BrowserUseAgent(private val context: Context) {
    suspend fun runTask(task: String, startUrl: String): String {
        require(task.isNotBlank()) { "Browser task must not be blank" }
        return withTimeoutOrNull(180_000L) {
            coroutineScope {
                val binding = ChatAgent.resolveDefaultModelBinding(
                    listOf(ModelRole.COMPLEX_PLANNING, ModelRole.FAST_CHEAP),
                )
                currentCoroutineContext()[TaskExecutionContext]?.recordModel(binding.model.id, binding.provider?.id)
                val browser = HeadlessBrowser.open(context)
                try {
                    val browserTools = BrowserUseToolSet(browser, this, context.cacheDir)
                    try {
                        val initialTab = browser.openTab(startUrl.trim().ifEmpty { null })
                        val systemPrompt = PromptResources.read("browser/system.md") + taskSkillGuidance()
                        val agent = AIAgent(
                            promptExecutor = binding.executor,
                            agentConfig = AIAgentConfig(
                                prompt = prompt("maid_browser_use") { system(systemPrompt) },
                                model = binding.model,
                                maxAgentIterations = 40,
                            ),
                            strategy = ResearchAgent.createStrategy(
                                agentScopeLabel = "网页操作",
                                strategyName = "browser_use",
                            ),
                            toolRegistry = withTaskSkills(ToolRegistry { tools(browserTools) }),
                            id = "browser_use_agent",
                        )
                        try {
                            agent.run("目标：${task.trim()}\n初始标签页：$initialTab").trim()
                        } finally {
                            agent.close()
                        }
                    } finally {
                        browserTools.cancelTimers()
                    }
                } finally {
                    browser.close()
                }
            }
        } ?: "浏览器任务超时。"
    }
}
