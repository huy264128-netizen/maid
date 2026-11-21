package top.aaa000111.maid.androidtool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShizukuManager

/**
 * 通用 Shell 命令执行工具集：通过 Shizuku 或 Root 权限执行命令并返回完整标准输出、错误与退出码。
 *
 * 支持权限：`SHIZUKU`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class ShellToolSet : AndroidToolSet() {

    override val name: String = "shell"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.SHIZUKU,
        AndroidPrivilege.ROOT,
    )

    @Tool
    @LLMDescription("Execute a single shell command using Shizuku or Root and return exit code, stdout, and stderr.")
    suspend fun executeShell(
        @LLMDescription("Shell command string to run via /system/bin/sh -c.") command: String,
        @LLMDescription("Optional working directory path.") workingDir: String? = null,
        @LLMDescription("Command timeout in milliseconds (default 15000).") timeoutMs: Long = 15_000L,
        @LLMDescription("Prefer su (root) when available instead of Shizuku (default false).") preferRoot: Boolean = false,
    ): String {
        val safeTimeout = timeoutMs.coerceIn(500L, 120_000L)
        val useRootFirst = preferRoot && PrivilegeBackend.isRootAvailable()
        val backend = when {
            useRootFirst -> "root"
            PrivilegeBackend.isShizukuReady() -> "shizuku"
            PrivilegeBackend.isRootAvailable() -> "root"
            else -> return "No available privilege (Shizuku / Root) to execute shell command"
        }

        val result = if (backend == "root") {
            PrivilegeBackend.execRootShell(command, workingDir, safeTimeout)
        } else {
            ShizukuManager.execShell(command, workingDir = workingDir, timeoutMillis = safeTimeout)
        }
        return PrivilegeBackend.formatShellResult(result, backend)
    }
}
