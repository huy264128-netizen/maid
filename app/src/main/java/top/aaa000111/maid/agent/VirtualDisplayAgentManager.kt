package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import android.content.Context
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import top.aaa000111.maid.display.VirtualDisplayManager

/** 一次规划独占其显示器。显示器 ID 同时用于提交和等待。 */
class VirtualDisplayAgentManager internal constructor(
    context: Context,
    private val runTask: suspend (VirtualDisplayManager, String) -> String,
) : AgentManager("VIRTUAL_DISPLAY", "virtual_display_agent") {
    constructor(context: Context) : this(context, { display, task -> VirtualDisplayAgent(display).runTask(task) })

    private class Entry(val display: VirtualDisplayManager) {
        var task: Deferred<String>? = null
    }

    private val appContext = context.applicationContext
    private val displays = mutableMapOf<Int, Entry>()
    private var closed = false

    @Tool
    @LLMDescription("Create a trusted Android display with its own content. Requires Shizuku UserService permission to create trusted displays. Returns displayId.")
    suspend fun createVirtualDisplay(
        width: Int = 540,
        height: Int = 960,
        densityDpi: Int = 160,
    ): String = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Virtual display manager is closed" }
        require(width in 1..1920 && height in 1..1920 && densityDpi in 1..640)
        val display = VirtualDisplayManager(appContext)
        try {
            val id = display.createForAgent("Maid virtual display", width, height, densityDpi)
            check(!closed) { "Virtual display manager is closed" }
            displays[id] = Entry(display)
            "displayId=$id"
        } catch (error: Throwable) {
            runCatching { display.closeAgentDisplay() }
            throw error
        }
    }

    @Tool
    @LLMDescription("Start a task asynchronously on an existing displayId. Returns an acknowledgement. Call waitVirtualDisplayTask with that same displayId for the result. Each display accepts one task at a time.")
    suspend fun executeVirtualDisplayTask(displayId: Int, task: String): String = withContext(Dispatchers.Main.immediate) {
        require(task.isNotBlank()) { "Task must not be blank" }
        val entry = displays[displayId] ?: return@withContext "Unknown virtual display: $displayId"
        if (entry.task != null) return@withContext "Call waitVirtualDisplayTask($displayId) before submitting another task."
        if (entry.display.state.value != VirtualDisplayManager.State.Active(displayId)) {
            return@withContext "Virtual display $displayId has stopped."
        }
        val pending = startTask("virtual-display-$displayId") {
            entry.display.requireAgentPermission()
            runTask(entry.display, task.trim())
        }
        entry.task = pending
        pending.start()
        "Started on displayId=$displayId. Call waitVirtualDisplayTask with this displayId."
    }

    @Tool
    @LLMDescription("Wait for and consume the pending task result for this displayId before submitting another task.")
    suspend fun waitVirtualDisplayTask(displayId: Int): String = withContext(Dispatchers.Main.immediate) {
        val entry = displays[displayId] ?: return@withContext "Unknown virtual display: $displayId"
        val task = entry.task ?: return@withContext "No pending task on virtual display $displayId."
        val result = awaitTask(displayId.toString(), task)
        if (entry.task === task) entry.task = null
        result
    }

    @Tool
    @LLMDescription("Release a virtual display after its pending task has been waited on.")
    suspend fun releaseVirtualDisplay(displayId: Int): String = withContext(Dispatchers.Main.immediate) {
        val entry = displays[displayId] ?: return@withContext "Unknown virtual display: $displayId"
        if (entry.task != null) return@withContext "Call waitVirtualDisplayTask($displayId) before releasing it."
        displays.remove(displayId)
        entry.display.closeAgentDisplay()
        "Released displayId=$displayId."
    }

    internal suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        closed = true
        val entries = displays.values.toList()
        displays.clear()
        val tasks = entries.mapNotNull { it.task }
        tasks.forEach { it.cancel() }
        try {
            tasks.joinAll()
        } finally {
            entries.forEach { runCatching { it.display.closeAgentDisplay() } }
        }
    }
}
