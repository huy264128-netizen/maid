package top.aaa000111.maid.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.prompt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import top.aaa000111.maid.display.VirtualDisplayManager
import top.aaa000111.maid.learning.TaskExecutionContext
import top.aaa000111.maid.prompt.PromptResources

internal class VirtualDisplayAgent(private val display: VirtualDisplayManager) {
    suspend fun runTask(task: String): String = withTimeoutOrNull(180_000L) {
        val binding = ChatAgent.resolveDefaultModelBinding(DeviceActionAgent.DEFAULT_MODEL_ROLES)
        currentCoroutineContext()[TaskExecutionContext]?.recordModel(binding.model.id, binding.provider?.id)
        val agent = AIAgent(
            promptExecutor = binding.executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("maid_virtual_display") { system(PromptResources.read("display/system.md")) },
                model = binding.model,
                maxAgentIterations = 30,
            ),
            strategy = ResearchAgent.createStrategy(
                agentScopeLabel = "虚拟显示器操作",
                strategyName = "virtual_display",
            ),
            toolRegistry = ToolRegistry { tools(display) },
            id = "virtual_display_agent",
        )
        try {
            agent.run(task).trim()
        } finally {
            agent.close()
        }
    } ?: "Virtual display task timed out."
}
