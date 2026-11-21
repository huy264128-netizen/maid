package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.UserInput

/** UI 对话直接在调用协程中执行；作为工具使用时才保存异步 String 结果。 */
class ChatAgentManager(
    private val agent: ChatAgent = ChatAgent(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AgentManager("CHAT", "chat_agent", dispatcher) {
    suspend fun chatWithDetails(input: UserInput, session: ChatSession = agent.defaultSession): ChatAgentReply =
        withContext(dispatcher) { agent.chatWithDetails(input, session) }

    suspend fun chat(text: String, session: ChatSession = agent.defaultSession): String =
        chatWithDetails(UserInput(text = text), session).content

    @Tool
    @LLMDescription("Start a chat task and return its task ID immediately. Call waitChatTask to get the text response.")
    suspend fun executeChatTask(@LLMDescription("Message to send") text: String): String =
        submitTask(agent.defaultSession.id) { chat(text) }

    @Tool
    @LLMDescription("Wait for a task ID returned by executeChatTask and return its response.")
    suspend fun waitChatTask(@LLMDescription("Task ID returned by this manager") taskId: String): String = waitTask(taskId)

    companion object {
        val default get() = top.aaa000111.maid.chat.ChatBackgroundRuntime.current.agentManager
    }
}
