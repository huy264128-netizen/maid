package top.aaa000111.maid.androidtool

import android.content.Intent
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShizukuManager

/**
 * 应用与进程控制工具集：查询前台 Activity、启动组件、强行停止应用、清理后台及列举运行进程。
 *
 * 支持权限：`SHIZUKU`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class AppControlToolSet : AndroidToolSet() {

    override val name: String = "app_control"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.SHIZUKU,
        AndroidPrivilege.ROOT,
    )

    @Tool
    @LLMDescription("Get the currently focused top Activity component name.")
    suspend fun getTopActivity(): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val result = ShizukuManager.getTopActivity()
            if (result.isSuccess) {
                return result.getOrNull() ?: "No top activity found"
            }
        }

        if (PrivilegeBackend.isRootAvailable()) {
            val res = PrivilegeBackend.execRootShell(
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 5",
            )
            if (res.isSuccess && res.stdout.isNotBlank()) {
                return res.stdout.trim()
            }
        }

        return "Unable to resolve top activity (requires Shizuku or Root)"
    }

    @Tool
    @LLMDescription("Launch an Activity by packageName and optional className or action.")
    suspend fun startActivity(
        @LLMDescription("Target application package name.") packageName: String,
        @LLMDescription("Optional full or relative Activity class name.") className: String? = null,
        @LLMDescription("Optional Intent action (defaults to android.intent.action.MAIN when className is provided).") action: String? = null,
        @LLMDescription("Target user ID (default 0).") userId: Int = 0,
    ): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "packageName must not be blank"

        if (!className.isNullOrBlank() && PrivilegeBackend.isShizukuReady()) {
            val code = ShizukuManager.startActivity(
                packageName = pkg,
                className = className.trim(),
                action = action?.takeIf { it.isNotBlank() } ?: Intent.ACTION_MAIN,
                userId = userId,
            ).getOrNull()
            if (code != null) {
                return "startActivity($pkg/${className.trim()}) resultCode=$code via Shizuku"
            }
        }

        val shellCmd = if (!className.isNullOrBlank()) {
            val resolvedClass = className.trim().let { cls ->
                if (cls.startsWith(".")) "$pkg$cls" else cls
            }
            val actionPart = action?.takeIf { it.isNotBlank() }
                ?.let { "-a ${PrivilegeBackend.shellQuote(it)} " }
                .orEmpty()
            "am start --user $userId $actionPart-n ${PrivilegeBackend.shellQuote("$pkg/$resolvedClass")}"
        } else if (!action.isNullOrBlank()) {
            "am start --user $userId -a ${PrivilegeBackend.shellQuote(action.trim())} -p ${PrivilegeBackend.shellQuote(pkg)}"
        } else {
            "monkey -p ${PrivilegeBackend.shellQuote(pkg)} -c android.intent.category.LAUNCHER 1"
        }

        val result = PrivilegeBackend.execShizukuOrRootShell(shellCmd)
        return if (result.isSuccess) {
            "Started $pkg: ${result.stdout.trim().ifBlank { "ok" }}"
        } else {
            "Failed to start $pkg: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Force stop an application package.")
    suspend fun forceStopPackage(
        @LLMDescription("Target application package name.") packageName: String,
        @LLMDescription("Target user ID (default 0).") userId: Int = 0,
    ): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "packageName must not be blank"

        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.forceStopPackage(pkg, userId).getOrDefault(false)
            if (ok) return "Force-stopped $pkg (user=$userId) via Shizuku"
        }

        val result = PrivilegeBackend.execShizukuOrRootShell(
            "am force-stop --user $userId ${PrivilegeBackend.shellQuote(pkg)}",
        )
        return if (result.isSuccess) {
            "Force-stopped $pkg (user=$userId)"
        } else {
            "Failed to force-stop $pkg: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Kill background processes associated with a package.")
    suspend fun killBackgroundProcesses(
        @LLMDescription("Target application package name.") packageName: String,
        @LLMDescription("Target user ID (default 0).") userId: Int = 0,
    ): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "packageName must not be blank"

        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.killBackgroundProcesses(pkg, userId).getOrDefault(false)
            if (ok) return "Killed background processes for $pkg (user=$userId) via Shizuku"
        }

        val result = PrivilegeBackend.execShizukuOrRootShell(
            "am kill --user $userId ${PrivilegeBackend.shellQuote(pkg)}",
        )
        return if (result.isSuccess) {
            "Killed background processes for $pkg (user=$userId)"
        } else {
            "Failed to kill background processes for $pkg: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("List running app processes with PID, UID, and process name.")
    suspend fun getRunningProcesses(
        @LLMDescription("Maximum number of process entries to return (default 40).") limit: Int = 40,
    ): String {
        val safeLimit = limit.coerceIn(1, 200)
        if (PrivilegeBackend.isShizukuReady()) {
            val processes = ShizukuManager.getRunningAppProcesses().getOrNull()
            if (processes != null) {
                if (processes.isEmpty()) return "No running app processes reported"
                return processes.take(safeLimit).joinToString("\n") { p ->
                    "pid=${p.pid} uid=${p.uid} importance=${p.importance} name=${p.processName} pkgs=${p.packages.joinToString(",")}"
                }
            }
        }

        val result = PrivilegeBackend.execShizukuOrRootShell(
            "ps -A -o PID,UID,NAME | head -n ${safeLimit + 1}",
        )
        return if (result.isSuccess) {
            result.stdout.trim()
        } else {
            "Failed to list processes: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }
}
