package top.aaa000111.maid.learning

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.skill.SkillRepository
import java.nio.file.Files

class TaskEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun repository() = TaskEvidenceRepository(temporary.root.toPath().resolve("tasks"))
    private fun recorder(repository: TaskEvidenceRepository) =
        TaskRecorder(repository, SkillRepository(temporary.root.toPath().resolve("skills")))

    @Test fun completedToolReturnDoesNotClaimBusinessSuccessAndHasNoPayload() = runBlocking {
        val repository = repository()
        val recorder = recorder(repository)
        recorder.run("session") {
            assertEquals("operation failed: private-result", recordToolExecution("testTool", "CHAT") {
                "operation failed: private-result"
            })
        }
        val task = repository.list().single()
        assertEquals(TaskExecutionStatus.COMPLETED, task.status)
        assertEquals(VerificationStatus.UNKNOWN, task.verification)
        assertEquals(ToolExecutionStatus.RETURNED, task.tools.single().status)
        assertEquals(VerificationStatus.UNKNOWN, task.tools.single().verification)
        val bytes = Files.readAllBytes(temporary.root.toPath().resolve("tasks/${task.taskId}.json"))
        assertFalse(String(bytes).contains("private-result"))
        assertEquals(task, repository().get(task.taskId))
    }

    @Test fun childDelegationAndCheckpointEventsStayUnderOneRoot() = runBlocking {
        val repository = repository()
        val recorder = recorder(repository)
        class Manager(type: String) : top.aaa000111.maid.agent.AgentManager(type, type.lowercase()) {
            suspend fun start(block: suspend () -> String): String = submitTask(execute = block)
        }
        val plannerManager = Manager("PLANNER")
        val deviceManager = Manager("DEVICE")
        recorder.run("session") {
            val taskId = recordToolExecution("planAndExecuteTask", "CHAT") {
                plannerManager.start {
                    val planner = currentCoroutineContext()[TaskExecutionContext]!!
                    planner.linkPlan("plan-1")
                    planner.recordMilestone("CHECKPOINT_STARTED", 1)
                    planner.recordMilestone("CHECKPOINT_STARTED", 2)
                    val deviceId = recordToolExecution("executeDeviceTask", "PLANNER") {
                        deviceManager.start { "done" }
                    }
                    deviceManager.waitTask(deviceId)
                }
            }
            plannerManager.waitTask(taskId)
        }
        val tasks = repository.list()
        assertEquals(3, tasks.size)
        val root = tasks.single { it.parentTaskId == null }
        val planner = tasks.single { it.agentType == "PLANNER" }
        val device = tasks.single { it.agentType == "DEVICE" }
        assertEquals(root.taskId, planner.parentTaskId)
        assertEquals(planner.taskId, device.parentTaskId)
        assertEquals(root.turnId, device.turnId)
        assertEquals("plan-1", planner.planId)
        assertEquals(listOf(1, 2), planner.milestones.map { it.checkpointIndex })
        assertTrue(tasks.all { it.verification == VerificationStatus.UNKNOWN })
    }

    @Test fun cancellationAndFailureArePersistedWithoutExceptionMessages() = runBlocking {
        val repository = repository()
        val recorder = recorder(repository)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            recorder.run("cancelled") {
                recordToolExecution("wait", "CHAT") {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        job.cancelAndJoin()
        try {
            recorder.run("failed") {
                recordToolExecution("throw", "CHAT") { error("secret-exception-message") }
            }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        val tasks = repository.list()
        assertEquals(TaskExecutionStatus.CANCELLED, tasks.single { it.sessionId == "cancelled" }.status)
        assertEquals(ToolExecutionStatus.CANCELLED, tasks.single { it.sessionId == "cancelled" }.tools.single().status)
        assertEquals(TaskExecutionStatus.FAILED, tasks.single { it.sessionId == "failed" }.status)
        assertFalse(tasks.toString().contains("secret-exception-message"))
    }

    @Test fun processRecoveryMarksOnlyRunningTasksAndToolsInterrupted() = runBlocking {
        val repository = repository()
        val running = TaskEvidence(sessionId = "running", tools = listOf(
            ToolExecutionEvidence("one", "test", "CHAT", 1),
        ))
        val finished = TaskEvidence(sessionId = "finished", status = TaskExecutionStatus.COMPLETED)
        repository.create(running)
        repository.create(finished)
        repository().recoverInterrupted()
        assertEquals(TaskExecutionStatus.INTERRUPTED, repository.get(running.taskId)!!.status)
        assertEquals(ToolExecutionStatus.INTERRUPTED, repository.get(running.taskId)!!.tools.single().status)
        assertEquals(TaskExecutionStatus.COMPLETED, repository.get(finished.taskId)!!.status)
        repository().recoverInterrupted()
        assertEquals(2, repository.list().size)
    }

    @Test fun updatesFromConcurrentToolsDoNotLoseEvents() = runBlocking {
        val repository = repository()
        recorder(repository).run("concurrent") {
            coroutineScope {
                repeat(12) { i -> launch(Dispatchers.Default) {
                    recordToolExecution("tool$i", "CHAT") { yield() }
                } }
            }
        }
        val calls = repository.list().single().tools
        assertEquals(12, calls.size)
        assertEquals(12, calls.map { it.callId }.distinct().size)
        assertTrue(calls.all { it.status == ToolExecutionStatus.RETURNED })
    }
}
