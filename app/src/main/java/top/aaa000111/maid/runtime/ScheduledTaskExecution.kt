package top.aaa000111.maid.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.aaa000111.maid.chat.ChatBackgroundRuntime
import top.aaa000111.maid.chat.UserInput
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 定时触发后要执行的具体动作。
 */
sealed interface ScheduledTaskAction {
    /**
     * 启动一个携带初始提示词的新对话会话并提交给对话调度器执行。
     */
    data class StartPromptSession(
        val prompt: String,
        val sessionTitle: String = "",
    ) : ScheduledTaskAction

    /**
     * 按 `taskId` 加载并直接执行已编译的 MaidLang 脚本字节码。
     */
    data class RunCompiledScript(
        val taskId: String,
    ) : ScheduledTaskAction
}

/**
 * 在 AlarmManager 广播与 WorkManager 任务间传递的统一载荷。
 */
data class ScheduledTaskPayload(
    val scheduleId: String,
    val action: ScheduledTaskAction,
) {
    fun toKeyValueMap(): Map<String, String> = when (action) {
        is ScheduledTaskAction.StartPromptSession -> buildMap {
            put(KEY_SCHEDULE_ID, scheduleId)
            put(KEY_ACTION_TYPE, ACTION_PROMPT_SESSION)
            put(KEY_PROMPT, action.prompt)
            if (action.sessionTitle.isNotBlank()) {
                put(KEY_SESSION_TITLE, action.sessionTitle)
            }
        }

        is ScheduledTaskAction.RunCompiledScript -> mapOf(
            KEY_SCHEDULE_ID to scheduleId,
            KEY_ACTION_TYPE to ACTION_COMPILED_SCRIPT,
            KEY_TASK_ID to action.taskId,
        )
    }

    fun toBundle(): Bundle = Bundle().apply {
        for ((key, value) in toKeyValueMap()) {
            putString(key, value)
        }
    }

    fun toWorkData(): Data {
        val builder = Data.Builder()
        for ((key, value) in toKeyValueMap()) {
            builder.putString(key, value)
        }
        return builder.build()
    }

    companion object {
        const val KEY_SCHEDULE_ID = "top.aaa000111.maid.scheduled.SCHEDULE_ID"
        const val KEY_ACTION_TYPE = "top.aaa000111.maid.scheduled.ACTION_TYPE"
        const val KEY_PROMPT = "top.aaa000111.maid.scheduled.PROMPT"
        const val KEY_SESSION_TITLE = "top.aaa000111.maid.scheduled.SESSION_TITLE"
        const val KEY_TASK_ID = "top.aaa000111.maid.scheduled.TASK_ID"
        const val KEY_OUTPUT_SUMMARY = "top.aaa000111.maid.scheduled.OUTPUT_SUMMARY"

        const val ACTION_PROMPT_SESSION = "prompt_session"
        const val ACTION_COMPILED_SCRIPT = "compiled_script"

        /** 留出安全余量，避免超出 WorkManager `Data` 10KB 上限。 */
        const val MAX_PROMPT_BYTES = 8192

        fun validatePrompt(prompt: String): String? {
            if (prompt.isBlank()) {
                return "Prompt must not be blank."
            }
            val sizeBytes = prompt.toByteArray(StandardCharsets.UTF_8).size
            if (sizeBytes > MAX_PROMPT_BYTES) {
                return "Prompt size ($sizeBytes bytes) exceeds the $MAX_PROMPT_BYTES-byte scheduling payload limit."
            }
            return null
        }

        fun fromKeyValueMap(
            values: Map<String, String?>,
            fallbackScheduleId: String? = null,
        ): ScheduledTaskPayload? {
            val scheduleId = values[KEY_SCHEDULE_ID]?.takeIf { it.isNotBlank() }
                ?: fallbackScheduleId?.takeIf { it.isNotBlank() }
                ?: "scheduled"
            return when (values[KEY_ACTION_TYPE]) {
                ACTION_PROMPT_SESSION -> {
                    val prompt = values[KEY_PROMPT]?.takeIf { it.isNotBlank() } ?: return null
                    val title = values[KEY_SESSION_TITLE].orEmpty().trim()
                    ScheduledTaskPayload(
                        scheduleId = scheduleId,
                        action = ScheduledTaskAction.StartPromptSession(
                            prompt = prompt,
                            sessionTitle = title,
                        ),
                    )
                }

                ACTION_COMPILED_SCRIPT -> {
                    val taskId = values[KEY_TASK_ID]?.trim()?.takeIf { it.isNotBlank() } ?: return null
                    ScheduledTaskPayload(
                        scheduleId = scheduleId,
                        action = ScheduledTaskAction.RunCompiledScript(taskId = taskId),
                    )
                }

                else -> null
            }
        }

        fun fromBundle(
            bundle: Bundle?,
            fallbackScheduleId: String? = null,
        ): ScheduledTaskPayload? {
            if (bundle == null) return null
            val values = mapOf(
                KEY_SCHEDULE_ID to bundle.getString(KEY_SCHEDULE_ID),
                KEY_ACTION_TYPE to bundle.getString(KEY_ACTION_TYPE),
                KEY_PROMPT to bundle.getString(KEY_PROMPT),
                KEY_SESSION_TITLE to bundle.getString(KEY_SESSION_TITLE),
                KEY_TASK_ID to bundle.getString(KEY_TASK_ID),
            )
            return fromKeyValueMap(values, fallbackScheduleId = fallbackScheduleId)
        }

        fun fromWorkData(data: Data): ScheduledTaskPayload? {
            val values = mapOf(
                KEY_SCHEDULE_ID to data.getString(KEY_SCHEDULE_ID),
                KEY_ACTION_TYPE to data.getString(KEY_ACTION_TYPE),
                KEY_PROMPT to data.getString(KEY_PROMPT),
                KEY_SESSION_TITLE to data.getString(KEY_SESSION_TITLE),
                KEY_TASK_ID to data.getString(KEY_TASK_ID),
            )
            return fromKeyValueMap(values)
        }
    }
}

/**
 * 定时触发启动带提示词的会话的结果。
 */
data class ScheduledSessionLaunchResult(
    val sessionId: String,
    val launched: Boolean,
    val summary: String,
)

/**
 * 定时任务执行的统一结果。
 */
sealed interface ScheduledExecutionOutcome {
    val summary: String

    data class Success(
        override val summary: String,
    ) : ScheduledExecutionOutcome

    data class Failure(
        override val summary: String,
    ) : ScheduledExecutionOutcome
}

/**
 * 执行定时任务载荷的核心执行器（无主线程阻塞，便于单测注入替身）。
 */
class ScheduledTaskExecutor(
    private val taskStoreProvider: () -> MaidLangTaskStore,
    private val sessionLauncher: suspend (prompt: String, sessionTitle: String) -> ScheduledSessionLaunchResult =
        ::defaultLaunchPromptSession,
) {
    suspend fun execute(payload: ScheduledTaskPayload): ScheduledExecutionOutcome {
        return when (val action = payload.action) {
            is ScheduledTaskAction.StartPromptSession -> {
                val resolvedTitle = action.sessionTitle.ifBlank {
                    "定时任务: ${payload.scheduleId}"
                }
                try {
                    val result = sessionLauncher(action.prompt, resolvedTitle)
                    if (result.launched) {
                        ScheduledExecutionOutcome.Success(result.summary)
                    } else {
                        ScheduledExecutionOutcome.Failure(result.summary)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ScheduledExecutionOutcome.Failure(
                        "Failed to start scheduled prompt session '${payload.scheduleId}': ${e.message ?: e::class.simpleName}",
                    )
                }
            }

            is ScheduledTaskAction.RunCompiledScript -> {
                val result = withContext(Dispatchers.IO) {
                    taskStoreProvider().executeTask(action.taskId)
                }
                when (result) {
                    is MaidLangExecutionResult.Success -> ScheduledExecutionOutcome.Success(
                        "Executed compiled MaidLang task '${result.taskId}' in ${result.durationMillis} ms; result=${result.value}",
                    )

                    is MaidLangExecutionResult.Failure -> ScheduledExecutionOutcome.Failure(
                        "Compiled MaidLang task '${result.taskId}' failed: ${result.errorMessage}",
                    )
                }
            }
        }
    }

    companion object {
        suspend fun defaultLaunchPromptSession(
            prompt: String,
            sessionTitle: String,
        ): ScheduledSessionLaunchResult {
            AppRuntime.syncModelExecutors()
            val dispatcher = ChatBackgroundRuntime.current
            val session = dispatcher.createBackgroundSession(title = sessionTitle)
            val submitted = dispatcher.submitInput(
                session = session,
                input = UserInput(text = prompt),
            )
            if (!submitted) {
                return ScheduledSessionLaunchResult(
                    sessionId = session.id,
                    launched = false,
                    summary = "Failed to submit scheduled prompt to session '${session.id}'.",
                )
            }
            dispatcher.awaitActiveTurn(session.id)
            return ScheduledSessionLaunchResult(
                sessionId = session.id,
                launched = true,
                summary = "Completed scheduled prompt session '${session.id}' (title='${session.state.value.title}').",
            )
        }
    }
}

/**
 * AlarmManager 定时调度适配边界，默认委托给 [AlarmScheduler] 并投递至 [ScheduledAlarmReceiver]。
 */
interface ScheduledAlarmGateway {
    fun schedule(
        alarmId: String,
        triggerAt: Instant,
        payload: ScheduledTaskPayload,
        allowWhileIdle: Boolean,
    )

    fun cancel(alarmId: String): Boolean
}

class DefaultScheduledAlarmGateway(
    private val alarmScheduler: AlarmScheduler,
) : ScheduledAlarmGateway {
    override fun schedule(
        alarmId: String,
        triggerAt: Instant,
        payload: ScheduledTaskPayload,
        allowWhileIdle: Boolean,
    ) {
        alarmScheduler.schedule(
            id = alarmId,
            receiver = ScheduledAlarmReceiver::class.java,
            triggerAt = triggerAt,
            extras = payload.toBundle(),
            allowWhileIdle = allowWhileIdle,
        )
    }

    override fun cancel(alarmId: String): Boolean {
        return alarmScheduler.cancel(
            id = alarmId,
            receiver = ScheduledAlarmReceiver::class.java,
        )
    }
}

/**
 * WorkManager 任务状态摘要。
 */
data class ScheduledWorkStatusEntry(
    val id: String,
    val state: String,
    val runAttemptCount: Int,
    val outputSummary: String = "",
)

/**
 * WorkManager 定时调度适配边界，默认委托给 [WorkScheduler] 与 [ScheduledTaskWorker]。
 */
interface ScheduledWorkGateway {
    fun scheduleOneTime(
        workName: String,
        payload: ScheduledTaskPayload,
        delaySeconds: Long,
        requireNetwork: Boolean,
        replaceExisting: Boolean,
    )

    fun schedulePeriodic(
        workName: String,
        payload: ScheduledTaskPayload,
        repeatIntervalMinutes: Long,
        initialDelaySeconds: Long,
        requireNetwork: Boolean,
        replaceExisting: Boolean,
    )

    fun cancel(workName: String)

    suspend fun getStatus(workName: String): List<ScheduledWorkStatusEntry>
}

class DefaultScheduledWorkGateway(
    private val workScheduler: WorkScheduler,
) : ScheduledWorkGateway {
    override fun scheduleOneTime(
        workName: String,
        payload: ScheduledTaskPayload,
        delaySeconds: Long,
        requireNetwork: Boolean,
        replaceExisting: Boolean,
    ) {
        val constraints = buildConstraints(requireNetwork)
        val builder = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInputData(payload.toWorkData())
            .setConstraints(constraints)
            .addTag(TAG_SCHEDULED_TASK)
        if (delaySeconds > 0L) {
            builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        }
        val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workScheduler.enqueueOneTime(workName, builder.build(), policy)
    }

    override fun schedulePeriodic(
        workName: String,
        payload: ScheduledTaskPayload,
        repeatIntervalMinutes: Long,
        initialDelaySeconds: Long,
        requireNetwork: Boolean,
        replaceExisting: Boolean,
    ) {
        require(repeatIntervalMinutes >= MIN_PERIODIC_INTERVAL_MINUTES) {
            "WorkManager periodic interval must be at least $MIN_PERIODIC_INTERVAL_MINUTES minutes."
        }
        val constraints = buildConstraints(requireNetwork)
        val builder = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
            repeatIntervalMinutes,
            TimeUnit.MINUTES,
        )
            .setInputData(payload.toWorkData())
            .setConstraints(constraints)
            .addTag(TAG_SCHEDULED_TASK)
        if (initialDelaySeconds > 0L) {
            builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }
        val policy = if (replaceExisting) {
            ExistingPeriodicWorkPolicy.UPDATE
        } else {
            ExistingPeriodicWorkPolicy.KEEP
        }
        workScheduler.enqueuePeriodic(workName, builder.build(), policy)
    }

    override fun cancel(workName: String) {
        workScheduler.cancel(workName)
    }

    override suspend fun getStatus(workName: String): List<ScheduledWorkStatusEntry> {
        val infos: List<WorkInfo> = workScheduler.observe(workName).first()
        return infos.map { info ->
            ScheduledWorkStatusEntry(
                id = info.id.toString(),
                state = info.state.name,
                runAttemptCount = info.runAttemptCount,
                outputSummary = info.outputData.getString(ScheduledTaskPayload.KEY_OUTPUT_SUMMARY).orEmpty(),
            )
        }
    }

    private fun buildConstraints(requireNetwork: Boolean): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(if (requireNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
    }

    companion object {
        const val TAG_SCHEDULED_TASK = "maid_scheduled_task"
        const val MIN_PERIODIC_INTERVAL_MINUTES = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 60_000L
    }
}

/**
 * 执行定时任务载荷（启动带提示词的会话或运行已编译 MaidLang 脚本）的后台 Worker。
 */
class ScheduledTaskWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        AppRuntime.init(applicationContext)
        val payload = ScheduledTaskPayload.fromWorkData(inputData)
            ?: return Result.failure(
                workDataOf(
                    ScheduledTaskPayload.KEY_OUTPUT_SUMMARY to "Invalid or missing scheduled task payload.",
                ),
            )

        val outcome = AppRuntime.getScheduledTaskExecutor().execute(payload)
        val output = workDataOf(ScheduledTaskPayload.KEY_OUTPUT_SUMMARY to outcome.summary)
        return when (outcome) {
            is ScheduledExecutionOutcome.Success -> Result.success(output)
            is ScheduledExecutionOutcome.Failure -> Result.failure(output)
        }
    }
}

/**
 * 接收 AlarmManager 广播后立即将耗时执行移交 WorkManager（[ScheduledTaskWorker]），
 * 不在主线程阻塞且避免超过 BroadcastReceiver 的时间窗口限制。
 */
class ScheduledAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        AppRuntime.init(appContext)
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        val payload = ScheduledTaskPayload.fromBundle(intent.extras, fallbackScheduleId = alarmId)
            ?: return

        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInputData(payload.toWorkData())
            .addTag(DefaultScheduledWorkGateway.TAG_SCHEDULED_TASK)
            .addTag("alarm_${payload.scheduleId}")
            .build()

        val handoffName = "alarm_trigger_${payload.scheduleId}_${System.currentTimeMillis()}"
        AppRuntime.getWorkScheduler().enqueueOneTime(
            name = handoffName,
            request = request,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }
}
