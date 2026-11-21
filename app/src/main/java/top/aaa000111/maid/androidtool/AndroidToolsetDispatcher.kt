package top.aaa000111.maid.androidtool

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import com.rosan.dhizuku.api.Dhizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import top.aaa000111.maid.shizuku.ShellCommandExecutor
import top.aaa000111.maid.shizuku.ShizukuManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 负责检测当前应用实际持有的 [AndroidPrivilege] 集合。
 */
fun interface AndroidPrivilegeChecker {
    fun checkAvailablePrivileges(): Set<AndroidPrivilege>
}

/**
 * 默认的 Android 权限能力检测器，依次探测：
 * - [AndroidPrivilege.SHIZUKU] / [AndroidPrivilege.ROOT_SHIZUKU]
 * - [AndroidPrivilege.DHIZUKU]
 * - [AndroidPrivilege.ACCESSIBILITY]
 * - [AndroidPrivilege.ROOT]
 */
class DefaultAndroidPrivilegeChecker(
    context: Context,
    private val checkRoot: Boolean = true,
    private val rootTimeoutMillis: Long = 1_500L,
) : AndroidPrivilegeChecker {

    private val appContext = context.applicationContext

    override fun checkAvailablePrivileges(): Set<AndroidPrivilege> {
        return buildSet {
            detectShizukuPrivileges(this)
            if (isDhizukuAvailable()) {
                add(AndroidPrivilege.DHIZUKU)
            }
            if (isAccessibilityAvailable()) {
                add(AndroidPrivilege.ACCESSIBILITY)
            }
            if (isFileManagementAvailable()) {
                add(AndroidPrivilege.FILE_MANAGEMENT)
            }
            if (isUsageStatsAvailable()) {
                add(AndroidPrivilege.USAGE_STATS)
            }
            if (isWriteSettingsAvailable()) {
                add(AndroidPrivilege.WRITE_SETTINGS)
            }
            if (isOverlayAvailable()) {
                add(AndroidPrivilege.SYSTEM_ALERT_WINDOW)
            }
            if (checkRoot && isRootAvailable()) {
                add(AndroidPrivilege.ROOT)
            }
        }
    }

    private fun detectShizukuPrivileges(target: MutableSet<AndroidPrivilege>) {
        val status = ShizukuManager.refreshStatus()
        if (status.isReady) {
            target.add(AndroidPrivilege.SHIZUKU)
            if (status.serverInfo?.isRoot == true) {
                target.add(AndroidPrivilege.ROOT_SHIZUKU)
            }
        }
    }

    private fun isDhizukuAvailable(): Boolean {
        return runCatching {
            Dhizuku.init(appContext) && Dhizuku.isPermissionGranted()
        }.getOrDefault(false)
    }

    internal fun isAccessibilityEnabled(): Boolean = isAccessibilityAvailable()

    internal fun isFileManagementAvailable(): Boolean {
        return runCatching {
            Environment.isExternalStorageManager()
        }.getOrDefault(false)
    }

    internal fun isUsageStatsAvailable(): Boolean {
        return runCatching {
            val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    internal fun isWriteSettingsAvailable(): Boolean {
        return runCatching {
            Settings.System.canWrite(appContext)
        }.getOrDefault(false)
    }

    internal fun isOverlayAvailable(): Boolean {
        return runCatching {
            Settings.canDrawOverlays(appContext)
        }.getOrDefault(false)
    }

    private fun isAccessibilityAvailable(): Boolean {
        val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledServices = runCatching {
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrNull()

        if (enabledServices != null && enabledServices.any {
                it.resolveInfo?.serviceInfo?.packageName == appContext.packageName
            }
        ) {
            return true
        }

        val accessibilityEnabled = runCatching {
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            )
        }.getOrDefault(0)
        if (accessibilityEnabled != 1) return false

        val enabledSetting = runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()

        return enabledSetting.split(':').any { rawComponent ->
            ComponentName.unflattenFromString(rawComponent)?.packageName == appContext.packageName
        }
    }

    private fun isRootAvailable(): Boolean {
        val result = ShellCommandExecutor.executeCommand(
            command = arrayOf("su", "-c", "id"),
            timeoutMillis = rootTimeoutMillis,
        )
        return result.isSuccess && result.stdout.contains("uid=0")
    }
}

/**
 * 基于 Android 权限能力声明的 Koog ToolSet 调度器。
 *
 * 仅关注 Android 权限持有状态：从已注册的 [AndroidToolSet] 列表中筛选出
 * “当前应用至少持有其声明权限之一（或无需特殊权限）”的 ToolSet，并组装为 Koog [ToolRegistry]。
 */
class AndroidToolsetDispatcher(
    toolSets: Collection<AndroidToolSet> = emptyList(),
    private val privilegeChecker: AndroidPrivilegeChecker,
    private val json: Json = Json,
) {

    constructor(
        context: Context,
        toolSets: Collection<AndroidToolSet> = emptyList(),
        checkRoot: Boolean = true,
        json: Json = Json,
    ) : this(
        toolSets = toolSets,
        privilegeChecker = DefaultAndroidPrivilegeChecker(
            context = context,
            checkRoot = checkRoot,
        ),
        json = json,
    )

    private val registeredToolSets = CopyOnWriteArrayList(toolSets)

    fun register(toolSet: AndroidToolSet) {
        if (!registeredToolSets.contains(toolSet)) {
            registeredToolSets.add(toolSet)
        }
    }

    fun registerAll(toolSets: Collection<AndroidToolSet>) {
        toolSets.forEach(::register)
    }

    fun unregister(toolSet: AndroidToolSet) {
        registeredToolSets.remove(toolSet)
    }

    fun getRegisteredToolSets(): List<AndroidToolSet> = registeredToolSets.toList()

    /**
     * 检测当前可用权限。
     */
    fun availablePrivileges(): Set<AndroidPrivilege> = privilegeChecker.checkAvailablePrivileges()

    /**
     * 在给定可用权限下，返回允许使用的 [AndroidToolSet] 列表。
     */
    fun resolveAllowedToolSets(
        privileges: Set<AndroidPrivilege> = availablePrivileges(),
    ): List<AndroidToolSet> {
        return registeredToolSets.filter { it.isAllowed(privileges) }
    }

    /**
     * 根据当前可用权限筛选允许使用的 [AndroidToolSet]，并构建并返回 Koog [ToolRegistry]。
     */
    fun dispatch(
        privileges: Set<AndroidPrivilege> = availablePrivileges(),
    ): ToolRegistry {
        val allowed = resolveAllowedToolSets(privileges)
        if (allowed.isEmpty()) {
            return ToolRegistry.EMPTY
        }
        return ToolRegistry {
            for (toolSet in allowed) {
                tools(toolSet, json)
            }
        }
    }

    /**
     * 在 IO 调度器上执行权限探测并构建 Koog [ToolRegistry]。
     */
    suspend fun dispatchAsync(): ToolRegistry = withContext(Dispatchers.IO) {
        val privileges = availablePrivileges()
        dispatch(privileges)
    }

    /**
     * 便捷方法：直接返回当前允许注册的 Koog [Tool] 列表。
     */
    fun dispatchTools(
        privileges: Set<AndroidPrivilege> = availablePrivileges(),
    ): List<Tool<*, *>> = dispatch(privileges).tools
}
