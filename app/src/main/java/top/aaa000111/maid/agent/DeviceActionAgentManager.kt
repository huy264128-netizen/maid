package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeviceActionAgentManager(
    private val agent: DeviceActionAgent = DeviceActionAgent.default,
) : AgentManager("DEVICE", DeviceActionAgent.TOOLSET_NAME) {
    suspend fun execute(
        task: String,
        context: String = "",
        imageUris: List<String> = emptyList(),
    ): DeviceActionResult = deviceLease.withLock { agent.execute(task, context, imageUris) }

    @Tool
    @LLMDescription("Start an Android device operation using granted tools and model safety checks. Returns a task ID immediately. Call waitDeviceTask with that ID before using the outcome or starting a dependent operation.")
    suspend fun executeDeviceTask(
        @LLMDescription("Android device operation or goal") task: String,
        @LLMDescription("Context, constraints or hints") context: String = "",
        @LLMDescription("Screenshot or reference image URIs") imageUris: List<String> = emptyList(),
    ): String {
        val images = imageUris.toList()
        return submitTask { DeviceActionAgent.formatToolResponse(execute(task, context, images)) }
    }

    @Tool
    @LLMDescription("Wait for a task ID returned by executeDeviceTask and return its text result.")
    suspend fun waitDeviceTask(@LLMDescription("Task ID returned by this manager") taskId: String): String = waitTask(taskId)

    companion object {
        private val deviceLease = Mutex()
        val default get() = DeviceActionAgent.default.manager
    }
}
