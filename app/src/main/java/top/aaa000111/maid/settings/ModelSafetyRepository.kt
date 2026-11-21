package top.aaa000111.maid.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val Context.modelSafetyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "maid_model_safety_configs",
)

/**
 * 模型执行设备操作时的总体安全保护级别。
 */
enum class ModelSafetyLevel {
    /** 严格保护：拦截所有高危敏感工具及危险 Shell 命令，仅允许基础界面交互与只读查询。 */
    STRICT,

    /** 均衡保护（默认）：允许常规非破坏性 Shell 与应用控制，拦截 Root、设备策略、分辨率修改及高危命令。 */
    BALANCED,

    /** 完全信任：放行全部已授权的 Android 工具与命令（仅保留系统提示词提醒）。 */
    UNRESTRICTED,

    /** 自定义：用户独立调整了各项敏感操作开关。 */
    CUSTOM,
}

/**
 * 受安全策略管控的敏感 Android 工具类别。
 */
enum class SensitiveToolCategory {
    SHELL_EXECUTION,
    APP_LIFECYCLE_CONTROL,
    DISPLAY_MODIFICATION,
    DEVICE_POLICY_CONTROL,
    ROOT_OPERATIONS,
    LOCAL_LLM_API_KEY_ACCESS,
}

/**
 * 控制智能体（`DeviceActionAgent` / `ProotLinuxAgent` 等）调用敏感操作时的安全配置。
 */
data class ModelSafetyConfig(
    val allowShellExecution: Boolean = true,
    val allowAppLifecycleControl: Boolean = true,
    val allowDisplayModification: Boolean = false,
    val allowDevicePolicyControl: Boolean = false,
    val allowRootOperations: Boolean = false,
    val allowLocalLlmApiKeyAccess: Boolean = false,
    val blockDangerousShellCommands: Boolean = true,
) {
    val safetyLevel: ModelSafetyLevel
        get() = when (this) {
            STRICT -> ModelSafetyLevel.STRICT
            BALANCED -> ModelSafetyLevel.BALANCED
            UNRESTRICTED -> ModelSafetyLevel.UNRESTRICTED
            else -> ModelSafetyLevel.CUSTOM
        }

    val allowedSensitiveCategoryCount: Int
        get() = listOf(
            allowShellExecution,
            allowAppLifecycleControl,
            allowDisplayModification,
            allowDevicePolicyControl,
            allowRootOperations,
            allowLocalLlmApiKeyAccess,
        ).count { it }

    val hasHighRiskEnabled: Boolean
        get() = allowRootOperations ||
            allowDevicePolicyControl ||
            allowLocalLlmApiKeyAccess ||
            !blockDangerousShellCommands

    fun isCategoryAllowed(category: SensitiveToolCategory): Boolean = when (category) {
        SensitiveToolCategory.SHELL_EXECUTION -> allowShellExecution
        SensitiveToolCategory.APP_LIFECYCLE_CONTROL -> allowAppLifecycleControl
        SensitiveToolCategory.DISPLAY_MODIFICATION -> allowDisplayModification
        SensitiveToolCategory.DEVICE_POLICY_CONTROL -> allowDevicePolicyControl
        SensitiveToolCategory.ROOT_OPERATIONS -> allowRootOperations
        SensitiveToolCategory.LOCAL_LLM_API_KEY_ACCESS -> allowLocalLlmApiKeyAccess
    }

    fun isToolAllowed(toolName: String): Boolean {
        val category = categoryForTool(toolName) ?: return true
        return isCategoryAllowed(category)
    }

    /**
     * 评估一次工具调用是否符合当前安全配置。
     *
     * @return 若允许执行则返回 `null`；否则返回简明的拦截原因说明。
     */
    fun evaluateToolCall(
        toolName: String,
        argumentsJson: String = "",
    ): String? {
        val category = categoryForTool(toolName)
        if (category != null && !isCategoryAllowed(category)) {
            return "Blocked by Model Safety Policy (${safetyLevel.name}): " +
                "tool '$toolName' (${categoryLabel(category)}) is disabled in Settings -> 模型安全性."
        }

        if (blockDangerousShellCommands && isShellCommandTool(toolName)) {
            val commandText = extractCommandArgument(argumentsJson)
            val matchedDanger = findDangerousShellPattern(commandText)
            if (matchedDanger != null) {
                return "Blocked by Model Safety Policy (${safetyLevel.name}): " +
                    "dangerous shell command detected ($matchedDanger). " +
                    "Adjust Settings -> 模型安全性 if you intentionally trust this operation."
            }
        }

        return null
    }

    /**
     * 生成嵌入 `DeviceActionAgent` 系统提示词中的安全策略说明。
     */
    fun toPolicyPromptSection(): String {
        val allowed = mutableListOf<String>()
        val blocked = mutableListOf<String>()

        SensitiveToolCategory.entries.forEach { category ->
            val label = categoryLabel(category)
            if (isCategoryAllowed(category)) {
                allowed += label
            } else {
                blocked += label
            }
        }

        return buildString {
            appendLine("[模型安全策略：${safetyLevel.name}]")
            if (allowed.isNotEmpty()) {
                appendLine("- 当前允许的敏感操作类别：${allowed.joinToString("、")}")
            }
            if (blocked.isNotEmpty()) {
                appendLine("- 当前禁止的敏感操作类别：${blocked.joinToString("、")}（请勿尝试调用，若任务必需请如实告知用户前往「设置 -> 模型安全性」开启）")
            }
            if (blockDangerousShellCommands) {
                appendLine("- 高危 Shell 命令拦截已启用：禁止执行卸载/清除应用数据、删除系统或大量文件、重启关机、格式化分区等破坏性指令。")
            }
        }.trimEnd()
    }

    companion object {
        val STRICT = ModelSafetyConfig(
            allowShellExecution = false,
            allowAppLifecycleControl = false,
            allowDisplayModification = false,
            allowDevicePolicyControl = false,
            allowRootOperations = false,
            allowLocalLlmApiKeyAccess = false,
            blockDangerousShellCommands = true,
        )

        val BALANCED = ModelSafetyConfig(
            allowShellExecution = true,
            allowAppLifecycleControl = true,
            allowDisplayModification = false,
            allowDevicePolicyControl = false,
            allowRootOperations = false,
            allowLocalLlmApiKeyAccess = false,
            blockDangerousShellCommands = true,
        )

        val UNRESTRICTED = ModelSafetyConfig(
            allowShellExecution = true,
            allowAppLifecycleControl = true,
            allowDisplayModification = true,
            allowDevicePolicyControl = true,
            allowRootOperations = true,
            allowLocalLlmApiKeyAccess = true,
            blockDangerousShellCommands = false,
        )

        val DEFAULT = BALANCED

        private val jsonParser = Json { ignoreUnknownKeys = true }

        private val TOOL_CATEGORY_MAP: Map<String, SensitiveToolCategory> = mapOf(
            "executeShell" to SensitiveToolCategory.SHELL_EXECUTION,
            "forceStopPackage" to SensitiveToolCategory.APP_LIFECYCLE_CONTROL,
            "killBackgroundProcesses" to SensitiveToolCategory.APP_LIFECYCLE_CONTROL,
            "setDisplaySize" to SensitiveToolCategory.DISPLAY_MODIFICATION,
            "setDisplayDensity" to SensitiveToolCategory.DISPLAY_MODIFICATION,
            "setApplicationHidden" to SensitiveToolCategory.DEVICE_POLICY_CONTROL,
            "setPackagesSuspended" to SensitiveToolCategory.DEVICE_POLICY_CONTROL,
            "setUninstallBlocked" to SensitiveToolCategory.DEVICE_POLICY_CONTROL,
            "lockScreenNow" to SensitiveToolCategory.DEVICE_POLICY_CONTROL,
            "executeRootShell" to SensitiveToolCategory.ROOT_OPERATIONS,
            "setSystemProperty" to SensitiveToolCategory.ROOT_OPERATIONS,
            "readProtectedFile" to SensitiveToolCategory.ROOT_OPERATIONS,
            "executeProotLinuxTaskWithLlmApi" to SensitiveToolCategory.LOCAL_LLM_API_KEY_ACCESS,
        )

        private val DANGEROUS_SHELL_RULES: List<Pair<String, Regex>> = listOf(
            "recursive delete (rm -rf / rm -r)" to Regex(
                """(?i)(^|[;&|\s])rm\s+[^\n]*-[^\s]*r[^\s]*f|(^|[;&|\s])rm\s+[^\n]*-[^\s]*f[^\s]*r|(^|[;&|\s])rm\s+[^\n]*\s+/($|\s)""",
            ),
            "package uninstall / data wipe (pm uninstall / pm clear)" to Regex(
                """(?i)(^|[;&|\s])pm\s+(uninstall|clear|disable-user|disable)\b""",
            ),
            "device reboot / shutdown" to Regex(
                """(?i)(^|[;&|\s])(reboot|shutdown|poweroff|svc\s+power\s+(shutdown|reboot)|setprop\s+sys\.powerctl)\b""",
            ),
            "filesystem format / raw block write (mkfs / dd / wipefs)" to Regex(
                """(?i)(^|[;&|\s])(mkfs(\.[a-z0-9_]+)?|wipefs|dd\s+if=|format\s+/)""",
            ),
            "fork bomb" to Regex(""":\(\)\s*\{"""),
            "root permission mass override (chmod/chown 777 /)" to Regex(
                """(?i)(^|[;&|\s])(chmod|chown)\s+[^\n]*-R[^\n]*\s+/(\s|$)""",
            ),
            "locksettings or system settings wipe" to Regex(
                """(?i)(^|[;&|\s])(locksettings\s+clear|settings\s+delete)\b""",
            ),
        )

        fun fromLevel(level: ModelSafetyLevel): ModelSafetyConfig = when (level) {
            ModelSafetyLevel.STRICT -> STRICT
            ModelSafetyLevel.BALANCED,
            ModelSafetyLevel.CUSTOM -> BALANCED
            ModelSafetyLevel.UNRESTRICTED -> UNRESTRICTED
        }

        fun categoryForTool(toolName: String): SensitiveToolCategory? = TOOL_CATEGORY_MAP[toolName]

        fun categoryLabel(category: SensitiveToolCategory): String = when (category) {
            SensitiveToolCategory.SHELL_EXECUTION -> "通用 Shell 命令"
            SensitiveToolCategory.APP_LIFECYCLE_CONTROL -> "强停/清理后台应用"
            SensitiveToolCategory.DISPLAY_MODIFICATION -> "修改分辨率与 DPI"
            SensitiveToolCategory.DEVICE_POLICY_CONTROL -> "设备管理员高危策略（锁屏/隐藏/冻结）"
            SensitiveToolCategory.ROOT_OPERATIONS -> "Root 最高权限操作"
            SensitiveToolCategory.LOCAL_LLM_API_KEY_ACCESS -> "使用本机 LLM API Key"
        }

        fun isShellCommandTool(toolName: String): Boolean {
            return toolName == "executeShell" || toolName == "executeRootShell"
        }

        internal fun extractCommandArgument(argumentsJson: String): String {
            val trimmed = argumentsJson.trim()
            if (trimmed.isEmpty()) return ""
            val parsed = runCatching {
                jsonParser.parseToJsonElement(trimmed) as? JsonObject
            }.getOrNull()
            val commandField = parsed?.get("command")?.jsonPrimitive?.contentOrNull
            return commandField ?: trimmed
        }

        internal fun findDangerousShellPattern(commandText: String): String? {
            val normalized = commandText.trim()
            if (normalized.isEmpty()) return null
            return DANGEROUS_SHELL_RULES.firstOrNull { (_, regex) ->
                regex.containsMatchIn(normalized)
            }?.first
        }
    }
}

/**
 * 持久化与观察模型安全策略配置的全局单例仓库。
 */
object ModelSafetyRepository {

    private val KEY_ALLOW_SHELL = booleanPreferencesKey("allow_shell_execution")
    private val KEY_ALLOW_APP_LIFECYCLE = booleanPreferencesKey("allow_app_lifecycle_control")
    private val KEY_ALLOW_DISPLAY_MOD = booleanPreferencesKey("allow_display_modification")
    private val KEY_ALLOW_DEVICE_POLICY = booleanPreferencesKey("allow_device_policy_control")
    private val KEY_ALLOW_ROOT = booleanPreferencesKey("allow_root_operations")
    private val KEY_ALLOW_LOCAL_LLM_API_KEY = booleanPreferencesKey("allow_local_llm_api_key_access")
    private val KEY_BLOCK_DANGEROUS_SHELL = booleanPreferencesKey("block_dangerous_shell_commands")

    @Volatile
    private var appContext: Context? = null

    val isReady: Boolean
        get() = appContext != null

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }
    }

    private fun requireContext(): Context {
        return appContext ?: error("ModelSafetyRepository must be initialized via init(context) first")
    }

    fun observeSafetyConfig(): Flow<ModelSafetyConfig> {
        return requireContext().modelSafetyDataStore.data
            .map { prefs -> readFromPreferences(prefs) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun getSafetyConfig(): ModelSafetyConfig = withContext(Dispatchers.IO) {
        readFromPreferences(requireContext().modelSafetyDataStore.data.first())
    }

    suspend fun saveSafetyConfig(config: ModelSafetyConfig) {
        withContext(Dispatchers.IO) {
            requireContext().modelSafetyDataStore.edit { prefs ->
                prefs[KEY_ALLOW_SHELL] = config.allowShellExecution
                prefs[KEY_ALLOW_APP_LIFECYCLE] = config.allowAppLifecycleControl
                prefs[KEY_ALLOW_DISPLAY_MOD] = config.allowDisplayModification
                prefs[KEY_ALLOW_DEVICE_POLICY] = config.allowDevicePolicyControl
                prefs[KEY_ALLOW_ROOT] = config.allowRootOperations
                prefs[KEY_ALLOW_LOCAL_LLM_API_KEY] = config.allowLocalLlmApiKeyAccess
                prefs[KEY_BLOCK_DANGEROUS_SHELL] = config.blockDangerousShellCommands
            }
        }
    }

    suspend fun applySafetyLevel(level: ModelSafetyLevel) {
        saveSafetyConfig(ModelSafetyConfig.fromLevel(level))
    }

    suspend fun resetToDefault() {
        saveSafetyConfig(ModelSafetyConfig.DEFAULT)
    }

    private fun readFromPreferences(prefs: Preferences): ModelSafetyConfig {
        val defaults = ModelSafetyConfig.DEFAULT
        return ModelSafetyConfig(
            allowShellExecution = prefs[KEY_ALLOW_SHELL] ?: defaults.allowShellExecution,
            allowAppLifecycleControl = prefs[KEY_ALLOW_APP_LIFECYCLE] ?: defaults.allowAppLifecycleControl,
            allowDisplayModification = prefs[KEY_ALLOW_DISPLAY_MOD] ?: defaults.allowDisplayModification,
            allowDevicePolicyControl = prefs[KEY_ALLOW_DEVICE_POLICY] ?: defaults.allowDevicePolicyControl,
            allowRootOperations = prefs[KEY_ALLOW_ROOT] ?: defaults.allowRootOperations,
            allowLocalLlmApiKeyAccess = prefs[KEY_ALLOW_LOCAL_LLM_API_KEY] ?: defaults.allowLocalLlmApiKeyAccess,
            blockDangerousShellCommands = prefs[KEY_BLOCK_DANGEROUS_SHELL] ?: defaults.blockDangerousShellCommands,
        )
    }
}
