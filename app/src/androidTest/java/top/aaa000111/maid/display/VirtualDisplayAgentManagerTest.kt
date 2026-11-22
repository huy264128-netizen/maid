package top.aaa000111.maid.display

import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.aaa000111.maid.agent.VirtualDisplayAgentManager
import top.aaa000111.maid.shizuku.ShizukuManager

@RunWith(AndroidJUnit4::class)
class VirtualDisplayAgentManagerTest {
    @Test
    fun aDisplayRejectsOverlappingTasksAndAnotherDisplayCanRun() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(ShizukuManager.checkPermission())
        assumeTrue(ShizukuManager.checkRemotePermission("android.permission.INJECT_EVENTS"))
        assumeTrue(ShizukuManager.checkRemotePermission("android.permission.INTERNAL_SYSTEM_WINDOW"))
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
        val serviceUid = ShizukuManager.bindUserService().getOrThrow().processUid
        assumeTrue(serviceUid == 0 || ShizukuManager.checkRemotePermission("android.permission.ADD_TRUSTED_DISPLAY"))

        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val manager = VirtualDisplayAgentManager(context) { _, task ->
            if (task == "first") {
                started.complete(Unit)
                finish.await()
            }
            "result=$task"
        }
        try {
            withTimeout(20_000L) {
                val first = manager.createVirtualDisplay(width = 320, height = 240).substringAfter('=').toInt()
                val second = manager.createVirtualDisplay(width = 320, height = 240).substringAfter('=').toInt()
                assertTrue(manager.executeVirtualDisplayTask(first, "first").startsWith("Started"))
                started.await()
                assertNull(withTimeoutOrNull(100L) { manager.waitVirtualDisplayTask(first) })
                assertTrue(manager.executeVirtualDisplayTask(first, "overlap").startsWith("Call wait"))
                assertTrue(manager.releaseVirtualDisplay(first).startsWith("Call wait"))
                assertTrue(manager.executeVirtualDisplayTask(second, "parallel").startsWith("Started"))
                assertEquals("result=parallel", manager.waitVirtualDisplayTask(second))
                finish.complete(Unit)
                assertEquals("result=first", manager.waitVirtualDisplayTask(first))
                assertTrue(manager.executeVirtualDisplayTask(first, "next").startsWith("Started"))
                assertEquals("result=next", manager.waitVirtualDisplayTask(first))
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun missingShizukuAuthorizationRejectsCreation() = runBlocking {
        assumeFalse(ShizukuManager.checkPermission())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displayManager = context.getSystemService(DisplayManager::class.java)!!
        val before = displayManager.displays.map { it.displayId }.toSet()
        val manager = VirtualDisplayAgentManager(context)
        try {
            val failure = runCatching { manager.createVirtualDisplay() }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("Shizuku"))
            assertEquals(before, displayManager.displays.map { it.displayId }.toSet())
        } finally {
            manager.close()
        }
    }
}
