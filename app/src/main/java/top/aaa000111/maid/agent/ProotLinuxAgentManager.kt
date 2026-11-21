package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.currentChatSession

class ProotLinuxAgentManager(
    private val lifecycle: ProotLinuxAgentLifecycleManager = ProotLinuxAgentLifecycleManager.default,
    private val session: ChatSession? = null,
) : AgentManager("LINUX", TOOLSET_NAME) {
    val terminalState get() = lifecycle.state

    suspend fun runTask(
        task: String,
        context: String = "",
        session: ChatSession? = this.session,
        fileAttachmentIds: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        includeLlmApiEnv: Boolean = false,
        llmApiProviders: List<String> = emptyList(),
        sessionIdOverride: String? = null,
    ): ProotLinuxExecutionResult = lifecycle.runTask(
        task = task, context = context, session = session,
        fileAttachmentIds = fileAttachmentIds, imageUris = imageUris,
        includeLlmApiEnv = includeLlmApiEnv, llmApiProviders = llmApiProviders,
        sessionIdOverride = sessionIdOverride,
    )

    @Tool
    @LLMDescription("Start a Linux, scripting, file-processing or package-installation task in the dedicated PRoot terminal. Returns a task ID immediately. Call waitLinuxTask with that ID for the final result and exported attachments before reporting completion.")
    suspend fun executeProotLinuxTask(
        @LLMDescription("Linux task or goal") task: String,
        @LLMDescription("Constraints, expected output or file hints") context: String = "",
        @LLMDescription("Session file attachment IDs or URIs") fileAttachmentIds: List<String> = emptyList(),
        @LLMDescription("Session image attachment IDs or URIs") imageUris: List<String> = emptyList(),
    ): String {
        val activeSession = session ?: currentChatSession()
        val files = fileAttachmentIds.toList()
        val images = imageUris.toList()
        return submitTask(activeSession?.id) {
            formatToolResponse(runTask(task, context, activeSession, files, images))
        }
    }

    @Tool
    @LLMDescription("Start a PRoot Linux task with temporary local LLM API environment variables. Requires '允许使用本机 LLM API Key' in model safety settings; execution rechecks this permission and clears injected variables afterwards. Returns a task ID; call waitLinuxTask for the result.")
    suspend fun executeProotLinuxTaskWithLlmApi(
        @LLMDescription("Linux workflow needing local LLM API access") task: String,
        @LLMDescription("Constraints or expected output") context: String = "",
        @LLMDescription("Provider IDs or model role names; empty means all configured providers/roles") providers: List<String> = emptyList(),
        @LLMDescription("Session file attachment IDs or URIs") fileAttachmentIds: List<String> = emptyList(),
        @LLMDescription("Session image attachment IDs or URIs") imageUris: List<String> = emptyList(),
    ): String {
        val activeSession = session ?: currentChatSession()
        val selectedProviders = providers.toList()
        val files = fileAttachmentIds.toList()
        val images = imageUris.toList()
        return submitTask(activeSession?.id) {
            formatToolResponse(runTask(task, context, activeSession, files, images, true, selectedProviders))
        }
    }

    @Tool
    @LLMDescription("Wait for a task ID returned by either PRoot Linux start tool and return its text result.")
    suspend fun waitLinuxTask(@LLMDescription("Task ID returned by this manager") taskId: String): String = waitTask(taskId)

    companion object {
        const val TOOLSET_NAME = "proot_linux"
        val default get() = ProotLinuxAgentLifecycleManager.default.manager

        internal fun formatToolResponse(result: ProotLinuxExecutionResult): String = buildString {
            appendLine("success=${result.success}")
            if (result.modelId.isNotBlank()) {
                appendLine("model=${result.provider?.name ?: "custom"}/${result.modelId}")
            }
            if (result.suggestedWorkingDir.isNotBlank()) {
                appendLine("suggestedWorkingDir=${result.suggestedWorkingDir}")
            }
            if (result.injectedLlmEnvVars.isNotEmpty()) {
                appendLine("injectedLlmEnvVars=${result.injectedLlmEnvVars.joinToString(",")}")
            }
            if (result.terminalCommandCount > 0) {
                appendLine("terminalCommands=${result.terminalCommandCount}")
            }
            if (result.executedTools.isNotEmpty()) {
                appendLine("executedTools=${result.executedTools.joinToString(",")}")
            }
            appendLine("summary:")
            append(result.summary.trim())
        }.trimEnd()
    }
}
