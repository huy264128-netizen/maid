package top.aaa000111.maid.androidtool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShizukuManager

/**
 * 窗口与显示管理工具集：查询屏幕信息、调整分辨率/DPI、冻结或恢复旋转及锁屏状态检查。
 *
 * 支持权限：`SHIZUKU`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class WindowToolSet : AndroidToolSet() {

    override val name: String = "window"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.SHIZUKU,
        AndroidPrivilege.ROOT,
    )

    @Tool
    @LLMDescription("Get display resolution, density DPI, rotation, and navigation bar info.")
    suspend fun getDisplayInfo(
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val info = ShizukuManager.getDisplayInfo(displayId).getOrNull()
            if (info != null) {
                return "displayId=${info.displayId} " +
                    "base=${info.baseWidth}x${info.baseHeight}@${info.baseDensityDpi}dpi " +
                    "initial=${info.initialWidth}x${info.initialHeight}@${info.initialDensityDpi}dpi " +
                    "rotation=${info.rotation} hasNavBar=${info.hasNavigationBar}"
            }
        }

        val result = PrivilegeBackend.execShizukuOrRootShell(
            "wm size -d $displayId; wm density -d $displayId",
        )
        return if (result.isSuccess) {
            result.stdout.trim()
        } else {
            "Failed to get display info: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Override the display resolution to width x height pixels.")
    suspend fun setDisplaySize(
        @LLMDescription("Width in pixels.") width: Int,
        @LLMDescription("Height in pixels.") height: Int,
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        if (width <= 0 || height <= 0) return "Width and height must be positive"
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.setForcedDisplaySize(width, height, displayId).getOrDefault(false)
            if (ok) return "Set display $displayId size to ${width}x${height} via Shizuku"
        }
        val result = PrivilegeBackend.execShizukuOrRootShell(
            "wm size ${width}x${height} -d $displayId",
        )
        return if (result.isSuccess) {
            "Set display $displayId size to ${width}x${height}"
        } else {
            "Failed to set display size: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Reset forced display resolution back to device default.")
    suspend fun clearDisplaySize(
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.clearForcedDisplaySize(displayId).getOrDefault(false)
            if (ok) return "Reset display $displayId size via Shizuku"
        }
        val result = PrivilegeBackend.execShizukuOrRootShell(
            "wm size reset -d $displayId",
        )
        return if (result.isSuccess) {
            "Reset display $displayId size"
        } else {
            "Failed to reset display size: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Override the display density DPI.")
    suspend fun setDisplayDensity(
        @LLMDescription("Density in DPI (e.g. 320, 420, 480).") densityDpi: Int,
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        if (densityDpi <= 0) return "densityDpi must be positive"
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.setForcedDisplayDensity(densityDpi, displayId).getOrDefault(false)
            if (ok) return "Set display $displayId density to $densityDpi via Shizuku"
        }
        val result = PrivilegeBackend.execShizukuOrRootShell(
            "wm density $densityDpi -d $displayId",
        )
        return if (result.isSuccess) {
            "Set display $displayId density to $densityDpi"
        } else {
            "Failed to set display density: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Reset forced display density DPI back to device default.")
    suspend fun clearDisplayDensity(
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.clearForcedDisplayDensity(displayId).getOrDefault(false)
            if (ok) return "Reset display $displayId density via Shizuku"
        }
        val result = PrivilegeBackend.execShizukuOrRootShell(
            "wm density reset -d $displayId",
        )
        return if (result.isSuccess) {
            "Reset display $displayId density"
        } else {
            "Failed to reset display density: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Freeze display rotation to a Surface rotation constant (0=0°, 1=90°, 2=180°, 3=270°).")
    suspend fun freezeRotation(
        @LLMDescription("Rotation index 0..3.") rotation: Int,
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        val safeRotation = rotation.coerceIn(0, 3)
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.freezeRotation(safeRotation, displayId).getOrDefault(false)
            if (ok) return "Froze display $displayId rotation to $safeRotation via Shizuku"
        }
        val result = PrivilegeBackend.execShizukuOrRootShell(
            "settings put system accelerometer_rotation 0 && settings put system user_rotation $safeRotation",
        )
        return if (result.isSuccess) {
            "Froze display rotation to $safeRotation"
        } else {
            "Failed to freeze rotation: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Unfreeze display rotation and restore automatic orientation.")
    suspend fun thawRotation(
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.thawRotation(displayId).getOrDefault(false)
            if (ok) return "Thawed display $displayId rotation via Shizuku"
        }
        val result = PrivilegeBackend.execShizukuOrRootShell(
            "settings put system accelerometer_rotation 1",
        )
        return if (result.isSuccess) {
            "Thawed display rotation"
        } else {
            "Failed to thaw rotation: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Check whether the device lockscreen / keyguard is currently locked.")
    suspend fun isKeyguardLocked(): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val locked = ShizukuManager.isKeyguardLocked().getOrNull()
            if (locked != null) return "keyguardLocked=$locked"
        }
        val result = PrivilegeBackend.execShizukuOrRootShell(
            "dumpsys window policy | grep -E 'mShowingLockscreen|isStatusBarKeyguard|showing=' | head -n 3",
        )
        return if (result.isSuccess && result.stdout.isNotBlank()) {
            result.stdout.trim()
        } else {
            "Unable to query keyguard state: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }
}
