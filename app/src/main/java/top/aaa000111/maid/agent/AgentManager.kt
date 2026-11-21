package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.aaa000111.maid.chat.ChatSessionContext
import top.aaa000111.maid.learning.LearningRuntime
import top.aaa000111.maid.learning.TaskExecutionContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 一次工具挂载持有一个 Manager；输入留在执行闭包中，内存表只保存 String 任务结果。 */
abstract class AgentManager(
    private val agentType: String,
    final override val name: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ToolSet {
    private val tasks = ConcurrentHashMap<String, Deferred<String>>()

    protected suspend fun submitTask(sessionId: String? = null, execute: suspend () -> String): String {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val id = UUID.randomUUID().toString()
        // 工具返回 ID 后任务继续运行，不绑定本次工具调用的 Job，也不登记全局作用域。
        val task = CoroutineScope(caller.minusKey(Job) + dispatcher).async(start = CoroutineStart.LAZY) {
            val parent = currentCoroutineContext()[TaskExecutionContext]
            val recorder = parent?.recorder ?: LearningRuntime.recorderOrNull()
            val sourceSessionId = sessionId ?: caller[ChatSessionContext]?.session?.id
                ?: parent?.sessionId ?: id
            if (recorder != null) {
                recorder.run(sourceSessionId, agentType, child = parent != null, block = execute)
            } else execute()
        }
        tasks[id] = task
        task.start()
        return id
    }

    /** 等待逻辑由基类实现；具体 Manager 以不同的 @Tool 方法名转发，避免同时挂载时重名。 */
    suspend fun waitTask(
        @LLMDescription("Task ID returned by this manager's start tool") taskId: String,
    ): String {
        val task = tasks[taskId] ?: return "Unknown task: $taskId"
        return try {
            task.await()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            "Task cancelled: $taskId"
        } catch (error: Exception) {
            "Task failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun asToolRegistry(): ToolRegistry = ToolRegistry { tools(this@AgentManager) }
}
