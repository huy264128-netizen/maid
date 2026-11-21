package top.aaa000111.maid.androidtool

import android.content.Context
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import com.rosan.dhizuku.api.Dhizuku

/**
 * 设备管理员（Device Owner / Profile Owner）策略工具集，基于 Dhizuku 权限提供应用隐藏、冻结、防卸载与锁屏能力。
 *
 * 支持权限：`DHIZUKU`。
 */
@Suppress("unused")
class DevicePolicyToolSet(
    context: Context,
) : AndroidToolSet() {

    private val appContext by lazy { context.applicationContext }

    override val name: String = "device_policy"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(AndroidPrivilege.DHIZUKU)

    @Tool
    @LLMDescription("Inspect Dhizuku server version, owner component, and delegated scopes.")
    fun getDhizukuInfo(): String {
        val ctx = DhizukuDevicePolicyHelper.acquire(appContext).getOrElse { error ->
            return "Dhizuku unavailable: ${error.message}"
        }
        val versionCode = runCatching { Dhizuku.getVersionCode() }.getOrDefault(-1)
        val versionName = runCatching { Dhizuku.getVersionName() }.getOrDefault("unknown")
        val scopes = runCatching { Dhizuku.getDelegatedScopes()?.joinToString(",") }.getOrNull().orEmpty()
        return "owner=${ctx.ownerComponent.flattenToShortString()} " +
            "serverVersion=$versionName($versionCode) " +
            "delegatedScopes=${scopes.ifBlank { "default" }}"
    }

    @Tool
    @LLMDescription("Hide or unhide an installed application package via DevicePolicyManager.")
    fun setApplicationHidden(
        @LLMDescription("Target package name.") packageName: String,
        @LLMDescription("True to hide, false to unhide.") hidden: Boolean,
    ): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "packageName must not be blank"

        val ctx = DhizukuDevicePolicyHelper.acquire(appContext).getOrElse { error ->
            return "Dhizuku unavailable: ${error.message}"
        }
        return runCatching {
            val changed = ctx.devicePolicyManager.setApplicationHidden(ctx.ownerComponent, pkg, hidden)
            "setApplicationHidden($pkg, hidden=$hidden) -> changed=$changed"
        }.getOrElse { error ->
            "Failed setApplicationHidden($pkg): ${error.message}"
        }
    }

    @Tool
    @LLMDescription("Check whether an application package is currently hidden.")
    fun isApplicationHidden(
        @LLMDescription("Target package name.") packageName: String,
    ): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "packageName must not be blank"

        val ctx = DhizukuDevicePolicyHelper.acquire(appContext).getOrElse { error ->
            return "Dhizuku unavailable: ${error.message}"
        }
        return runCatching {
            val hidden = ctx.devicePolicyManager.isApplicationHidden(ctx.ownerComponent, pkg)
            "$pkg hidden=$hidden"
        }.getOrElse { error ->
            "Failed isApplicationHidden($pkg): ${error.message}"
        }
    }

    @Tool
    @LLMDescription("Freeze (suspend) or unfreeze one or more packages (comma-separated) via DevicePolicyManager.")
    fun setPackagesSuspended(
        @LLMDescription("Comma-separated package names.") packageNamesCsv: String,
        @LLMDescription("True to suspend/freeze, false to unsuspend.") suspended: Boolean,
    ): String {
        val packages = packageNamesCsv.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
        if (packages.isEmpty()) return "At least one package name must be provided"

        val ctx = DhizukuDevicePolicyHelper.acquire(appContext).getOrElse { error ->
            return "Dhizuku unavailable: ${error.message}"
        }
        return runCatching {
            val failed = ctx.devicePolicyManager.setPackagesSuspended(ctx.ownerComponent, packages, suspended)
            if (failed.isEmpty()) {
                "setPackagesSuspended(${packages.joinToString(",")}, suspended=$suspended) -> all succeeded"
            } else {
                "setPackagesSuspended partial failure; failed=${failed.joinToString(",")}"
            }
        }.getOrElse { error ->
            "Failed setPackagesSuspended: ${error.message}"
        }
    }

    @Tool
    @LLMDescription("Check whether a package is currently suspended (frozen).")
    fun isPackageSuspended(
        @LLMDescription("Target package name.") packageName: String,
    ): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "packageName must not be blank"

        val ctx = DhizukuDevicePolicyHelper.acquire(appContext).getOrElse { error ->
            return "Dhizuku unavailable: ${error.message}"
        }
        return runCatching {
            val suspended = ctx.devicePolicyManager.isPackageSuspended(ctx.ownerComponent, pkg)
            "$pkg suspended=$suspended"
        }.getOrElse { error ->
            "Failed isPackageSuspended($pkg): ${error.message}"
        }
    }

    @Tool
    @LLMDescription("Block or allow uninstallation of a target package via DevicePolicyManager.")
    fun setUninstallBlocked(
        @LLMDescription("Target package name.") packageName: String,
        @LLMDescription("True to block uninstallation, false to allow.") blocked: Boolean,
    ): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "packageName must not be blank"

        val ctx = DhizukuDevicePolicyHelper.acquire(appContext).getOrElse { error ->
            return "Dhizuku unavailable: ${error.message}"
        }
        return runCatching {
            ctx.devicePolicyManager.setUninstallBlocked(ctx.ownerComponent, pkg, blocked)
            "setUninstallBlocked($pkg, blocked=$blocked) -> ok"
        }.getOrElse { error ->
            "Failed setUninstallBlocked($pkg): ${error.message}"
        }
    }

    @Tool
    @LLMDescription("Check whether uninstallation is blocked for a package.")
    fun isUninstallBlocked(
        @LLMDescription("Target package name.") packageName: String,
    ): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "packageName must not be blank"

        val ctx = DhizukuDevicePolicyHelper.acquire(appContext).getOrElse { error ->
            return "Dhizuku unavailable: ${error.message}"
        }
        return runCatching {
            val blocked = ctx.devicePolicyManager.isUninstallBlocked(ctx.ownerComponent, pkg)
            "$pkg uninstallBlocked=$blocked"
        }.getOrElse { error ->
            "Failed isUninstallBlocked($pkg): ${error.message}"
        }
    }

    @Tool
    @LLMDescription("Immediately lock the device screen via DevicePolicyManager.")
    fun lockScreenNow(): String {
        val ctx = DhizukuDevicePolicyHelper.acquire(appContext).getOrElse { error ->
            return "Dhizuku unavailable: ${error.message}"
        }
        return runCatching {
            ctx.devicePolicyManager.lockNow()
            "Device locked via Dhizuku"
        }.getOrElse { error ->
            "Failed to lock screen: ${error.message}"
        }
    }
}
