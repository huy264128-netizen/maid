package top.aaa000111.maid.learning

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import top.aaa000111.maid.skill.SkillDescriptor
import top.aaa000111.maid.skill.SkillExecutionContext
import top.aaa000111.maid.skill.SkillRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class TaskExecutionContext internal constructor(
    val taskId: String,
    val sessionId: String,
    val turnId: String,
    val agentType: String,
    val skillContext: SkillExecutionContext,
    internal val recorder: TaskRecorder,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskExecutionContext>

    suspend fun recordInput(kind: String, messageId: String? = null) {
        recorder.repository.update(taskId) { task ->
            val primary = task.inputs.indexOfFirst { it.turnId == turnId && it.kind == "USER_REQUEST" && it.messageId == null }
            if (kind == "USER_REQUEST" && primary >= 0) {
                task.copy(inputs = task.inputs.mapIndexed { index, input ->
                    if (index == primary) input.copy(messageId = messageId) else input
                })
            } else task.copy(inputs = task.inputs + TaskInputEvidence(
                UUID.randomUUID().toString(), kind, System.currentTimeMillis(), messageId,
            ))
        }
    }

    suspend fun recordOutput(messageId: String) {
        recorder.repository.update(taskId) { it.copy(outputMessageIds = (it.outputMessageIds + messageId).distinct()) }
    }

    suspend fun recordSkill(skill: SkillDescriptor, filePath: String) {
        recorder.repository.update(taskId) { task ->
            if (task.skills.any { it.skillId == skill.id && it.versionHash == skill.versionHash && it.filePath == filePath }) task
            else task.copy(skills = task.skills + SkillUseEvidence(skill.id, skill.versionHash, filePath, System.currentTimeMillis()))
        }
    }

    suspend fun recordModel(modelId: String, providerId: String?) {
        recorder.repository.update(taskId) { it.copy(modelId = modelId, providerId = providerId) }
    }

    suspend fun recordMilestone(kind: String, checkpointIndex: Int? = null) {
        recorder.repository.update(taskId) { it.copy(
            milestones = it.milestones + TaskMilestoneEvidence(kind, checkpointIndex, System.currentTimeMillis()),
        ) }
    }

    suspend fun recordEnvironment(values: JsonObject) {
        recorder.repository.update(taskId) { it.copy(environment = JsonObject(it.environment + values)) }
    }

    suspend fun linkPlan(planId: String) {
        recorder.repository.update(taskId) { it.copy(planId = planId) }
    }
}

/** 可注入的进程内执行记录器；所有落盘 API 自行切换 IO。 */
class TaskRecorder(
    val repository: TaskEvidenceRepository,
    val skillRepository: SkillRepository,
    private val environment: JsonObject = JsonObject(emptyMap()),
) {
    private val activeSessions = ConcurrentHashMap<String, TaskExecutionContext>()

    suspend fun <T> withActiveTask(sessionId: String, block: suspend () -> T): T {
        val active = activeSessions[sessionId] ?: return block()
        return withContext(active) { block() }
    }

    suspend fun <T> run(
        sessionId: String,
        agentType: String = "CHAT",
        child: Boolean = false,
        block: suspend () -> T,
    ): T {
        val parent = currentCoroutineContext()[TaskExecutionContext]
        if (parent != null && !child) return block()
        val evidence = TaskEvidence(
            sessionId = sessionId,
            turnId = parent?.turnId ?: UUID.randomUUID().toString(),
            parentTaskId = parent?.taskId,
            agentType = agentType,
            environment = environment,
        )
        val context = TaskExecutionContext(
            evidence.taskId, sessionId, evidence.turnId, agentType,
            parent?.skillContext ?: skillRepository.snapshot(), this,
        )
        withContext(NonCancellable) {
            repository.create(if (parent == null) evidence.copy(
                inputs = listOf(TaskInputEvidence(evidence.turnId, "USER_REQUEST", evidence.startedAtMillis)),
            ) else evidence)
        }
        if (parent == null) activeSessions[sessionId] = context
        try {
            val result = withContext(context) { block() }
            withContext(NonCancellable) {
                repository.update(context.taskId) {
                    if (it.status == TaskExecutionStatus.RUNNING) it.copy(
                        status = TaskExecutionStatus.COMPLETED, endedAtMillis = System.currentTimeMillis(),
                    ) else it
                }
            }
            return result
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    repository.update(context.taskId) { it.copy(
                        status = if (error is CancellationException) TaskExecutionStatus.CANCELLED else TaskExecutionStatus.FAILED,
                        endedAtMillis = System.currentTimeMillis(),
                        reason = if (error is CancellationException) "cancelled" else error.javaClass.simpleName,
                    ) }
                } catch (recordingError: Throwable) {
                    error.addSuppressed(recordingError)
                }
            }
            throw error
        } finally {
            if (parent == null) activeSessions.remove(sessionId, context)
        }
    }

    suspend fun rejectCurrent(reason: String) {
        val context = currentCoroutineContext()[TaskExecutionContext] ?: return
        repository.update(context.taskId) { it.copy(
            status = TaskExecutionStatus.REJECTED, reason = reason,
            endedAtMillis = System.currentTimeMillis(),
        ) }
    }
}

/** 对任意 Agent 工具执行记录返回/异常，不从自然语言返回值猜测成功。 */
suspend fun <T> recordToolExecution(
    toolName: String,
    agentType: String,
    messageId: String? = null,
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()[TaskExecutionContext] ?: return block()
    val id = UUID.randomUUID().toString()
    context.recorder.repository.update(context.taskId) { it.copy(tools = it.tools + ToolExecutionEvidence(
        callId = id, toolName = toolName, agentType = agentType.ifBlank { context.agentType },
        startedAtMillis = System.currentTimeMillis(), messageId = messageId,
    )) }
    try {
        val result = block()
        withContext(NonCancellable) {
            context.recorder.repository.update(context.taskId) { task -> task.copy(tools = task.tools.map {
                if (it.callId == id) it.copy(status = ToolExecutionStatus.RETURNED, endedAtMillis = System.currentTimeMillis()) else it
            }) }
        }
        return result
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            try {
                context.recorder.repository.update(context.taskId) { task -> task.copy(tools = task.tools.map {
                    if (it.callId == id) it.copy(
                        status = if (error is CancellationException) ToolExecutionStatus.CANCELLED else ToolExecutionStatus.THREW,
                        endedAtMillis = System.currentTimeMillis(), errorType = error.javaClass.simpleName,
                    ) else it
                }) }
            } catch (recordingError: Throwable) {
                error.addSuppressed(recordingError)
            }
        }
        throw error
    }
}
