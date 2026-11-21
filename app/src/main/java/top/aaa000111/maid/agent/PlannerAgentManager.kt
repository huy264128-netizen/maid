package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.currentChatSession

class PlannerAgentManager(
    private val lifecycle: PlannerLifecycleManager = PlannerLifecycleManager.default,
    private val session: ChatSession? = null,
) : AgentManager("PLANNER", TOOLSET_NAME) {
    val lastCompletedPlan get() = lifecycle.lastCompletedMemory
    fun activePlanCount(): Int = lifecycle.activePlanCount()
    fun activePlan(planId: String): PlannerLifecycleMemory.Snapshot? = lifecycle.activeMemorySnapshot(planId)

    suspend fun runPlan(
        goal: String,
        context: String = "",
        imageUris: List<String> = emptyList(),
        session: ChatSession? = this.session,
    ): PlannerExecutionResult = lifecycle.runPlan(goal, context, imageUris, session)

    @Tool
    @LLMDescription("Start a complex multi-step planning or device automation task. Returns a task ID immediately. Call waitPlannerTask with that ID to obtain the final result before reporting completion. New chat messages are consumed at planning checkpoints.")
    suspend fun planAndExecuteTask(
        @LLMDescription("Overall goal or multi-step task") goal: String,
        @LLMDescription("Background context, constraints or preferences") context: String = "",
        @LLMDescription("Image attachment IDs or image URIs") imageUris: List<String> = emptyList(),
    ): String {
        val activeSession = session ?: currentChatSession()
        val images = imageUris.toList()
        return submitTask(activeSession?.id) {
            formatToolOutput(runPlan(goal, context, images, activeSession))
        }
    }

    @Tool
    @LLMDescription("Wait for a task ID returned by planAndExecuteTask and return the final text result.")
    suspend fun waitPlannerTask(@LLMDescription("Task ID returned by this manager") taskId: String): String = waitTask(taskId)

    companion object {
        const val TOOLSET_NAME = "planner_bridge"
        val default get() = PlannerLifecycleManager.default.manager

        internal fun formatToolOutput(result: PlannerExecutionResult): String = buildString {
            appendLine("planId=${result.planId}")
            appendLine("success=${result.success}")
            appendLine("checkpointsCompleted=${result.checkpointsCompleted}")
            appendLine("finalResult:")
            append(result.finalContent)
            if (result.trailingExternalInputs.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("[规划结束前收到的额外用户输入（请在最终回复中一并回应）]")
                result.trailingExternalInputs.forEachIndexed { index, input ->
                    appendLine("${index + 1}. ${input.normalizedText.ifEmpty { "(附带文件/图片)" }}")
                }
            }
        }.trimEnd()
    }
}
