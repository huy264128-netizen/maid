package top.aaa000111.maid.learning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** 独立于聊天快照的事实仓库。不保存提示词、工具参数、工具返回正文或异常消息。 */
class TaskEvidenceRepository(private val root: Path) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(evidence: TaskEvidence) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = pathFor(evidence.taskId)
            check(!Files.exists(path)) { "Task already exists" }
            save(evidence)
        }
    }

    suspend fun get(taskId: String): TaskEvidence? = withContext(Dispatchers.IO) {
        mutex.withLock { read(pathFor(taskId)) }
    }

    suspend fun list(): List<TaskEvidence> = withContext(Dispatchers.IO) {
        mutex.withLock { readAll().sortedBy { it.startedAtMillis } }
    }

    suspend fun update(taskId: String, transform: (TaskEvidence) -> TaskEvidence) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = read(pathFor(taskId)) ?: error("Unknown task")
            val updated = transform(previous)
            require(updated.taskId == previous.taskId && updated.sessionId == previous.sessionId)
            save(updated)
        }
    }

    /** 仅在进程启动、尚未接受新任务时调用。历史 RUNNING 不能被恢复成成功。 */
    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            readAll().filter { it.status == TaskExecutionStatus.RUNNING }.forEach { task ->
                save(task.copy(
                    status = TaskExecutionStatus.INTERRUPTED,
                    endedAtMillis = now,
                    reason = "process_interrupted",
                    tools = task.tools.map {
                        if (it.status == ToolExecutionStatus.RUNNING) {
                            it.copy(status = ToolExecutionStatus.INTERRUPTED, endedAtMillis = now)
                        } else it
                    },
                ))
            }
        }
    }

    private fun pathFor(id: String): Path {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid task id" }
        return root.resolve("$id.json")
    }

    private fun save(task: TaskEvidence) = atomicWrite(pathFor(task.taskId), encode(task).toString())

    private fun read(path: Path): TaskEvidence? {
        if (!Files.isRegularFile(path)) return null
        // 损坏文件必须显式报错，不能默默将证据丢失解释为没有任务。
        return decode(json.parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)).jsonObject)
    }

    private fun readAll(): List<TaskEvidence> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { paths ->
            paths.iterator().asSequence().filter { it.fileName.toString().endsWith(".json") }.map { read(it)!! }.toList()
        }
    }

    private fun encode(t: TaskEvidence) = buildJsonObject {
        put("schemaVersion", 1)
        put("taskId", t.taskId); put("sessionId", t.sessionId); put("turnId", t.turnId)
        put("parentTaskId", t.parentTaskId); put("agentType", t.agentType); put("planId", t.planId)
        put("startedAtMillis", t.startedAtMillis); put("endedAtMillis", t.endedAtMillis)
        put("status", t.status.name); put("verification", t.verification.name); put("reason", t.reason)
        put("modelId", t.modelId); put("providerId", t.providerId); put("environment", t.environment)
        putJsonArray("inputs") { t.inputs.forEach { e -> add(buildJsonObject {
            put("turnId", e.turnId); put("kind", e.kind); put("atMillis", e.atMillis); put("messageId", e.messageId)
        }) } }
        putJsonArray("milestones") { t.milestones.forEach { e -> add(buildJsonObject {
            put("kind", e.kind); put("checkpointIndex", e.checkpointIndex); put("atMillis", e.atMillis)
        }) } }
        putJsonArray("tools") { t.tools.forEach { e -> add(buildJsonObject {
            put("callId", e.callId); put("toolName", e.toolName); put("agentType", e.agentType)
            put("startedAtMillis", e.startedAtMillis); put("endedAtMillis", e.endedAtMillis)
            put("status", e.status.name); put("verification", e.verification.name); put("errorType", e.errorType)
            put("messageId", e.messageId)
        }) } }
        put("outputMessageIds", JsonArray(t.outputMessageIds.map { JsonPrimitive(it) }))
        putJsonArray("skills") { t.skills.forEach { e -> add(buildJsonObject {
            put("skillId", e.skillId); put("versionHash", e.versionHash)
            put("filePath", e.filePath); put("atMillis", e.atMillis)
        }) } }
    }

    private fun decode(o: JsonObject): TaskEvidence {
        fun JsonObject.s(key: String) = get(key)?.jsonPrimitive?.contentOrNull
        fun JsonObject.l(key: String) = get(key)?.jsonPrimitive?.longOrNull
        fun objects(key: String) = o[key]?.jsonArray.orEmpty().map { it.jsonObject }
        return TaskEvidence(
            taskId = o.s("taskId")!!, sessionId = o.s("sessionId")!!, turnId = o.s("turnId")!!,
            parentTaskId = o.s("parentTaskId"), agentType = o.s("agentType") ?: "CHAT", planId = o.s("planId"),
            startedAtMillis = o.l("startedAtMillis")!!, endedAtMillis = o.l("endedAtMillis"),
            status = TaskExecutionStatus.valueOf(o.s("status")!!),
            verification = VerificationStatus.valueOf(o.s("verification") ?: "UNKNOWN"),
            reason = o.s("reason"), modelId = o.s("modelId"), providerId = o.s("providerId"),
            environment = o["environment"]?.jsonObject ?: JsonObject(emptyMap()),
            inputs = objects("inputs").map { TaskInputEvidence(it.s("turnId")!!, it.s("kind")!!, it.l("atMillis")!!, it.s("messageId")) },
            milestones = objects("milestones").map { TaskMilestoneEvidence(
                it.s("kind")!!, it["checkpointIndex"]?.jsonPrimitive?.intOrNull, it.l("atMillis")!!,
            ) },
            tools = objects("tools").map { ToolExecutionEvidence(
                callId = it.s("callId")!!, toolName = it.s("toolName")!!,
                agentType = it.s("agentType") ?: "", startedAtMillis = it.l("startedAtMillis")!!,
                endedAtMillis = it.l("endedAtMillis"), status = ToolExecutionStatus.valueOf(it.s("status")!!),
                verification = VerificationStatus.valueOf(it.s("verification") ?: "UNKNOWN"), errorType = it.s("errorType"), messageId = it.s("messageId"),
            ) },
            outputMessageIds = o["outputMessageIds"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
            skills = objects("skills").map { SkillUseEvidence(
                it.s("skillId")!!, it.s("versionHash")!!, it.s("filePath")!!, it.l("atMillis")!!,
            ) },
        )
    }
}
