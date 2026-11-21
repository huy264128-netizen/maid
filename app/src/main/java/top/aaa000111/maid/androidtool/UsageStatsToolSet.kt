package top.aaa000111.maid.androidtool

import android.app.usage.UsageStatsManager
import android.content.Context
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShellCommandResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用使用情况统计工具集：
 * 支持通过普通 Android 使用情况访问权限（[AndroidPrivilege.USAGE_STATS]，即 `PACKAGE_USAGE_STATS`）、
 * [AndroidPrivilege.SHIZUKU] 或 [AndroidPrivilege.ROOT] 查询最近活跃的前台应用及各应用的前台使用时长。
 *
 * 当未授予 `USAGE_STATS` 但持有 `SHIZUKU` 或 `ROOT` 时，直接通过 Shizuku / Root 执行 `dumpsys usagestats` / `dumpsys activity recents` 获取，不修改自身权限。
 *
 * 支持权限：`USAGE_STATS`、`SHIZUKU`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class UsageStatsToolSet(
    context: Context,
    private val usageStatsPermissionChecker: (() -> Boolean)? = null,
    private val privilegedShellRunner: (suspend (String) -> ShellCommandResult)? = null,
) : AndroidToolSet() {

    private val appContext by lazy { context.applicationContext }

    override val name: String = "usage_stats"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.USAGE_STATS,
        AndroidPrivilege.SHIZUKU,
        AndroidPrivilege.ROOT,
    )

    private fun hasDirectUsageStatsPermission(): Boolean {
        usageStatsPermissionChecker?.let { return it() }
        return DefaultAndroidPrivilegeChecker(appContext, checkRoot = false).isUsageStatsAvailable()
    }

    private suspend fun execPrivileged(command: String): ShellCommandResult {
        privilegedShellRunner?.let { return it(command) }
        return PrivilegeBackend.execShizukuOrRootShell(command)
    }

    @Tool
    @LLMDescription(
        "Query recently active/foreground applications within the given time window (in minutes) using Android UsageStatsManager or Shizuku/Root dumpsys.",
    )
    suspend fun getRecentForegroundApps(
        @LLMDescription("Lookback time window in minutes (default 60, range 1..1440).")
        withinMinutes: Int = 60,
        @LLMDescription("Maximum number of recent packages to return (default 15).")
        limit: Int = 15,
    ): String {
        val safeMinutes = withinMinutes.coerceIn(1, 1440)
        val safeLimit = limit.coerceIn(1, 100)
        val now = System.currentTimeMillis()
        val startTime = now - safeMinutes * 60_000L

        if (hasDirectUsageStatsPermission()) {
            val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val stats = runCatching {
                usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now)
            }.getOrNull().orEmpty()

            if (stats.isNotEmpty()) {
                val recent = stats
                    .filter { it.lastTimeUsed >= startTime }
                    .groupBy { it.packageName }
                    .mapNotNull { (_, entries) -> entries.maxByOrNull { it.lastTimeUsed } }
                    .sortedByDescending { it.lastTimeUsed }
                    .take(safeLimit)

                if (recent.isEmpty()) {
                    return "No applications used within the last $safeMinutes minutes."
                }

                return buildString {
                    appendLine("windowMinutes=$safeMinutes count=${recent.size}")
                    recent.forEachIndexed { idx, item ->
                        val lastUsed = formatTime(item.lastTimeUsed)
                        val fgSec = item.totalTimeInForeground / 1000L
                        appendLine("${idx + 1}. ${item.packageName} · lastUsed=$lastUsed · foreground=${fgSec}s")
                    }
                }.trimEnd()
            }
        }

        // 未授予 PACKAGE_USAGE_STATS 时，直接走 Shizuku / Root 查询 dumpsys activity recents 与 usagestats
        val cmd = """
            echo "=== Recent Tasks (dumpsys activity recents) ==="
            dumpsys activity recents | grep -E "Recent #|realActivity=|baseIntent=" | head -n ${safeLimit * 3}
            echo "=== In-Memory UsageStats (dumpsys usagestats) ==="
            dumpsys usagestats | grep -E "package=|totalTimeUsed=|totalTime=" | tail -n $safeLimit
        """.trimIndent()
        val res = execPrivileged(cmd)
        if (res.isSuccess && res.stdout.isNotBlank()) {
            return res.stdout.trim()
        }
        return "No usage stats available (requires PACKAGE_USAGE_STATS, Shizuku, or Root): ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
    }

    @Tool
    @LLMDescription(
        "Query foreground usage duration summary for a specific package or top used packages over the past N days.",
    )
    suspend fun getAppUsageSummary(
        @LLMDescription("Optional package name to filter (leave empty for top used packages).")
        packageName: String = "",
        @LLMDescription("Number of days to aggregate (default 1, range 1..30).")
        days: Int = 1,
        @LLMDescription("Maximum number of packages to return when packageName is empty (default 20).")
        limit: Int = 20,
    ): String {
        val safeDays = days.coerceIn(1, 30)
        val safeLimit = limit.coerceIn(1, 100)
        val now = System.currentTimeMillis()
        val startTime = now - safeDays * 24L * 3600_000L
        val targetPkg = packageName.trim()

        if (hasDirectUsageStatsPermission()) {
            val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val aggregated = runCatching {
                usm?.queryAndAggregateUsageStats(startTime, now)
            }.getOrNull().orEmpty()

            if (aggregated.isNotEmpty()) {
                val filtered = aggregated.values
                    .filter { stat ->
                        (targetPkg.isEmpty() || stat.packageName.equals(targetPkg, ignoreCase = true)) &&
                            stat.totalTimeInForeground > 0L
                    }
                    .sortedByDescending { it.totalTimeInForeground }
                    .take(safeLimit)

                if (filtered.isEmpty()) {
                    return if (targetPkg.isNotEmpty()) {
                        "No foreground usage recorded for package '$targetPkg' in the last $safeDays day(s)."
                    } else {
                        "No foreground usage recorded in the last $safeDays day(s)."
                    }
                }

                return buildString {
                    appendLine("days=$safeDays matched=${filtered.size}")
                    filtered.forEachIndexed { idx, stat ->
                        val durationStr = formatDuration(stat.totalTimeInForeground)
                        val lastUsed = formatTime(stat.lastTimeUsed)
                        appendLine("${idx + 1}. ${stat.packageName} · foreground=$durationStr · lastUsed=$lastUsed")
                    }
                }.trimEnd()
            }
        }

        // 未授予 PACKAGE_USAGE_STATS 时，直接通过 Shizuku / Root 执行 dumpsys usagestats
        val cmd = if (targetPkg.isNotEmpty()) {
            val quotedPkg = PrivilegeBackend.shellQuote(targetPkg)
            "dumpsys usagestats $quotedPkg | head -n 120"
        } else {
            "dumpsys usagestats | grep -E 'package=|totalTimeUsed=|totalTime=' | tail -n ${safeLimit * 2}"
        }
        val res = execPrivileged(cmd)
        if (res.isSuccess && res.stdout.isNotBlank()) {
            return res.stdout.trim()
        }
        return "No usage stats available (requires PACKAGE_USAGE_STATS, Shizuku, or Root): ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
    }

    private fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "never"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
