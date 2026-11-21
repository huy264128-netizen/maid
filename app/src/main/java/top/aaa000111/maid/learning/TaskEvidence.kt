package top.aaa000111.maid.learning

import kotlinx.serialization.json.JsonObject
import java.util.UUID

enum class TaskExecutionStatus { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED, REJECTED }
enum class VerificationStatus { UNKNOWN, PASSED, FAILED, USER_CONFIRMED }
enum class ToolExecutionStatus { RUNNING, RETURNED, THREW, CANCELLED, INTERRUPTED }

/** 结构化事实；RETURNED 仅表示工具返回，不推断其业务结果。 */
data class ToolExecutionEvidence(
    val callId: String,
    val toolName: String,
    val agentType: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val status: ToolExecutionStatus = ToolExecutionStatus.RUNNING,
    val verification: VerificationStatus = VerificationStatus.UNKNOWN,
    val errorType: String? = null,
    val messageId: String? = null,
)

data class TaskInputEvidence(
    val turnId: String,
    val kind: String,
    val atMillis: Long,
    val messageId: String? = null,
)

data class TaskMilestoneEvidence(
    val kind: String,
    val checkpointIndex: Int?,
    val atMillis: Long,
)

data class SkillUseEvidence(
    val skillId: String,
    val versionHash: String,
    val filePath: String,
    val atMillis: Long,
)

data class TaskEvidence(
    val taskId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val turnId: String = UUID.randomUUID().toString(),
    val parentTaskId: String? = null,
    val agentType: String = "CHAT",
    val planId: String? = null,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val endedAtMillis: Long? = null,
    val status: TaskExecutionStatus = TaskExecutionStatus.RUNNING,
    val verification: VerificationStatus = VerificationStatus.UNKNOWN,
    val reason: String? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val environment: JsonObject = JsonObject(emptyMap()),
    val inputs: List<TaskInputEvidence> = emptyList(),
    val milestones: List<TaskMilestoneEvidence> = emptyList(),
    val tools: List<ToolExecutionEvidence> = emptyList(),
    val skills: List<SkillUseEvidence> = emptyList(),
    val outputMessageIds: List<String> = emptyList(),
)
