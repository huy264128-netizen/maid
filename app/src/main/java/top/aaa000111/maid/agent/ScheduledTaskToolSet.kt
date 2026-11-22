package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aaa000111.maid.runtime.DefaultScheduledWorkGateway
import top.aaa000111.maid.runtime.MaidLangCompileResult
import top.aaa000111.maid.runtime.MaidLangNativeRegistry
import top.aaa000111.maid.runtime.MaidLangTaskStore
import top.aaa000111.maid.runtime.ScheduledAlarmGateway
import top.aaa000111.maid.runtime.ScheduledTaskAction
import top.aaa000111.maid.runtime.ScheduledTaskPayload
import top.aaa000111.maid.runtime.ScheduledWorkGateway
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 面向 Agent 的定时任务与 MaidLang 编译任务工具集：
 * - [makeTask]：编译 MaidLang 脚本源码并持久化字节码，成功返回 `taskId`，失败返回详细报错；
 * - AlarmManager 工具：按墙钟时间安排单次触发（启动带提示词的会话或按 `taskId` 执行已编译脚本）及取消；
 * - WorkManager 工具：安排持久化一次性或周期性任务（启动带提示词的会话或按 `taskId` 执行已编译脚本）、取消及状态查询。
 */
class ScheduledTaskToolSet(
    private val taskStore: MaidLangTaskStore,
    private val alarmGateway: ScheduledAlarmGateway,
    private val workGateway: ScheduledWorkGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ToolSet {

    @Tool
    @LLMDescription(
        "Compile MaidLang source code into reusable bytecode and store it locally. " +
            "Returns the generated taskId when compilation succeeds, or a detailed compilation error when it fails. " +
            "Pass the returned taskId to scheduleAlarmScript or scheduleWorkScript to run the script on a schedule.",
    )
    suspend fun makeTask(
        @LLMDescription("MaidLang source code to compile.")
        code: String,
        @LLMDescription("Optional human-readable task name.")
        name: String = "",
    ): String {
        val result = withContext(Dispatchers.IO) {
            taskStore.makeTask(source = code, name = name)
        }
        return when (result) {
            is MaidLangCompileResult.Success -> buildString {
                appendLine("Compiled MaidLang task successfully.")
                appendLine("taskId: ${result.meta.taskId}")
                if (result.meta.name.isNotBlank()) {
                    appendLine("name: ${result.meta.name}")
                }
                appendLine("bytecodeSizeBytes: ${result.meta.bytecodeSizeBytes}")
                if (result.meta.referencedNatives.isNotEmpty()) {
                    appendLine("referencedNatives: ${result.meta.referencedNatives.joinToString(", ")}")
                }
                if (result.meta.requiredToolsets.isNotEmpty()) {
                    appendLine("requiredToolsets: ${result.meta.requiredToolsets.joinToString(", ")}")
                }
                if (result.missingToolsets.isNotEmpty()) {
                    val missingSummary = result.missingToolsets.joinToString("; ") { missing ->
                        val privs = missing.requiredPrivileges.joinToString("/") { it.name }
                        "${missing.toolsetId}(${missing.displayTitle}, requires: $privs)"
                    }
                    appendLine("permissionWarning: Missing currently granted toolsets: $missingSummary")
                }
            }.trimEnd()

            is MaidLangCompileResult.Failure -> {
                "Compilation failed (${result.stage}): ${result.errorMessage}"
            }
        }
    }

    @Tool
    @LLMDescription(
        "List available built-in MaidLang native function declarations (`external fun ...`) " +
            "for writing MaidLang scripts. Optionally filter by Android toolsetId (e.g. 'input', 'screen', 'shell', 'app_control', 'settings', 'files').",
    )
    fun listMaidLangNatives(
        @LLMDescription("Optional toolsetId filter; leave empty to list all registered natives.")
        toolsetId: String = "",
    ): String {
        val normalizedFilter = toolsetId.trim()
        val entries = if (normalizedFilter.isEmpty()) {
            MaidLangNativeRegistry.declarations().mapNotNull { MaidLangNativeRegistry[it.name] }
        } else {
            MaidLangNativeRegistry.entriesForToolset(normalizedFilter)
        }
        if (entries.isEmpty()) {
            val availableToolsets = MaidLangNativeRegistry.registeredToolsetIds().joinToString(", ")
            return if (normalizedFilter.isEmpty()) {
                "No MaidLang native functions are currently registered."
            } else {
                "No MaidLang native functions found for toolset '$normalizedFilter'. Available toolsets: $availableToolsets"
            }
        }
        return entries.joinToString("\n") { entry ->
            val toolsetLabel = entry.toolsetId ?: "custom"
            val privLabel = if (entry.requiredPrivileges.isEmpty()) {
                "no special privilege"
            } else {
                entry.requiredPrivileges.joinToString("/") { it.name }
            }
            "[$toolsetLabel | $privLabel] ${entry.declaration.toExternalFunDeclaration()}"
        }
    }

    @Tool
    @LLMDescription("List all compiled MaidLang tasks stored locally with their taskId, name, required toolsets, and size.")
    suspend fun listCompiledTasks(): String {
        val tasks = withContext(Dispatchers.IO) { taskStore.listTasks() }
        if (tasks.isEmpty()) {
            return "No compiled MaidLang tasks are stored."
        }
        return tasks.joinToString("\n") { meta ->
            val label = if (meta.name.isBlank()) meta.taskId else "${meta.taskId} (${meta.name})"
            val toolsets = if (meta.requiredToolsets.isEmpty()) "none" else meta.requiredToolsets.joinToString(", ")
            "$label | ${meta.bytecodeSizeBytes} bytes | toolsets=$toolsets"
        }
    }

    @Tool
    @LLMDescription("Delete a locally stored compiled MaidLang task by its taskId.")
    suspend fun deleteCompiledTask(
        @LLMDescription("Compiled MaidLang taskId returned by makeTask.")
        taskId: String,
    ): String {
        val normalizedId = taskId.trim()
        if (normalizedId.isEmpty()) {
            return "Error: taskId must not be blank."
        }
        val deleted = withContext(Dispatchers.IO) { taskStore.deleteTask(normalizedId) }
        return if (deleted) {
            "Deleted compiled MaidLang task '$normalizedId'."
        } else {
            "Compiled MaidLang task '$normalizedId' was not found."
        }
    }

    @Tool
    @LLMDescription(
        "Schedule a one-shot AlarmManager trigger that starts a new Maid chat session with the given prompt. " +
            "Provide either delaySeconds (> 0) or triggerAtIso (ISO-8601 future timestamp, e.g. '2026-11-23T08:00:00+08:00' or '2026-11-23T08:00:00'). " +
            "Same alarmId replaces an existing pending alarm. Note: AlarmManager alarms do not survive device reboot.",
    )
    fun scheduleAlarmSession(
        @LLMDescription("Stable unique alarm identifier used for replacement or cancellation.")
        alarmId: String,
        @LLMDescription("Prompt text to run in the newly started chat session when the alarm fires.")
        prompt: String,
        @LLMDescription("Delay in seconds from now (must be > 0 when triggerAtIso is empty).")
        delaySeconds: Long = 0L,
        @LLMDescription("Future ISO-8601 timestamp (e.g. '2026-11-23T08:00:00+08:00' or local '2026-11-23T08:00:00'); leave empty when using delaySeconds.")
        triggerAtIso: String = "",
        @LLMDescription("Optional title for the scheduled chat session.")
        sessionTitle: String = "",
        @LLMDescription("Set true to request delivery during Doze/idle mode under Android quotas.")
        allowWhileIdle: Boolean = false,
    ): String {
        val normalizedId = alarmId.trim()
        if (normalizedId.isEmpty()) {
            return "Error: alarmId must not be blank."
        }
        ScheduledTaskPayload.validatePrompt(prompt)?.let { error ->
            return "Error: $error"
        }
        val triggerAt = resolveTriggerInstant(delaySeconds, triggerAtIso)
            .getOrElse { error -> return "Error: ${error.message}" }

        val payload = ScheduledTaskPayload(
            scheduleId = normalizedId,
            action = ScheduledTaskAction.StartPromptSession(
                prompt = prompt,
                sessionTitle = sessionTitle.trim(),
            ),
        )
        return runCatching {
            alarmGateway.schedule(
                alarmId = normalizedId,
                triggerAt = triggerAt,
                payload = payload,
                allowWhileIdle = allowWhileIdle,
            )
            "Scheduled AlarmManager prompt session '$normalizedId' for $triggerAt (allowWhileIdle=$allowWhileIdle)."
        }.getOrElse { error ->
            "Error: Failed to schedule alarm '$normalizedId': ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription(
        "Schedule a one-shot AlarmManager trigger that directly executes a compiled MaidLang script by taskId. " +
            "Call makeTask first to compile the script and obtain taskId. " +
            "Provide either delaySeconds (> 0) or triggerAtIso (ISO-8601 future timestamp).",
    )
    suspend fun scheduleAlarmScript(
        @LLMDescription("Stable unique alarm identifier used for replacement or cancellation.")
        alarmId: String,
        @LLMDescription("Compiled MaidLang taskId returned by makeTask.")
        taskId: String,
        @LLMDescription("Delay in seconds from now (must be > 0 when triggerAtIso is empty).")
        delaySeconds: Long = 0L,
        @LLMDescription("Future ISO-8601 timestamp; leave empty when using delaySeconds.")
        triggerAtIso: String = "",
        @LLMDescription("Set true to request delivery during Doze/idle mode under Android quotas.")
        allowWhileIdle: Boolean = false,
    ): String {
        val normalizedId = alarmId.trim()
        if (normalizedId.isEmpty()) {
            return "Error: alarmId must not be blank."
        }
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            return "Error: taskId must not be blank."
        }
        val exists = withContext(Dispatchers.IO) { taskStore.hasTask(normalizedTaskId) }
        if (!exists) {
            return "Error: Compiled MaidLang task '$normalizedTaskId' not found. Call makeTask first to compile your script and get a valid taskId."
        }
        val triggerAt = resolveTriggerInstant(delaySeconds, triggerAtIso)
            .getOrElse { error -> return "Error: ${error.message}" }

        val payload = ScheduledTaskPayload(
            scheduleId = normalizedId,
            action = ScheduledTaskAction.RunCompiledScript(taskId = normalizedTaskId),
        )
        return runCatching {
            alarmGateway.schedule(
                alarmId = normalizedId,
                triggerAt = triggerAt,
                payload = payload,
                allowWhileIdle = allowWhileIdle,
            )
            "Scheduled AlarmManager compiled script '$normalizedId' (taskId=$normalizedTaskId) for $triggerAt (allowWhileIdle=$allowWhileIdle)."
        }.getOrElse { error ->
            "Error: Failed to schedule alarm '$normalizedId': ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("Cancel a pending AlarmManager task by its alarmId.")
    fun cancelAlarmTask(
        @LLMDescription("Alarm identifier previously passed to scheduleAlarmSession or scheduleAlarmScript.")
        alarmId: String,
    ): String {
        val normalizedId = alarmId.trim()
        if (normalizedId.isEmpty()) {
            return "Error: alarmId must not be blank."
        }
        return runCatching {
            val canceled = alarmGateway.cancel(normalizedId)
            if (canceled) {
                "Canceled AlarmManager task '$normalizedId'."
            } else {
                "No pending AlarmManager task found for '$normalizedId'."
            }
        }.getOrElse { error ->
            "Error: Failed to cancel alarm '$normalizedId': ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription(
        "Schedule a persistent WorkManager task (one-time or periodic) that starts a new Maid chat session with the given prompt. " +
            "Set repeatIntervalMinutes=0 for a one-time task, or >= 15 for a periodic task (WorkManager minimum interval is 15 minutes). " +
            "WorkManager persists across app restarts and device reboots.",
    )
    fun scheduleWorkSession(
        @LLMDescription("Stable unique WorkManager name used for status inspection, update, or cancellation.")
        workName: String,
        @LLMDescription("Prompt text to run in the newly started chat session when the work executes.")
        prompt: String,
        @LLMDescription("Optional initial delay in seconds (>= 0).")
        delaySeconds: Long = 0L,
        @LLMDescription("Periodic repeat interval in minutes; 0 for one-time work, or >= 15 for periodic work.")
        repeatIntervalMinutes: Long = 0L,
        @LLMDescription("Optional title for the scheduled chat session.")
        sessionTitle: String = "",
        @LLMDescription("Whether network connectivity is required before running (default true for LLM chat sessions).")
        requireNetwork: Boolean = true,
        @LLMDescription("Set true to replace/update existing work with the same workName; false keeps existing work.")
        replaceExisting: Boolean = false,
    ): String {
        val normalizedName = workName.trim()
        if (normalizedName.isEmpty()) {
            return "Error: workName must not be blank."
        }
        ScheduledTaskPayload.validatePrompt(prompt)?.let { error ->
            return "Error: $error"
        }
        validateWorkTiming(delaySeconds, repeatIntervalMinutes)?.let { error ->
            return "Error: $error"
        }

        val payload = ScheduledTaskPayload(
            scheduleId = normalizedName,
            action = ScheduledTaskAction.StartPromptSession(
                prompt = prompt,
                sessionTitle = sessionTitle.trim(),
            ),
        )
        return runCatching {
            if (repeatIntervalMinutes == 0L) {
                workGateway.scheduleOneTime(
                    workName = normalizedName,
                    payload = payload,
                    delaySeconds = delaySeconds,
                    requireNetwork = requireNetwork,
                    replaceExisting = replaceExisting,
                )
                "Scheduled one-time WorkManager prompt session '$normalizedName' (delaySeconds=$delaySeconds, requireNetwork=$requireNetwork, replaceExisting=$replaceExisting)."
            } else {
                workGateway.schedulePeriodic(
                    workName = normalizedName,
                    payload = payload,
                    repeatIntervalMinutes = repeatIntervalMinutes,
                    initialDelaySeconds = delaySeconds,
                    requireNetwork = requireNetwork,
                    replaceExisting = replaceExisting,
                )
                "Scheduled periodic WorkManager prompt session '$normalizedName' every $repeatIntervalMinutes minutes (initialDelaySeconds=$delaySeconds, requireNetwork=$requireNetwork, replaceExisting=$replaceExisting)."
            }
        }.getOrElse { error ->
            "Error: Failed to schedule WorkManager task '$normalizedName': ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription(
        "Schedule a persistent WorkManager task (one-time or periodic) that directly executes a compiled MaidLang script by taskId. " +
            "Call makeTask first to compile the script and obtain taskId. " +
            "Set repeatIntervalMinutes=0 for a one-time task, or >= 15 for a periodic task.",
    )
    suspend fun scheduleWorkScript(
        @LLMDescription("Stable unique WorkManager name used for status inspection, update, or cancellation.")
        workName: String,
        @LLMDescription("Compiled MaidLang taskId returned by makeTask.")
        taskId: String,
        @LLMDescription("Optional initial delay in seconds (>= 0).")
        delaySeconds: Long = 0L,
        @LLMDescription("Periodic repeat interval in minutes; 0 for one-time work, or >= 15 for periodic work.")
        repeatIntervalMinutes: Long = 0L,
        @LLMDescription("Whether network connectivity is required before running (default false for local MaidLang scripts).")
        requireNetwork: Boolean = false,
        @LLMDescription("Set true to replace/update existing work with the same workName; false keeps existing work.")
        replaceExisting: Boolean = false,
    ): String {
        val normalizedName = workName.trim()
        if (normalizedName.isEmpty()) {
            return "Error: workName must not be blank."
        }
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            return "Error: taskId must not be blank."
        }
        val exists = withContext(Dispatchers.IO) { taskStore.hasTask(normalizedTaskId) }
        if (!exists) {
            return "Error: Compiled MaidLang task '$normalizedTaskId' not found. Call makeTask first to compile your script and get a valid taskId."
        }
        validateWorkTiming(delaySeconds, repeatIntervalMinutes)?.let { error ->
            return "Error: $error"
        }

        val payload = ScheduledTaskPayload(
            scheduleId = normalizedName,
            action = ScheduledTaskAction.RunCompiledScript(taskId = normalizedTaskId),
        )
        return runCatching {
            if (repeatIntervalMinutes == 0L) {
                workGateway.scheduleOneTime(
                    workName = normalizedName,
                    payload = payload,
                    delaySeconds = delaySeconds,
                    requireNetwork = requireNetwork,
                    replaceExisting = replaceExisting,
                )
                "Scheduled one-time WorkManager compiled script '$normalizedName' (taskId=$normalizedTaskId, delaySeconds=$delaySeconds, requireNetwork=$requireNetwork, replaceExisting=$replaceExisting)."
            } else {
                workGateway.schedulePeriodic(
                    workName = normalizedName,
                    payload = payload,
                    repeatIntervalMinutes = repeatIntervalMinutes,
                    initialDelaySeconds = delaySeconds,
                    requireNetwork = requireNetwork,
                    replaceExisting = replaceExisting,
                )
                "Scheduled periodic WorkManager compiled script '$normalizedName' (taskId=$normalizedTaskId) every $repeatIntervalMinutes minutes (initialDelaySeconds=$delaySeconds, requireNetwork=$requireNetwork, replaceExisting=$replaceExisting)."
            }
        }.getOrElse { error ->
            "Error: Failed to schedule WorkManager task '$normalizedName': ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("Cancel a WorkManager task by its unique workName.")
    fun cancelWorkTask(
        @LLMDescription("WorkManager unique name previously passed to scheduleWorkSession or scheduleWorkScript.")
        workName: String,
    ): String {
        val normalizedName = workName.trim()
        if (normalizedName.isEmpty()) {
            return "Error: workName must not be blank."
        }
        return runCatching {
            workGateway.cancel(normalizedName)
            "Requested cancellation for WorkManager task '$normalizedName'."
        }.getOrElse { error ->
            "Error: Failed to cancel WorkManager task '$normalizedName': ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("Inspect the current state and latest output summary of a WorkManager task by its unique workName.")
    suspend fun getWorkTaskStatus(
        @LLMDescription("WorkManager unique name to query.")
        workName: String,
    ): String {
        val normalizedName = workName.trim()
        if (normalizedName.isEmpty()) {
            return "Error: workName must not be blank."
        }
        return runCatching {
            val entries = workGateway.getStatus(normalizedName)
            if (entries.isEmpty()) {
                "No WorkManager entries found for '$normalizedName'."
            } else {
                entries.joinToString("\n") { entry ->
                    val outputPart = if (entry.outputSummary.isBlank()) "" else " | output=${entry.outputSummary}"
                    "${entry.id} | state=${entry.state} | attempts=${entry.runAttemptCount}$outputPart"
                }
            }
        }.getOrElse { error ->
            "Error: Failed to query WorkManager task '$normalizedName': ${error.message ?: error::class.simpleName}"
        }
    }

    private fun validateWorkTiming(delaySeconds: Long, repeatIntervalMinutes: Long): String? {
        if (delaySeconds < 0L) {
            return "delaySeconds must be >= 0."
        }
        if (repeatIntervalMinutes < 0L) {
            return "repeatIntervalMinutes must be 0 (one-time) or >= ${DefaultScheduledWorkGateway.MIN_PERIODIC_INTERVAL_MINUTES} (periodic)."
        }
        if (repeatIntervalMinutes in 1L until DefaultScheduledWorkGateway.MIN_PERIODIC_INTERVAL_MINUTES) {
            return "WorkManager periodic interval must be at least ${DefaultScheduledWorkGateway.MIN_PERIODIC_INTERVAL_MINUTES} minutes (got $repeatIntervalMinutes)."
        }
        return null
    }

    private fun resolveTriggerInstant(
        delaySeconds: Long,
        triggerAtIso: String,
    ): Result<Instant> {
        val normalizedIso = triggerAtIso.trim()
        val hasDelay = delaySeconds > 0L
        val hasIso = normalizedIso.isNotEmpty()

        if (delaySeconds < 0L) {
            return Result.failure(IllegalArgumentException("delaySeconds must be >= 0."))
        }
        if (hasDelay && hasIso) {
            return Result.failure(
                IllegalArgumentException("Specify either delaySeconds or triggerAtIso, not both."),
            )
        }
        if (!hasDelay && !hasIso) {
            return Result.failure(
                IllegalArgumentException("Provide either delaySeconds (> 0) or a future triggerAtIso timestamp."),
            )
        }
        val now = clock.instant()
        if (hasDelay) {
            return Result.success(now.plusSeconds(delaySeconds))
        }
        val parsed = parseIsoTimestamp(normalizedIso, clock.zone)
            ?: return Result.failure(
                IllegalArgumentException(
                    "Invalid triggerAtIso '$normalizedIso'. Use ISO-8601 format such as '2026-11-23T08:00:00+08:00' or '2026-11-23T08:00:00'.",
                ),
            )
        if (!parsed.isAfter(now)) {
            return Result.failure(
                IllegalArgumentException("triggerAtIso ($parsed) must be in the future (now=$now)."),
            )
        }
        return Result.success(parsed)
    }

    private fun parseIsoTimestamp(raw: String, defaultZone: ZoneId): Instant? {
        return runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).atZone(defaultZone).toInstant() }.getOrNull()
    }
}
