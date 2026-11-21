package top.aaa000111.maid.androidtool

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Display
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.aaa000111.maid.accessibility.MaidAccessibilityService
import top.aaa000111.maid.shizuku.ShellCommandExecutor
import top.aaa000111.maid.shizuku.ShizukuManager
import top.aaa000111.maid.shizuku.ShizukuStatus
import java.io.File
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * 探测到的 Root 管理器类型。
 */
enum class RootManagerType {
    KERNEL_SU,
    APATCH,
    MAGISK,
}

/**
 * 已安装的 Root 管理器信息。
 */
data class RootManagerInfo(
    val packageName: String,
    val displayName: String,
    val type: RootManagerType,
) {
    /** KernelSU / APatch 采用白名单主动授权模型，不支持通过执行 `su` 触发运行时弹窗。 */
    val requiresManualWhitelistGrant: Boolean
        get() = type == RootManagerType.KERNEL_SU || type == RootManagerType.APATCH
}

/**
 * 单次提权/授权操作的结果状态。
 */
enum class PrivilegeRequestStatus {
    GRANTED,
    DENIED,
    OPENED_SYSTEM_SETTINGS,
    OPENED_EXTERNAL_APP,
    NOT_INSTALLED,
    UNAVAILABLE,
}

/**
 * 提权入口函数的统一返回结果。
 */
data class PrivilegeRequestResult(
    val privilege: AndroidPrivilege,
    val status: PrivilegeRequestStatus,
    val message: String,
) {
    val isGranted: Boolean
        get() = status == PrivilegeRequestStatus.GRANTED
}

/**
 * 当前设备与应用的综合权限状态快照。
 */
data class PrivilegeOverview(
    val shizukuStatus: ShizukuStatus = ShizukuStatus(),
    val dhizukuInstalledOrActive: Boolean = false,
    val dhizukuGranted: Boolean = false,
    val dhizukuOwnerComponent: String? = null,
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val fileManagementGranted: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val writeSettingsGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val rootAvailable: Boolean = false,
    val suBinaryVisible: Boolean = false,
    val detectedRootManager: RootManagerInfo? = null,
    val availablePrivileges: Set<AndroidPrivilege> = emptySet(),
    val lastCheckedAtMillis: Long = 0L,
)

/**
 * Android 权限状态监测、提权入口函数与截屏能力的统一门面（全局单例）。
 *
 * 对外提供：
 * - [requestShizukuPermission]：申请 Shizuku 权限或拉起 Shizuku 管理端
 * - [requestDhizukuPermission]：申请 Dhizuku Device Owner 代理权限
 * - [requestAccessibilityPermission]：跳转系统无障碍设置页引导开启服务
 * - [requestRootPermission]：兼容 Magisk（`su` 弹窗）与 KernelSU/APatch（白名单识别与引导）的 Root 提权入口
 * - [requestPrivilege]：按 [AndroidPrivilege] 统一分发提权入口
 * - [captureScreenshot]：按 `Accessibility -> Shizuku -> Root` 优先级跨权限截屏
 */
object PrivilegeManager {

    private val KNOWN_ROOT_MANAGERS = listOf(
        RootManagerInfo("me.weishu.kernelsu", "KernelSU", RootManagerType.KERNEL_SU),
        RootManagerInfo("com.rifsxd.ksunext", "KernelSU Next", RootManagerType.KERNEL_SU),
        RootManagerInfo("com.sukisu.ultra", "SukiSU Ultra", RootManagerType.KERNEL_SU),
        RootManagerInfo("com.resukisu.resukisu", "ReSukiSU", RootManagerType.KERNEL_SU),
        RootManagerInfo("me.bmax.apatch", "APatch", RootManagerType.APATCH),
        RootManagerInfo("com.topjohnwu.magisk", "Magisk", RootManagerType.MAGISK),
    )

    private val SU_CANDIDATE_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/vendor/bin/su",
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var privilegeChecker: DefaultAndroidPrivilegeChecker? = null

    private val _overview = MutableStateFlow(
        PrivilegeOverview(shizukuStatus = ShizukuManager.status.value),
    )
    val overview: StateFlow<PrivilegeOverview> = _overview.asStateFlow()

    val isInitialized: Boolean
        get() = appContext != null

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext == null) {
                val ctx = context.applicationContext
                appContext = ctx
                privilegeChecker = DefaultAndroidPrivilegeChecker(
                    context = ctx,
                    checkRoot = true,
                )
                _overview.value = _overview.value.copy(shizukuStatus = ShizukuManager.status.value)
            }
        }
    }

    private fun requireContext(): Context {
        return appContext ?: error("PrivilegeManager must be initialized via init(context) first")
    }

    private fun requireChecker(): DefaultAndroidPrivilegeChecker {
        return privilegeChecker ?: error("PrivilegeManager must be initialized via init(context) first")
    }

    /**
     * 探测设备中已安装的 Root 管理器（KernelSU / KernelSU Next / SukiSU / ReSukiSU / APatch / Magisk）。
     */
    fun detectRootManager(): RootManagerInfo? {
        val pm = requireContext().packageManager
        return KNOWN_ROOT_MANAGERS.firstOrNull { candidate ->
            runCatching {
                pm.getPackageInfo(candidate.packageName, 0)
                true
            }.getOrDefault(false)
        }
    }

    /**
     * 检查当前应用进程是否能看到 `su` 可执行文件。
     * 注意：KernelSU 在未授权前会在内核层对应用隐藏 `su`，因此未授权时通常返回 `false`。
     */
    fun isSuBinaryVisible(): Boolean {
        if (SU_CANDIDATE_PATHS.any { File(it).exists() }) return true
        val pathEnv = System.getenv("PATH").orEmpty()
        return pathEnv.split(':')
            .asSequence()
            .filter { it.isNotBlank() }
            .any { dir -> File(dir, "su").exists() }
    }

    /**
     * 刷新全部权限持有状态。
     */
    suspend fun refreshOverview(checkRoot: Boolean = true): PrivilegeOverview =
        withContext(Dispatchers.IO) {
            val ctx = requireContext()
            val shizuku = ShizukuManager.refreshStatus()
            val dhizukuActive = runCatching { Dhizuku.init(ctx) }.getOrDefault(false)
            val dhizukuGranted = dhizukuActive &&
                runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
            val ownerComponent = if (dhizukuActive) {
                runCatching { Dhizuku.getOwnerComponent(ctx)?.flattenToShortString() }.getOrNull()
            } else {
                null
            }
            val checker = requireChecker()
            val accessibilityEnabled = checker.isAccessibilityEnabled()
            val accessibilityConnected = (
                MaidAccessibilityService.isConnected.value ||
                    MaidAccessibilityService.instance != null
                )
            val fileManagementGranted = checker.isFileManagementAvailable()
            val usageStatsGranted = checker.isUsageStatsAvailable()
            val writeSettingsGranted = checker.isWriteSettingsAvailable()
            val overlayGranted = checker.isOverlayAvailable()
            val rootManager = detectRootManager()
            val suVisible = isSuBinaryVisible()
            val rootOk = if (checkRoot) PrivilegeBackend.isRootAvailable() else _overview.value.rootAvailable

            val privileges = buildSet {
                if (shizuku.isReady) {
                    add(AndroidPrivilege.SHIZUKU)
                    if (shizuku.serverInfo?.isRoot == true) {
                        add(AndroidPrivilege.ROOT_SHIZUKU)
                    }
                }
                if (dhizukuGranted) {
                    add(AndroidPrivilege.DHIZUKU)
                }
                if (accessibilityEnabled || accessibilityConnected) {
                    add(AndroidPrivilege.ACCESSIBILITY)
                }
                if (fileManagementGranted) {
                    add(AndroidPrivilege.FILE_MANAGEMENT)
                }
                if (usageStatsGranted) {
                    add(AndroidPrivilege.USAGE_STATS)
                }
                if (writeSettingsGranted) {
                    add(AndroidPrivilege.WRITE_SETTINGS)
                }
                if (overlayGranted) {
                    add(AndroidPrivilege.SYSTEM_ALERT_WINDOW)
                }
                if (rootOk) {
                    add(AndroidPrivilege.ROOT)
                }
            }

            PrivilegeOverview(
                shizukuStatus = shizuku,
                dhizukuInstalledOrActive = dhizukuActive,
                dhizukuGranted = dhizukuGranted,
                dhizukuOwnerComponent = ownerComponent,
                accessibilityEnabled = accessibilityEnabled,
                accessibilityConnected = accessibilityConnected,
                fileManagementGranted = fileManagementGranted,
                usageStatsGranted = usageStatsGranted,
                writeSettingsGranted = writeSettingsGranted,
                overlayGranted = overlayGranted,
                rootAvailable = rootOk,
                suBinaryVisible = suVisible,
                detectedRootManager = rootManager,
                availablePrivileges = privileges,
                lastCheckedAtMillis = System.currentTimeMillis(),
            ).also { _overview.value = it }
        }

    /**
     * 统一提权入口：根据目标 [AndroidPrivilege] 调用对应的授权/引导逻辑。
     */
    suspend fun requestPrivilege(privilege: AndroidPrivilege): PrivilegeRequestResult {
        return when (privilege) {
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT_SHIZUKU,
            -> requestShizukuPermission()

            AndroidPrivilege.DHIZUKU -> requestDhizukuPermission()
            AndroidPrivilege.ACCESSIBILITY -> requestAccessibilityPermission()
            AndroidPrivilege.ROOT -> requestRootPermission()
            AndroidPrivilege.FILE_MANAGEMENT -> requestFileManagementPermission()
            AndroidPrivilege.USAGE_STATS -> requestUsageStatsPermission()
            AndroidPrivilege.WRITE_SETTINGS -> requestWriteSettingsPermission()
            AndroidPrivilege.SYSTEM_ALERT_WINDOW -> requestOverlayPermission()
        }
    }

    /**
     * Shizuku 提权入口：
     * - 服务已运行且未授权时弹出 Shizuku 授权框并挂起等待结果；
     * - 服务未运行但已安装 Shizuku 时尝试打开 Shizuku 应用；
     * - 未安装 Shizuku 时返回 [PrivilegeRequestStatus.NOT_INSTALLED]。
     */
    suspend fun requestShizukuPermission(
        timeoutMillis: Long = 60_000L,
    ): PrivilegeRequestResult {
        val status = withContext(Dispatchers.IO) { ShizukuManager.refreshStatus() }
        if (status.isReady) {
            refreshOverview(checkRoot = false)
            val modeLabel = if (status.serverInfo?.isRoot == true) "Root 模式" else "ADB 模式"
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.SHIZUKU,
                status = PrivilegeRequestStatus.GRANTED,
                message = "Shizuku 已授权（$modeLabel）",
            )
        }

        if (status.isBinderAlive) {
            val granted = ShizukuManager.awaitPermission(timeoutMillis = timeoutMillis)
            val updated = withContext(Dispatchers.IO) { ShizukuManager.refreshStatus() }
            refreshOverview(checkRoot = false)
            return if (granted || updated.isReady) {
                val modeLabel = if (updated.serverInfo?.isRoot == true) "Root 模式" else "ADB 模式"
                PrivilegeRequestResult(
                    privilege = AndroidPrivilege.SHIZUKU,
                    status = PrivilegeRequestStatus.GRANTED,
                    message = "Shizuku 授权成功（$modeLabel）",
                )
            } else {
                PrivilegeRequestResult(
                    privilege = AndroidPrivilege.SHIZUKU,
                    status = PrivilegeRequestStatus.DENIED,
                    message = "Shizuku 授权被拒绝或等待超时",
                )
            }
        }

        if (status.isManagerInstalled) {
            val opened = ShizukuManager.openShizukuApp()
            return if (opened) {
                PrivilegeRequestResult(
                    privilege = AndroidPrivilege.SHIZUKU,
                    status = PrivilegeRequestStatus.OPENED_EXTERNAL_APP,
                    message = "Shizuku 服务未启动，已打开 Shizuku 应用，请先启动服务后返回",
                )
            } else {
                PrivilegeRequestResult(
                    privilege = AndroidPrivilege.SHIZUKU,
                    status = PrivilegeRequestStatus.UNAVAILABLE,
                    message = "Shizuku 服务未启动，且无法自动打开 Shizuku 应用",
                )
            }
        }

        return PrivilegeRequestResult(
            privilege = AndroidPrivilege.SHIZUKU,
            status = PrivilegeRequestStatus.NOT_INSTALLED,
            message = "未检测到 Shizuku，请先安装并启动 Shizuku",
        )
    }

    /**
     * Dhizuku 提权入口：初始化 Dhizuku 服务端并在未授权时拉起授权页面等待结果。
     */
    suspend fun requestDhizukuPermission(
        timeoutMillis: Long = 20_000L,
    ): PrivilegeRequestResult {
        val ctx = requireContext()
        val initialized = withContext(Dispatchers.IO) {
            runCatching { Dhizuku.init(ctx) }.getOrDefault(false)
        }
        if (!initialized) {
            refreshOverview(checkRoot = false)
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.DHIZUKU,
                status = PrivilegeRequestStatus.UNAVAILABLE,
                message = "Dhizuku 未安装或尚未激活 Device Owner / Profile Owner",
            )
        }

        val alreadyGranted = withContext(Dispatchers.IO) {
            runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
        }
        if (alreadyGranted) {
            refreshOverview(checkRoot = false)
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.DHIZUKU,
                status = PrivilegeRequestStatus.GRANTED,
                message = "Dhizuku 权限已就绪",
            )
        }

        val granted = withTimeoutOrNull(timeoutMillis.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    Dhizuku.requestPermission(
                        object : DhizukuRequestPermissionListener() {
                            override fun onRequestPermission(grantResult: Int) {
                                if (continuation.isActive) {
                                    continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                                }
                            }
                        },
                    )
                }.onFailure {
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        } ?: withContext(Dispatchers.IO) {
            runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
        }

        refreshOverview(checkRoot = false)
        return if (granted) {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.DHIZUKU,
                status = PrivilegeRequestStatus.GRANTED,
                message = "Dhizuku 授权成功",
            )
        } else {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.DHIZUKU,
                status = PrivilegeRequestStatus.DENIED,
                message = "Dhizuku 授权未通过或已取消",
            )
        }
    }

    /**
     * 无障碍提权入口：跳转系统无障碍设置页引导开启「Maid 无障碍服务」。
     */
    suspend fun requestAccessibilityPermission(): PrivilegeRequestResult {
        val current = refreshOverview(checkRoot = false)
        if (current.accessibilityConnected || current.accessibilityEnabled) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.ACCESSIBILITY,
                status = PrivilegeRequestStatus.GRANTED,
                message = "无障碍服务已开启",
            )
        }

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            requireContext().startActivity(intent)
            true
        }.getOrDefault(false)

        return if (launched) {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.ACCESSIBILITY,
                status = PrivilegeRequestStatus.OPENED_SYSTEM_SETTINGS,
                message = "已打开系统无障碍设置，请开启「Maid 无障碍服务」",
            )
        } else {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.ACCESSIBILITY,
                status = PrivilegeRequestStatus.UNAVAILABLE,
                message = "无法打开系统无障碍设置页",
            )
        }
    }

    /**
     * Root（`su`）提权入口（兼容 Magisk 与 KernelSU / APatch）：
     *
     * 1. 先执行 `su -c id` 检测是否已获授权（在 Magisk 上若尚未决定则会触发弹窗）。
     * 2. 若未获授权且检测到 KernelSU / APatch（白名单主动授权模型，不会因调用 `su` 而弹窗）：
     *    直接返回提示说明，引导用户自行前往管理器的「超级用户」列表中为 Maid 开启权限。
     */
    suspend fun requestRootPermission(
        timeoutMillis: Long = 10_000L,
    ): PrivilegeRequestResult {
        val result = withContext(Dispatchers.IO) {
            ShellCommandExecutor.executeCommand(
                command = arrayOf("su", "-c", "id"),
                timeoutMillis = timeoutMillis,
            )
        }
        val rootGranted = result.isSuccess && result.stdout.contains("uid=0")
        val updatedOverview = refreshOverview(checkRoot = true)

        if (rootGranted || updatedOverview.rootAvailable) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.ROOT,
                status = PrivilegeRequestStatus.GRANTED,
                message = "已获取 Root (su) 权限",
            )
        }

        val manager = updatedOverview.detectedRootManager
        if (manager != null && manager.requiresManualWhitelistGrant) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.ROOT,
                status = PrivilegeRequestStatus.UNAVAILABLE,
                message = "${manager.displayName} 采用白名单授权，不支持应用弹窗；请自行在 ${manager.displayName}「超级用户」中为 Maid 开启 Root 权限",
            )
        }

        if (manager?.type == RootManagerType.MAGISK) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.ROOT,
                status = PrivilegeRequestStatus.DENIED,
                message = "Magisk 未授予 Root 权限，请在 Magisk「超级用户」中确认授权",
            )
        }

        if (!updatedOverview.suBinaryVisible) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.ROOT,
                status = PrivilegeRequestStatus.UNAVAILABLE,
                message = "当前进程不可见 su（若使用 KernelSU / APatch 或自定义包名管理器，请先在管理器「超级用户」中为 Maid 开启权限；若使用 Sui，也可直接通过 Shizuku 卡片授权）",
            )
        }

        val detail = result.stderr.ifBlank { result.errorMessage.orEmpty() }
        return PrivilegeRequestResult(
            privilege = AndroidPrivilege.ROOT,
            status = PrivilegeRequestStatus.DENIED,
            message = if (detail.isNotBlank()) {
                "未能获取 Root 权限：$detail"
            } else {
                "当前设备未 Root 或授权被拒绝"
            },
        )
    }

    /**
     * 普通 Android 所有文件访问权限（`MANAGE_EXTERNAL_STORAGE`）授权入口。
     */
    suspend fun requestFileManagementPermission(): PrivilegeRequestResult {
        val current = refreshOverview(checkRoot = false)
        if (current.fileManagementGranted) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.FILE_MANAGEMENT,
                status = PrivilegeRequestStatus.GRANTED,
                message = "所有文件访问权限已开启",
            )
        }
        val ctx = requireContext()
        val pkgUri = Uri.parse("package:${ctx.packageName}")
        val launched = launchSettingsIntentWithFallback(
            primaryAction = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            primaryData = pkgUri,
            fallbackAction = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
        )
        return if (launched) {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.FILE_MANAGEMENT,
                status = PrivilegeRequestStatus.OPENED_SYSTEM_SETTINGS,
                message = "已打开「所有文件访问权限」设置页，请为 Maid 开启授权",
            )
        } else {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.FILE_MANAGEMENT,
                status = PrivilegeRequestStatus.UNAVAILABLE,
                message = "无法打开所有文件访问权限设置页",
            )
        }
    }

    /**
     * 普通 Android 使用情况访问权限（`PACKAGE_USAGE_STATS`）授权入口。
     */
    suspend fun requestUsageStatsPermission(): PrivilegeRequestResult {
        val current = refreshOverview(checkRoot = false)
        if (current.usageStatsGranted) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.USAGE_STATS,
                status = PrivilegeRequestStatus.GRANTED,
                message = "应用使用情况访问权限已开启",
            )
        }
        val ctx = requireContext()
        val pkgUri = Uri.parse("package:${ctx.packageName}")
        val launched = launchSettingsIntentWithFallback(
            primaryAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
            primaryData = pkgUri,
            fallbackAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
        )
        return if (launched) {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.USAGE_STATS,
                status = PrivilegeRequestStatus.OPENED_SYSTEM_SETTINGS,
                message = "已打开「使用情况访问权限」设置页，请为 Maid 开启授权",
            )
        } else {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.USAGE_STATS,
                status = PrivilegeRequestStatus.UNAVAILABLE,
                message = "无法打开使用情况访问权限设置页",
            )
        }
    }

    /**
     * 普通 Android 修改系统设置权限（`WRITE_SETTINGS`）授权入口。
     */
    suspend fun requestWriteSettingsPermission(): PrivilegeRequestResult {
        val current = refreshOverview(checkRoot = false)
        if (current.writeSettingsGranted) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.WRITE_SETTINGS,
                status = PrivilegeRequestStatus.GRANTED,
                message = "修改系统设置权限已开启",
            )
        }
        val ctx = requireContext()
        val pkgUri = Uri.parse("package:${ctx.packageName}")
        val launched = launchSettingsIntentWithFallback(
            primaryAction = Settings.ACTION_MANAGE_WRITE_SETTINGS,
            primaryData = pkgUri,
            fallbackAction = Settings.ACTION_MANAGE_WRITE_SETTINGS,
        )
        return if (launched) {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.WRITE_SETTINGS,
                status = PrivilegeRequestStatus.OPENED_SYSTEM_SETTINGS,
                message = "已打开「修改系统设置」授权页，请为 Maid 开启允许修改系统设置",
            )
        } else {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.WRITE_SETTINGS,
                status = PrivilegeRequestStatus.UNAVAILABLE,
                message = "无法打开修改系统设置授权页",
            )
        }
    }

    /**
     * 普通 Android 悬浮窗权限（`SYSTEM_ALERT_WINDOW`）授权入口。
     */
    suspend fun requestOverlayPermission(): PrivilegeRequestResult {
        val current = refreshOverview(checkRoot = false)
        if (current.overlayGranted) {
            return PrivilegeRequestResult(
                privilege = AndroidPrivilege.SYSTEM_ALERT_WINDOW,
                status = PrivilegeRequestStatus.GRANTED,
                message = "悬浮窗权限已开启",
            )
        }
        val ctx = requireContext()
        val pkgUri = Uri.parse("package:${ctx.packageName}")
        val launched = launchSettingsIntentWithFallback(
            primaryAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            primaryData = pkgUri,
            fallbackAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        )
        return if (launched) {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.SYSTEM_ALERT_WINDOW,
                status = PrivilegeRequestStatus.OPENED_SYSTEM_SETTINGS,
                message = "已打开「显示在其他应用上层」设置页，请为 Maid 开启授权",
            )
        } else {
            PrivilegeRequestResult(
                privilege = AndroidPrivilege.SYSTEM_ALERT_WINDOW,
                status = PrivilegeRequestStatus.UNAVAILABLE,
                message = "无法打开悬浮窗权限设置页",
            )
        }
    }

    private fun launchSettingsIntentWithFallback(
        primaryAction: String,
        primaryData: Uri?,
        fallbackAction: String,
    ): Boolean {
        val ctx = requireContext()
        val primaryLaunched = runCatching {
            val intent = Intent(primaryAction).apply {
                if (primaryData != null) {
                    data = primaryData
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
        if (primaryLaunched) return true

        return runCatching {
            val fallbackIntent = Intent(fallbackAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(fallbackIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * 执行屏幕截图，结果写入应用私有缓存 `cacheDir/screenshots/`。
     */
    suspend fun captureScreenshot(
        displayId: Int = Display.DEFAULT_DISPLAY,
        format: String = "jpeg",
        maxDimension: Int = 1280,
        quality: Int = 85,
    ): Result<ScreenCaptureResult> {
        return PrivilegeBackend.captureScreenshot(
            context = requireContext(),
            displayId = displayId,
            format = format,
            maxDimension = maxDimension,
            quality = quality,
        )
    }
}
