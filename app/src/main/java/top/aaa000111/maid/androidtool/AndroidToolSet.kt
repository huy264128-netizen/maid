package top.aaa000111.maid.androidtool

import ai.koog.agents.core.tools.reflect.ToolSet

/**
 * Android 侧可用于执行自动化或系统操作的权限/能力类型（涵盖高权限与普通 Android 特殊权限）。
 */
enum class AndroidPrivilege {
    /** Shizuku 权限（ADB Shell 或 Root 均满足） */
    SHIZUKU,

    /** 以 Root 身份运行的 Shizuku 权限（uid == 0） */
    ROOT_SHIZUKU,

    /** Dhizuku 权限（Device Owner / Profile Owner） */
    DHIZUKU,

    /** Android 无障碍服务权限（AccessibilityService） */
    ACCESSIBILITY,

    /** 直接 Root（su）权限 */
    ROOT,

    /** 普通 Android 所有文件访问 / 存储管理权限（MANAGE_EXTERNAL_STORAGE） */
    FILE_MANAGEMENT,

    /** 普通 Android 应用使用情况访问权限（PACKAGE_USAGE_STATS） */
    USAGE_STATS,

    /** 普通 Android 修改系统设置权限（WRITE_SETTINGS） */
    WRITE_SETTINGS,

    /** 普通 Android 悬浮窗 / 显示在其他应用上层权限（SYSTEM_ALERT_WINDOW） */
    SYSTEM_ALERT_WINDOW,
}

/**
 * 所有受 Android 权限约束的 Koog [ToolSet] 基类。
 *
 * 子类通过实现 [requiredPrivileges] 声明自己支持的权限集合：
 * - 若声明集合非空：当前应用只需持有其中的任意一项权限，即允许注册并使用该 ToolSet；
 * - 若声明集合为空：表示不依赖特殊系统权限，始终允许使用。
 */
abstract class AndroidToolSet : ToolSet {

    /**
     * 声明当前 ToolSet 允许运行的 Android 权限集合（满足其中之一即可）。
     */
    abstract fun requiredPrivileges(): Set<AndroidPrivilege>

    /**
     * 判断在给定可用权限集合 [availablePrivileges] 下，当前 ToolSet 是否允许启用。
     */
    open fun isAllowed(availablePrivileges: Set<AndroidPrivilege>): Boolean {
        val required = requiredPrivileges()
        return required.isEmpty() || required.any { it in availablePrivileges }
    }
}
