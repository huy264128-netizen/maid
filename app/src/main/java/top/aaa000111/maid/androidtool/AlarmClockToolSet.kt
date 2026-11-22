package top.aaa000111.maid.androidtool

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import java.util.Calendar
import java.util.Locale

/**
 * 面向手机自带时钟/闹钟应用的系统动作请求。
 */
sealed interface AlarmClockRequest {
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val message: String,
        val days: List<Int>,
        val vibrate: Boolean,
        val skipUi: Boolean,
    ) : AlarmClockRequest

    data class SetTimer(
        val lengthSeconds: Int,
        val message: String,
        val skipUi: Boolean,
    ) : AlarmClockRequest

    data object ShowAlarms : AlarmClockRequest

    data object ShowTimers : AlarmClockRequest

    data class DismissAlarm(
        val searchMode: String,
        val hour: Int?,
        val minute: Int?,
        val label: String,
    ) : AlarmClockRequest

    data class SnoozeAlarm(
        val snoozeDurationMinutes: Int?,
    ) : AlarmClockRequest
}

/**
 * 抽象手机系统闹钟 Intent 的发送边界，便于生产环境使用 [AlarmClock] Intent、单元测试注入替身。
 */
fun interface AlarmClockActionLauncher {
    fun launch(request: AlarmClockRequest): Result<String>
}

/**
 * 调用手机自带闹钟/时钟应用的独立工具集（基于 Android [AlarmClock] 系统 Intent）。
 *
 * `com.android.alarm.permission.SET_ALARM` 为普通安装权限，无需特殊系统权限或 Shizuku/无障碍。
 */
class AlarmClockToolSet(
    context: Context,
    private val launcher: AlarmClockActionLauncher = AndroidAlarmClockActionLauncher(context),
) : AndroidToolSet() {

    override val name: String = TOOLSET_NAME

    // com.android.alarm.permission.SET_ALARM is a normal manifest permission.
    override fun requiredPrivileges(): Set<AndroidPrivilege> = emptySet()

    @Tool
    @LLMDescription(
        "Create an alarm in the phone's built-in system Clock/Alarm app (distinct from Maid's background AlarmManager tasks). " +
            "Supports 24-hour time, optional label, optional repeating weekdays, vibration, and skipUi.",
    )
    fun setSystemAlarm(
        @LLMDescription("Alarm hour in 24-hour format (0..23).")
        hour: Int,
        @LLMDescription("Alarm minute (0..59).")
        minute: Int,
        @LLMDescription("Optional alarm label or reminder text.")
        message: String = "",
        @LLMDescription(
            "Optional comma-separated repeat weekdays (e.g. 'mon,tue,wed,thu,fri', '1,2,3,4,5', '周一,周三,周五', 'weekdays', 'weekends', or 'everyday'). " +
                "Leave empty for a one-time alarm.",
        )
        days: String = "",
        @LLMDescription("Whether the alarm should vibrate (default true).")
        vibrate: Boolean = true,
        @LLMDescription("Set true to ask the system clock app to save the alarm directly without showing confirmation UI.")
        skipUi: Boolean = true,
    ): String {
        if (hour !in 0..23) {
            return "Error: hour must be between 0 and 23 (got $hour)."
        }
        if (minute !in 0..59) {
            return "Error: minute must be between 0 and 59 (got $minute)."
        }
        val parsedDays = parseRepeatDays(days).getOrElse { error ->
            return "Error: ${error.message}"
        }
        val request = AlarmClockRequest.SetAlarm(
            hour = hour,
            minute = minute,
            message = message.trim(),
            days = parsedDays,
            vibrate = vibrate,
            skipUi = skipUi,
        )
        return launcher.launch(request).getOrElse { error ->
            "Error: ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("Start a countdown timer in the phone's built-in system Clock app.")
    fun setSystemTimer(
        @LLMDescription("Countdown duration in seconds (must be > 0).")
        lengthSeconds: Int,
        @LLMDescription("Optional timer label.")
        message: String = "",
        @LLMDescription("Set true to start the timer immediately without showing confirmation UI.")
        skipUi: Boolean = true,
    ): String {
        if (lengthSeconds <= 0) {
            return "Error: lengthSeconds must be > 0 (got $lengthSeconds)."
        }
        val request = AlarmClockRequest.SetTimer(
            lengthSeconds = lengthSeconds,
            message = message.trim(),
            skipUi = skipUi,
        )
        return launcher.launch(request).getOrElse { error ->
            "Error: ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("Open the alarm list in the phone's built-in system Clock app.")
    fun showSystemAlarms(): String {
        return launcher.launch(AlarmClockRequest.ShowAlarms).getOrElse { error ->
            "Error: ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("Open the timer list in the phone's built-in system Clock app.")
    fun showSystemTimers(): String {
        return launcher.launch(AlarmClockRequest.ShowTimers).getOrElse { error ->
            "Error: ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription(
        "Dismiss an alarm in the phone's built-in system Clock app. " +
            "Supports searchMode='next' (default), 'all', 'time' (requires hour and minute), or 'label' (requires label).",
    )
    fun dismissSystemAlarm(
        @LLMDescription("Which alarm to dismiss: 'next', 'all', 'time', or 'label'.")
        searchMode: String = "next",
        @LLMDescription("Hour (0..23) when searchMode='time'; otherwise -1.")
        hour: Int = -1,
        @LLMDescription("Minute (0..59) when searchMode='time'; otherwise -1.")
        minute: Int = -1,
        @LLMDescription("Alarm label to match when searchMode='label'.")
        label: String = "",
    ): String {
        val normalizedMode = searchMode.trim().lowercase(Locale.ROOT)
        val extraMode = when (normalizedMode) {
            "", "next" -> AlarmClock.ALARM_SEARCH_MODE_NEXT
            "all" -> AlarmClock.ALARM_SEARCH_MODE_ALL
            "time" -> AlarmClock.ALARM_SEARCH_MODE_TIME
            "label" -> AlarmClock.ALARM_SEARCH_MODE_LABEL
            else -> return "Error: Invalid searchMode '$searchMode'. Use 'next', 'all', 'time', or 'label'."
        }

        val resolvedHour: Int?
        val resolvedMinute: Int?
        val resolvedLabel: String
        when (extraMode) {
            AlarmClock.ALARM_SEARCH_MODE_TIME -> {
                if (hour !in 0..23 || minute !in 0..59) {
                    return "Error: searchMode='time' requires valid hour (0..23) and minute (0..59)."
                }
                resolvedHour = hour
                resolvedMinute = minute
                resolvedLabel = label.trim()
            }

            AlarmClock.ALARM_SEARCH_MODE_LABEL -> {
                resolvedLabel = label.trim()
                if (resolvedLabel.isEmpty()) {
                    return "Error: searchMode='label' requires a non-empty label."
                }
                resolvedHour = null
                resolvedMinute = null
            }

            else -> {
                resolvedHour = null
                resolvedMinute = null
                resolvedLabel = label.trim()
            }
        }

        val request = AlarmClockRequest.DismissAlarm(
            searchMode = extraMode,
            hour = resolvedHour,
            minute = resolvedMinute,
            label = resolvedLabel,
        )
        return launcher.launch(request).getOrElse { error ->
            "Error: ${error.message ?: error::class.simpleName}"
        }
    }

    @Tool
    @LLMDescription("Snooze the currently ringing alarm in the phone's built-in system Clock app.")
    fun snoozeSystemAlarm(
        @LLMDescription("Optional snooze duration in minutes (> 0); pass 0 to use the system Clock app's default snooze duration.")
        snoozeDurationMinutes: Int = 0,
    ): String {
        if (snoozeDurationMinutes < 0) {
            return "Error: snoozeDurationMinutes must be >= 0 (got $snoozeDurationMinutes)."
        }
        val request = AlarmClockRequest.SnoozeAlarm(
            snoozeDurationMinutes = snoozeDurationMinutes.takeIf { it > 0 },
        )
        return launcher.launch(request).getOrElse { error ->
            "Error: ${error.message ?: error::class.simpleName}"
        }
    }

    companion object {
        const val TOOLSET_NAME = "alarm_clock"

        internal fun parseRepeatDays(raw: String): Result<List<Int>> {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) return Result.success(emptyList())

            val result = LinkedHashSet<Int>()
            val tokens = cleaned.split(',', '，', ';', '；', ' ')
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }

            for (token in tokens) {
                when (token) {
                    "everyday", "daily", "all", "每天", "每日" -> {
                        result.addAll(
                            listOf(
                                Calendar.MONDAY,
                                Calendar.TUESDAY,
                                Calendar.WEDNESDAY,
                                Calendar.THURSDAY,
                                Calendar.FRIDAY,
                                Calendar.SATURDAY,
                                Calendar.SUNDAY,
                            ),
                        )
                    }

                    "weekdays", "workdays", "工作日" -> {
                        result.addAll(
                            listOf(
                                Calendar.MONDAY,
                                Calendar.TUESDAY,
                                Calendar.WEDNESDAY,
                                Calendar.THURSDAY,
                                Calendar.FRIDAY,
                            ),
                        )
                    }

                    "weekends", "weekend", "周末" -> {
                        result.addAll(listOf(Calendar.SATURDAY, Calendar.SUNDAY))
                    }

                    "mon", "monday", "1", "周一", "星期一", "礼拜一" -> result.add(Calendar.MONDAY)
                    "tue", "tues", "tuesday", "2", "周二", "星期二", "礼拜二" -> result.add(Calendar.TUESDAY)
                    "wed", "wednesday", "3", "周三", "星期三", "礼拜三" -> result.add(Calendar.WEDNESDAY)
                    "thu", "thur", "thurs", "thursday", "4", "周四", "星期四", "礼拜四" -> result.add(Calendar.THURSDAY)
                    "fri", "friday", "5", "周五", "星期五", "礼拜五" -> result.add(Calendar.FRIDAY)
                    "sat", "saturday", "6", "周六", "星期六", "礼拜六" -> result.add(Calendar.SATURDAY)
                    "sun", "sunday", "7", "0", "周日", "周天", "星期日", "星期天", "礼拜日", "礼拜天" -> result.add(Calendar.SUNDAY)
                    else -> {
                        return Result.failure(
                            IllegalArgumentException(
                                "Unrecognized weekday '$token'. Use mon..sun, 1..7, 周一..周日, weekdays, weekends, or everyday.",
                            ),
                        )
                    }
                }
            }
            return Result.success(result.toList())
        }

        internal fun formatTime(hour: Int, minute: Int): String =
            String.format(Locale.ROOT, "%02d:%02d", hour, minute)

        internal fun formatDayLabel(calendarDay: Int): String = when (calendarDay) {
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            Calendar.SUNDAY -> "Sun"
            else -> calendarDay.toString()
        }
    }
}

internal class AndroidAlarmClockActionLauncher(
    context: Context,
) : AlarmClockActionLauncher {

    private val appContext: Context by lazy { context.applicationContext }

    override fun launch(request: AlarmClockRequest): Result<String> {
        val intent = buildIntent(request)
        return runCatching {
            val resolved = intent.resolveActivity(appContext.packageManager)
                ?: throw ActivityNotFoundException(
                    "No system Clock/Alarm app found to handle ${intent.action}.",
                )
            appContext.startActivity(intent)
            buildSuccessMessage(request, resolved.packageName)
        }.recoverCatching { error ->
            when (error) {
                is ActivityNotFoundException -> throw error
                is SecurityException -> throw SecurityException(
                    "Android denied launching system Clock/Alarm action (${intent.action}): ${error.message}",
                    error,
                )

                else -> throw error
            }
        }
    }

    private fun buildIntent(request: AlarmClockRequest): Intent {
        return when (request) {
            is AlarmClockRequest.SetAlarm -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, request.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
                putExtra(AlarmClock.EXTRA_VIBRATE, request.vibrate)
                putExtra(AlarmClock.EXTRA_SKIP_UI, request.skipUi)
                if (request.message.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, request.message)
                }
                if (request.days.isNotEmpty()) {
                    putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(request.days))
                }
            }

            is AlarmClockRequest.SetTimer -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, request.lengthSeconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, request.skipUi)
                if (request.message.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, request.message)
                }
            }

            AlarmClockRequest.ShowAlarms -> Intent(AlarmClock.ACTION_SHOW_ALARMS)

            AlarmClockRequest.ShowTimers -> Intent(AlarmClock.ACTION_SHOW_TIMERS)

            is AlarmClockRequest.DismissAlarm -> Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, request.searchMode)
                request.hour?.let { putExtra(AlarmClock.EXTRA_HOUR, it) }
                request.minute?.let { putExtra(AlarmClock.EXTRA_MINUTES, it) }
                if (request.label.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, request.label)
                }
            }

            is AlarmClockRequest.SnoozeAlarm -> Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
                request.snoozeDurationMinutes?.let {
                    putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, it)
                }
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun buildSuccessMessage(request: AlarmClockRequest, targetPackage: String): String {
        return when (request) {
            is AlarmClockRequest.SetAlarm -> {
                val time = AlarmClockToolSet.formatTime(request.hour, request.minute)
                val labelPart = if (request.message.isEmpty()) "" else ", label='${request.message}'"
                val repeatPart = if (request.days.isEmpty()) {
                    ", one-time"
                } else {
                    ", repeat=[${request.days.joinToString(",") { AlarmClockToolSet.formatDayLabel(it) }}]"
                }
                "Dispatched system alarm at $time$labelPart$repeatPart (vibrate=${request.vibrate}, skipUi=${request.skipUi}) to $targetPackage."
            }

            is AlarmClockRequest.SetTimer -> {
                val labelPart = if (request.message.isEmpty()) "" else ", label='${request.message}'"
                "Dispatched system timer for ${request.lengthSeconds}s$labelPart (skipUi=${request.skipUi}) to $targetPackage."
            }

            AlarmClockRequest.ShowAlarms -> "Opened system alarms in $targetPackage."

            AlarmClockRequest.ShowTimers -> "Opened system timers in $targetPackage."

            is AlarmClockRequest.DismissAlarm -> {
                "Dispatched dismiss system alarm (mode=${request.searchMode}) to $targetPackage."
            }

            is AlarmClockRequest.SnoozeAlarm -> {
                val durationPart = request.snoozeDurationMinutes?.let { " for ${it}m" }.orEmpty()
                "Dispatched snooze system alarm$durationPart to $targetPackage."
            }
        }
    }
}
