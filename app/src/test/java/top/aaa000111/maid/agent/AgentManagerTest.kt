package top.aaa000111.maid.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.ChatSessionContext
import top.aaa000111.maid.learning.TaskEvidenceRepository
import top.aaa000111.maid.learning.TaskExecutionContext
import top.aaa000111.maid.learning.TaskExecutionStatus
import top.aaa000111.maid.learning.TaskRecorder
import top.aaa000111.maid.skill.SkillRepository

class AgentManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Manager : AgentManager("RESEARCH", "research_test") {
        suspend fun start(block: suspend () -> String): String = submitTask(execute = block)
    }

    @Test fun evidenceTracksActualAsyncExecutionAfterSubmissionReturns() = runBlocking {
        val repository = TaskEvidenceRepository(temporary.root.toPath().resolve("evidence"))
        val recorder = TaskRecorder(repository, SkillRepository(temporary.root.toPath().resolve("skills")))
        val session = ChatSession(title = "source")
        val manager = Manager()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var taskId: String
        try {
            withContext(ChatSessionContext(session)) {
                recorder.run(session.id) {
                    val parent = currentCoroutineContext()[TaskExecutionContext]!!
                    taskId = manager.start {
                        val child = currentCoroutineContext()[TaskExecutionContext]!!
                        assertNotEquals(parent.taskId, child.taskId)
                        assertSame(parent.skillContext, child.skillContext)
                        assertEquals(session, currentCoroutineContext()[ChatSessionContext]?.session)
                        started.complete(Unit)
                        release.await()
                        "research result"
                    }
                    withTimeout(5_000) { started.await() }
                    assertEquals(TaskExecutionStatus.RUNNING, repository.list().single { it.agentType == "RESEARCH" }.status)
                }
            }
            assertEquals(TaskExecutionStatus.COMPLETED, repository.list().single { it.agentType == "CHAT" }.status)
            assertEquals(TaskExecutionStatus.RUNNING, repository.list().single { it.agentType == "RESEARCH" }.status)
            release.complete(Unit)
            assertEquals("research result", withTimeout(5_000) { manager.waitTask(taskId) })
            val records = repository.list()
            assertEquals(2, records.size)
            val parent = records.single { it.agentType == "CHAT" }
            val child = records.single { it.agentType == "RESEARCH" }
            assertEquals(parent.taskId, child.parentTaskId)
            assertEquals(session.id, child.sessionId)
            assertEquals(TaskExecutionStatus.COMPLETED, child.status)
        } finally { release.complete(Unit) }
    }
}
