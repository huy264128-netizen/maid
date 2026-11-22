package top.aaa000111.maid.androidtool

import android.app.Application
import android.content.Context
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidToolsetDispatcherTest {

    private class BasicToolSet : AndroidToolSet() {
        override fun requiredPrivileges(): Set<AndroidPrivilege> = emptySet()

        @Tool
        @LLMDescription("Get basic device status")
        fun getBasicStatus(): String = "ok"
    }

    private class InputControlToolSet : AndroidToolSet() {
        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ACCESSIBILITY,
            AndroidPrivilege.ROOT,
        )

        @Tool
        @LLMDescription("Tap screen coordinate")
        fun tapScreen(
            @LLMDescription("X coordinate") x: Int,
            @LLMDescription("Y coordinate") y: Int,
        ): String = "tapped($x,$y)"
    }

    private class DeviceOwnerToolSet : AndroidToolSet() {
        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
            AndroidPrivilege.DHIZUKU,
        )

        @Tool
        @LLMDescription("Set application hidden state")
        fun setAppHidden(
            @LLMDescription("Target package") packageName: String,
        ): String = "hidden:$packageName"
    }

    private class RootShizukuOnlyToolSet : AndroidToolSet() {
        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
            AndroidPrivilege.ROOT_SHIZUKU,
            AndroidPrivilege.ROOT,
        )

        @Tool
        @LLMDescription("Run privileged root task")
        fun runRootTask(): String = "root_done"
    }

    @Test
    fun dispatch_registersToolSet_whenAnyDeclaredPrivilegeIsHeld() {
        var heldPrivileges = emptySet<AndroidPrivilege>()
        val basic = BasicToolSet()
        val input = InputControlToolSet()
        val owner = DeviceOwnerToolSet()
        val rootOnly = RootShizukuOnlyToolSet()

        val dispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(basic, input, owner, rootOnly),
            privilegeChecker = { heldPrivileges },
        )

        // 1. 无任何特殊权限：仅允许无需权限的 BasicToolSet
        val noneRegistry = dispatcher.dispatch()
        assertEquals(listOf("getBasicStatus"), noneRegistry.tools.map { it.name })

        // 2. 仅持有 ACCESSIBILITY：InputControlToolSet 声明了 {SHIZUKU, ACCESSIBILITY, ROOT}，满足其中之一即允许
        heldPrivileges = setOf(AndroidPrivilege.ACCESSIBILITY)
        val accessibilityRegistry = dispatcher.dispatch()
        assertEquals(
            setOf("getBasicStatus", "tapScreen"),
            accessibilityRegistry.tools.map { it.name }.toSet(),
        )

        // 3. 持有普通 SHIZUKU：允许 InputControlToolSet，不允许仅限 ROOT_SHIZUKU/ROOT 与 DHIZUKU 的 ToolSet
        heldPrivileges = setOf(AndroidPrivilege.SHIZUKU)
        val shizukuAllowed = dispatcher.resolveAllowedToolSets()
        assertEquals(listOf(basic, input), shizukuAllowed)
        assertFalse(rootOnly in shizukuAllowed)
        assertFalse(owner in shizukuAllowed)

        // 4. 同时持有 ROOT_SHIZUKU 与 DHIZUKU：全部四个 ToolSet 均应注册
        heldPrivileges = setOf(
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT_SHIZUKU,
            AndroidPrivilege.DHIZUKU,
        )
        val fullRegistry = dispatcher.dispatch()
        assertEquals(
            setOf("getBasicStatus", "tapScreen", "setAppHidden", "runRootTask"),
            fullRegistry.tools.map { it.name }.toSet(),
        )
    }

    @Test
    fun dispatch_supportsDynamicRegistrationAndAsyncDispatch() = runBlocking {
        val basic = BasicToolSet()
        val owner = DeviceOwnerToolSet()
        val dispatcher = AndroidToolsetDispatcher(
            privilegeChecker = { setOf(AndroidPrivilege.DHIZUKU) },
        )

        assertTrue(dispatcher.dispatch().tools.isEmpty())

        dispatcher.register(basic)
        dispatcher.register(owner)
        val registry = dispatcher.dispatchAsync()
        assertEquals(
            setOf("getBasicStatus", "setAppHidden"),
            registry.tools.map { it.name }.toSet(),
        )

        dispatcher.unregister(owner)
        assertEquals(
            listOf("getBasicStatus"),
            dispatcher.dispatchTools().map { it.name },
        )
    }

    @Test
    fun defaultAndroidToolSets_dispatchByPrivilegeAndRegisterInKoogRegistry() {
        val fakeContext = allocateStubInstance<Context>(Application::class.java)
        var heldPrivileges = emptySet<AndroidPrivilege>()

        val dispatcher = AndroidToolsetDispatcher(
            toolSets = DefaultAndroidToolSets.createAll(fakeContext),
            privilegeChecker = { heldPrivileges },
        )

        fun allowedNames(): Set<String> = dispatcher.resolveAllowedToolSets().map { it.name }.toSet()

        val alwaysAllowedToolsets = setOf("audio", "alarm_clock")
        val alwaysAllowedTools = setOf(
            "getStreamVolumes",
            "setStreamVolume",
            "setSystemAlarm",
            "setSystemTimer",
            "showSystemAlarms",
            "showSystemTimers",
            "dismissSystemAlarm",
            "snoozeSystemAlarm",
        )
        fun privilegedToolNames(): Set<String> = dispatcher.dispatch().tools.map { it.name }.toSet() - alwaysAllowedTools

        // 普通音频与系统闹钟权限随应用安装获得，无需特殊权限。
        assertEquals(alwaysAllowedToolsets, allowedNames())
        assertEquals(alwaysAllowedTools, dispatcher.dispatch().tools.map { it.name }.toSet())

        // 1. 仅无障碍：动态加载 input 与 screen
        heldPrivileges = setOf(AndroidPrivilege.ACCESSIBILITY)
        assertEquals(setOf("input", "screen") + alwaysAllowedToolsets, allowedNames())
        assertEquals(
            setOf(
                "tap",
                "swipe",
                "inputText",
                "captureScreenshot",
                "dumpUiTree",
                "findNodesByText",
                "clickNodeByText",
                "clickNodeByViewId",
                "performGlobalAction",
            ),
            privilegedToolNames(),
        )

        // 2. 仅普通 Shizuku（ADB）：加载 input, screen, shell, app_control, window, file_management, usage_stats, system_settings；不加载 root_system 与 device_policy
        heldPrivileges = setOf(AndroidPrivilege.SHIZUKU)
        assertEquals(
            setOf(
                "input",
                "key_input",
                "screen",
                "shell",
                "app_control",
                "window",
                "file_management",
                "usage_stats",
                "system_settings",
            ) + alwaysAllowedToolsets,
            allowedNames(),
        )
        val shizukuTools = dispatcher.dispatch().tools.map { it.name }.toSet()
        assertTrue("pressKey" in shizukuTools)
        assertTrue("executeShell" in shizukuTools)
        assertTrue("startActivity" in shizukuTools)
        assertTrue("getDisplayInfo" in shizukuTools)
        assertTrue("listDirectory" in shizukuTools)
        assertTrue("getRecentForegroundApps" in shizukuTools)
        assertTrue("setScreenBrightness" in shizukuTools)
        assertFalse("executeRootShell" in shizukuTools)
        assertFalse("setApplicationHidden" in shizukuTools)

        // 3. Root Shizuku：在 Shizuku 基础上额外解锁 root_system
        heldPrivileges = setOf(AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT_SHIZUKU)
        assertEquals(
            setOf(
                "input",
                "key_input",
                "screen",
                "shell",
                "app_control",
                "window",
                "root_system",
                "file_management",
                "usage_stats",
                "system_settings",
            ) + alwaysAllowedToolsets,
            allowedNames(),
        )
        assertTrue("executeRootShell" in dispatcher.dispatch().tools.map { it.name })

        // 4. 直接 Root（su）：解锁除 Dhizuku 外的全部能力
        heldPrivileges = setOf(AndroidPrivilege.ROOT)
        assertEquals(
            setOf(
                "input",
                "key_input",
                "screen",
                "shell",
                "app_control",
                "window",
                "root_system",
                "file_management",
                "usage_stats",
                "system_settings",
            ) + alwaysAllowedToolsets,
            allowedNames(),
        )

        // 5. 仅 Dhizuku：额外加载 device_policy
        heldPrivileges = setOf(AndroidPrivilege.DHIZUKU)
        assertEquals(setOf("device_policy") + alwaysAllowedToolsets, allowedNames())
        assertEquals(
            setOf(
                "getDhizukuInfo",
                "setApplicationHidden",
                "isApplicationHidden",
                "setPackagesSuspended",
                "isPackageSuspended",
                "setUninstallBlocked",
                "isUninstallBlocked",
                "lockScreenNow",
            ),
            privilegedToolNames(),
        )

        // 6. 普通安卓权限独立解锁对应的工具集（无需 Shizuku / Root）
        heldPrivileges = setOf(AndroidPrivilege.FILE_MANAGEMENT)
        assertEquals(setOf("file_management") + alwaysAllowedToolsets, allowedNames())
        assertEquals(
            setOf(
                "listDirectory",
                "readTextFile",
                "writeTextFile",
                "createDirectory",
                "copyOrMoveFile",
                "deleteFileOrDirectory",
                "searchFiles",
                "getFileDetails",
                "importFileToSession",
                "exportFileFromSession",
            ),
            privilegedToolNames(),
        )

        heldPrivileges = setOf(AndroidPrivilege.USAGE_STATS)
        assertEquals(setOf("usage_stats") + alwaysAllowedToolsets, allowedNames())
        assertEquals(
            setOf("getRecentForegroundApps", "getAppUsageSummary"),
            privilegedToolNames(),
        )

        heldPrivileges = setOf(AndroidPrivilege.WRITE_SETTINGS)
        assertEquals(setOf("system_settings") + alwaysAllowedToolsets, allowedNames())
        assertEquals(
            setOf(
                "getDisplaySettings",
                "setScreenBrightness",
                "setScreenOffTimeout",
            ),
            privilegedToolNames(),
        )

        // 7. 全部权限就绪：13 个 ToolSet 的所有 Koog @Tool 方法均能通过反射注册
        heldPrivileges = AndroidPrivilege.entries.toSet()
        assertEquals(
            setOf(
                "input",
                "key_input",
                "screen",
                "shell",
                "app_control",
                "window",
                "root_system",
                "file_management",
                "usage_stats",
                "system_settings",
                "device_policy",
            ) + alwaysAllowedToolsets,
            allowedNames(),
        )
        val allToolNames = dispatcher.dispatch().tools.map { it.name }
        assertEquals(59, allToolNames.size)
        assertEquals(59, allToolNames.toSet().size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateStubInstance(clazz: Class<out T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz) as T
    }
}
