package top.aaa000111.maid.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSafetyConfigTest {

    @Test
    fun safetyLevel_resolvesPresetsAndCustomState() {
        assertEquals(ModelSafetyLevel.STRICT, ModelSafetyConfig.STRICT.safetyLevel)
        assertEquals(ModelSafetyLevel.BALANCED, ModelSafetyConfig.BALANCED.safetyLevel)
        assertEquals(ModelSafetyLevel.UNRESTRICTED, ModelSafetyConfig.UNRESTRICTED.safetyLevel)
        assertEquals(ModelSafetyLevel.BALANCED, ModelSafetyConfig.DEFAULT.safetyLevel)

        val custom = ModelSafetyConfig.BALANCED.copy(allowDisplayModification = true)
        assertEquals(ModelSafetyLevel.CUSTOM, custom.safetyLevel)
        assertEquals(3, custom.allowedSensitiveCategoryCount)

        val customLlmKey = ModelSafetyConfig.BALANCED.copy(allowLocalLlmApiKeyAccess = true)
        assertEquals(ModelSafetyLevel.CUSTOM, customLlmKey.safetyLevel)
        assertTrue(customLlmKey.hasHighRiskEnabled)
        assertTrue(customLlmKey.isToolAllowed("executeProotLinuxTaskWithLlmApi"))
        assertNull(customLlmKey.evaluateToolCall("executeProotLinuxTaskWithLlmApi", "{}"))
    }

    @Test
    fun strictMode_blocksSensitiveCategoriesWhileAllowingBasicUiTools() {
        val strict = ModelSafetyConfig.STRICT

        assertTrue(strict.isToolAllowed("tap"))
        assertTrue(strict.isToolAllowed("dumpUiTree"))
        assertTrue(strict.isToolAllowed("startActivity"))
        assertTrue(strict.isToolAllowed("clearDisplaySize"))

        assertFalse(strict.isToolAllowed("executeShell"))
        assertFalse(strict.isToolAllowed("forceStopPackage"))
        assertFalse(strict.isToolAllowed("setDisplaySize"))
        assertFalse(strict.isToolAllowed("lockScreenNow"))
        assertFalse(strict.isToolAllowed("executeRootShell"))
        assertFalse(strict.isToolAllowed("executeProotLinuxTaskWithLlmApi"))

        assertNotNull(strict.evaluateToolCall("executeShell", """{"command":"ls"}"""))
        assertNotNull(strict.evaluateToolCall("lockScreenNow", "{}"))
        assertNotNull(strict.evaluateToolCall("executeProotLinuxTaskWithLlmApi", "{}"))
    }

    @Test
    fun balancedMode_allowsSafeShellAndBlocksDangerousShellCommands() {
        val balanced = ModelSafetyConfig.BALANCED

        assertTrue(balanced.isToolAllowed("executeShell"))
        assertTrue(balanced.isToolAllowed("forceStopPackage"))
        assertFalse(balanced.isToolAllowed("executeRootShell"))
        assertFalse(balanced.isToolAllowed("setApplicationHidden"))
        assertFalse(balanced.isToolAllowed("setDisplayDensity"))
        assertFalse(balanced.isToolAllowed("executeProotLinuxTaskWithLlmApi"))
        assertFalse(balanced.allowLocalLlmApiKeyAccess)
        assertNotNull(balanced.evaluateToolCall("executeProotLinuxTaskWithLlmApi", "{}"))

        // 安全命令应放行
        assertNull(balanced.evaluateToolCall("executeShell", """{"command":"dumpsys activity top"}"""))
        assertNull(balanced.evaluateToolCall("executeShell", """{"command":"getprop ro.build.version.release"}"""))
        assertNull(balanced.evaluateToolCall("executeShell", """{"command":"ls -la /sdcard/Download"}"""))

        // 高危命令应被拦截
        assertNotNull(balanced.evaluateToolCall("executeShell", """{"command":"rm -rf /sdcard/*"}"""))
        assertNotNull(balanced.evaluateToolCall("executeShell", """{"command":"pm uninstall com.example.app"}"""))
        assertNotNull(balanced.evaluateToolCall("executeShell", """{"command":"pm clear com.example.app"}"""))
        assertNotNull(balanced.evaluateToolCall("executeShell", """{"command":"reboot"}"""))
        assertNotNull(balanced.evaluateToolCall("executeShell", """{"command":"dd if=/dev/zero of=/dev/block/sda"}"""))
    }

    @Test
    fun unrestrictedMode_allowsAllToolsAndShellCommands() {
        val unrestricted = ModelSafetyConfig.UNRESTRICTED

        assertTrue(unrestricted.hasHighRiskEnabled)
        assertTrue(unrestricted.allowLocalLlmApiKeyAccess)
        assertEquals(SensitiveToolCategory.entries.size, unrestricted.allowedSensitiveCategoryCount)
        assertEquals(6, unrestricted.allowedSensitiveCategoryCount)
        assertNull(unrestricted.evaluateToolCall("executeRootShell", """{"command":"pm clear com.example.app"}"""))
        assertNull(unrestricted.evaluateToolCall("lockScreenNow", "{}"))
        assertNull(unrestricted.evaluateToolCall("executeProotLinuxTaskWithLlmApi", "{}"))
    }
}
