package top.aaa000111.maid.androidtool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShizukuManager

/**
 * Root 级系统管理与特权操作工具集：仅当应用拥有 UID=0（Root 版 Shizuku 或 su）时加载。
 *
 * 支持权限：`ROOT_SHIZUKU`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class RootSystemToolSet : AndroidToolSet() {

    override val name: String = "root_system"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.ROOT_SHIZUKU,
        AndroidPrivilege.ROOT,
    )

    @Tool
    @LLMDescription("Execute a shell command strictly with UID 0 (root) privileges.")
    suspend fun executeRootShell(
        @LLMDescription("Shell command string to execute as root.") command: String,
        @LLMDescription("Optional working directory.") workingDir: String? = null,
        @LLMDescription("Command timeout in milliseconds (default 15000).") timeoutMs: Long = 15_000L,
    ): String {
        val safeTimeout = timeoutMs.coerceIn(500L, 120_000L)
        if (PrivilegeBackend.isRootShizukuReady()) {
            val result = ShizukuManager.execShell(
                shellCommand = command,
                workingDir = workingDir,
                timeoutMillis = safeTimeout,
            )
            return PrivilegeBackend.formatShellResult(result, "root_shizuku")
        }
        if (PrivilegeBackend.isRootAvailable()) {
            val result = PrivilegeBackend.execRootShell(command, workingDir, safeTimeout)
            return PrivilegeBackend.formatShellResult(result, "su")
        }
        return "No root privilege (ROOT_SHIZUKU / su) available"
    }

    @Tool
    @LLMDescription("Read an Android system property by key.")
    suspend fun getSystemProperty(
        @LLMDescription("System property key, e.g. 'ro.build.version.release'.") key: String,
        @LLMDescription("Default value if property is unset.") defaultValue: String = "",
    ): String {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) return "Property key must not be blank"

        if (PrivilegeBackend.isRootShizukuReady()) {
            val value = ShizukuManager.getSystemProperty(trimmedKey, defaultValue).orEmpty()
            return "$trimmedKey=$value"
        }

        val result = PrivilegeBackend.execRootShell("getprop ${PrivilegeBackend.shellQuote(trimmedKey)}")
        return if (result.isSuccess) {
            val value = result.stdout.trim().ifEmpty { defaultValue }
            "$trimmedKey=$value"
        } else {
            "Failed to read property $trimmedKey: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Set an Android system property using UID 0 (root) privileges.")
    suspend fun setSystemProperty(
        @LLMDescription("System property key.") key: String,
        @LLMDescription("Property value to assign.") value: String,
    ): String {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) return "Property key must not be blank"

        if (PrivilegeBackend.isRootShizukuReady()) {
            val ok = ShizukuManager.setSystemProperty(trimmedKey, value)
            if (ok) return "Set property $trimmedKey=$value via Root-Shizuku"
        }

        val result = PrivilegeBackend.execRootShell(
            "setprop ${PrivilegeBackend.shellQuote(trimmedKey)} ${PrivilegeBackend.shellQuote(value)}",
        )
        return if (result.isSuccess) {
            "Set property $trimmedKey=$value via su"
        } else {
            "Failed to set property $trimmedKey: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Read contents of a root-protected file path up to maxBytes.")
    suspend fun readProtectedFile(
        @LLMDescription("Absolute file path on device.") path: String,
        @LLMDescription("Maximum bytes to read (default 65536).") maxBytes: Int = 65_536,
    ): String {
        val trimmedPath = path.trim()
        if (trimmedPath.isEmpty()) return "File path must not be blank"
        val safeBytes = maxBytes.coerceIn(1, 256 * 1024)
        return executeRootShell("head -c $safeBytes ${PrivilegeBackend.shellQuote(trimmedPath)}")
    }
}
