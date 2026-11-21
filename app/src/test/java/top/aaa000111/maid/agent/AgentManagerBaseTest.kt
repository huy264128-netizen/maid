package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.tools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AgentManagerBaseTest {
    class ProbeManager : AgentManager("PROBE", "probe") {
        @Tool
        suspend fun startTask(): String = submitTask { "tool result" }
        @Tool
        suspend fun waitProbeTask(taskId: String): String = waitTask(taskId)
        suspend fun start(block: suspend () -> String): String = submitTask(execute = block)
    }

    @Test fun submissionReturnsIdBeforeResultAndTasksRunConcurrently() = runBlocking {
        val manager = ProbeManager()
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val a = manager.start { aStarted.complete(Unit); release.await(); "A" }
            val b = manager.start { bStarted.complete(Unit); release.await(); "B" }
            assertNotEquals(a, b)
            withTimeout(5_000) { aStarted.await(); bStarted.await() }
            assertFalse(release.isCompleted)
            release.complete(Unit)
            assertEquals("A", withTimeout(5_000) { manager.waitTask(a) })
            assertEquals("B", manager.waitTask(b))
            assertEquals("A", manager.waitTask(a))
        } finally { release.complete(Unit) }
    }

    @Test fun callerCompletionAndWaiterCancellationDoNotCancelTask() = runBlocking {
        val manager = ProbeManager()
        val id = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        try {
            val caller = launch { id.complete(manager.start { release.await(); "completed" }) }
            caller.join()
            val taskId = id.await()
            val waiter = launch { manager.waitTask(taskId) }
            waiter.cancelAndJoin()
            release.complete(Unit)
            assertEquals("completed", withTimeout(5_000) { manager.waitTask(taskId) })
        } finally { release.complete(Unit) }
    }

    @Test fun failureIsReturnedAsTextWithoutAffectingOtherTasks() = runBlocking {
        val manager = ProbeManager()
        val failed = manager.start { error("expected failure") }
        val success = manager.start { "ok" }
        assertTrue(withTimeout(5_000) { manager.waitTask(failed) }.contains("expected failure"))
        assertEquals("ok", manager.waitTask(success))
        assertTrue(manager.waitTask("missing").startsWith("Unknown task:"))
        assertTrue(ProbeManager().waitTask(success).startsWith("Unknown task:"))
    }

    @Test fun completedResultsRemainAvailableWithoutHistoryEviction() = runBlocking {
        val manager = ProbeManager()
        val first = manager.start { "first" }
        repeat(130) {
            val id = manager.start { "result $it" }
            assertEquals("result $it", manager.waitTask(id))
        }
        assertEquals("first", manager.waitTask(first))
    }

    @Test fun inheritedWaitToolAndSpecificStartToolAreRegistered() = runBlocking {
        val manager = ProbeManager()
        assertEquals(setOf("startTask", "waitProbeTask"), manager.asToolRegistry().tools.map { it.name }.toSet())
        assertEquals("tool result", manager.waitTask(manager.startTask()))
    }

    @Test fun concreteManagersRegisterDistinctWaitToolsWithoutMountingThemselvesInTheirAgents() {
        val managers = listOf(
            PlannerAgentManager(), ResearchAgentManager(), DeviceActionAgentManager(),
            ProotLinuxAgentManager(), ChatAgentManager(),
        )
        val registry = ToolRegistry { managers.forEach { tools(it) } }
        val names = registry.tools.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(listOf(
            "planAndExecuteTask", "executeResearchTask", "executeDeviceTask",
            "executeProotLinuxTask", "executeProotLinuxTaskWithLlmApi", "executeChatTask",
            "waitPlannerTask", "waitResearchTask", "waitDeviceTask", "waitLinuxTask", "waitChatTask",
        )))
    }
}
