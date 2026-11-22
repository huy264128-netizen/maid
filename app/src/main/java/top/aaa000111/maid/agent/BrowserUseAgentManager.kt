package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import android.content.Context

class BrowserUseAgentManager(context: Context) : AgentManager("BROWSER", "browser_use") {
    private val agent = BrowserUseAgent(context.applicationContext)

    @Tool
    @LLMDescription("Start an in-app WebView task on public HTTPS pages when the page requires browser interaction. Returns an ID. Use waitBrowserTask to get the outcome.")
    suspend fun executeBrowserTask(
        @LLMDescription("User's browser task and constraints") task: String,
        @LLMDescription("Starting public HTTPS URL. Leave empty to start with a blank tab.") startUrl: String = "",
    ): String = submitTask { agent.runTask(task, startUrl) }

    @Tool
    @LLMDescription("Wait for a browser task ID from executeBrowserTask and return the outcome.")
    suspend fun waitBrowserTask(@LLMDescription("Browser task ID") taskId: String): String = waitTask(taskId)
}
