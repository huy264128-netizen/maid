package top.aaa000111.maid.display

import android.app.Presentation
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.aaa000111.maid.settings.ModelSafetyRepository
import top.aaa000111.maid.shizuku.ShizukuManager

@RunWith(AndroidJUnit4::class)
class VirtualDisplayManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun stopAllowsRecreationAndCloseIsFinal() = runBlocking(Dispatchers.Main) {
        val manager = VirtualDisplayManager(context)
        ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 2).use { reader ->
            try {
                assertEquals(VirtualDisplayManager.State.Idle, manager.state.value)
                val first = manager.create("Maid test", 320, 240, 160, reader.surface)
                assertTrue(first.displayId > Display.DEFAULT_DISPLAY)
                assertEquals(VirtualDisplayManager.State.Active(first.displayId), manager.state.value)

                manager.stop()
                manager.stop()
                assertEquals(VirtualDisplayManager.State.Idle, manager.state.value)
                assertTrue(runCatching { manager.injectTap(160f, 120f) }.exceptionOrNull() is IllegalStateException)
                assertTrue(reader.surface.isValid)

                val second = manager.create("Maid test", 320, 240, 160, reader.surface)
                assertEquals(VirtualDisplayManager.State.Active(second.displayId), manager.state.value)
                assertTrue(
                    runCatching { manager.injectTap(Float.NaN, 120f) }.exceptionOrNull() is IllegalArgumentException,
                )

                manager.close()
                manager.stop()
                assertEquals(VirtualDisplayManager.State.Closed, manager.state.value)
                assertTrue(
                    runCatching { manager.create("Maid test", 320, 240, 160, reader.surface) }
                        .exceptionOrNull() is IllegalStateException,
                )
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun activityRendersIntoLocalCaptureAndRespondsToInput() = runBlocking {
        assumeTrue(ShizukuManager.checkPermission())
        assumeTrue(ShizukuManager.checkRemotePermission("android.permission.INJECT_EVENTS"))
        assumeTrue(ShizukuManager.checkRemotePermission("android.permission.INTERNAL_SYSTEM_WINDOW"))
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
        val serviceUid = ShizukuManager.bindUserService().getOrThrow().processUid
        assumeTrue(serviceUid == 0 || ShizukuManager.checkRemotePermission("android.permission.ADD_TRUSTED_DISPLAY"))
        assumeTrue(ModelSafetyRepository.getSafetyConfig().allowAppLifecycleControl)

        withContext(Dispatchers.Main) {
            val manager = VirtualDisplayManager(context)
            try {
                val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
                val activity = ComponentName(testPackage, VirtualDisplayProbeActivity::class.java.name)
                val width = 1080
                val height = 1920
                val id = manager.createForAgent("Maid Activity test", width, height, 160)
                manager.launchVirtualActivity(activity.flattenToString())

                var dump = ""
                withTimeoutOrNull(12_000L) {
                    do {
                        dump = manager.dumpVirtualDisplayUi()
                        if ("Maid virtual display probe" !in dump) delay(150L)
                    } while ("Maid virtual display probe" !in dump)
                }
                assertTrue(dump, dump.startsWith("displayId=$id\n"))
                assertTrue(dump, "Maid virtual display probe" in dump)
                val probe = dump.lineSequence().first { "Maid virtual display probe" in it }
                val bounds = Regex("""bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""").find(probe)
                    ?: error("Probe bounds are absent: $probe")
                val (left, top, right, bottom) = bounds.destructured
                val x = ((left.toInt().coerceAtLeast(0) + right.toInt().coerceAtMost(width)) / 2).toFloat()
                val y = ((top.toInt().coerceAtLeast(0) + bottom.toInt().coerceAtMost(height)) / 2).toFloat()
                val swipeHalfWidth = minOf(120f, x - left.toInt(), right.toInt() - x)

                // System dimming may lower channel intensity on a secondary display.
                fun colorSignature(color: Int): List<Boolean> =
                    listOf(Color.red(color) > 48, Color.green(color) > 48, Color.blue(color) > 48)

                suspend fun awaitColor(expected: Int) {
                    val expectedSignature = colorSignature(expected)
                    var color = Color.TRANSPARENT
                    withTimeoutOrNull(5_000L) {
                        do {
                            val frame = manager.captureFrame()
                            color = try {
                                assertEquals(width, frame.width)
                                assertEquals(height, frame.height)
                                frame.getPixel(x.toInt(), y.toInt())
                            } finally {
                                frame.recycle()
                            }
                            if (colorSignature(color) != expectedSignature) delay(50L)
                        } while (colorSignature(color) != expectedSignature)
                    }
                    assertEquals(
                        "Probe pixel expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(color)}",
                        expectedSignature,
                        colorSignature(color),
                    )
                }

                awaitColor(Color.BLUE)
                withTimeout(5_000L) {
                    while (!manager.injectTap(x, y)) delay(50L)
                }
                awaitColor(Color.GREEN)
                assertTrue(manager.injectSwipe(x - swipeHalfWidth, y, x + swipeHalfWidth, y))
                awaitColor(Color.RED)
                assertTrue(manager.sendVirtualDisplayKey(KeyEvent.KEYCODE_ENTER))
                awaitColor(Color.YELLOW)
                assertTrue(manager.injectText("x"))
                awaitColor(Color.MAGENTA)
                assertTrue(manager.injectTap(left.toFloat().coerceAtLeast(0f) + 24f, y))
                awaitColor(Color.CYAN)
            } finally {
                manager.closeAgentDisplay()
            }
        }
    }

    @Test
    fun authorizedTapReachesTheVirtualDisplay() = runBlocking {
        assumeTrue("Shizuku is not authorized on this device", ShizukuManager.refreshStatus().isReady)
        withContext(Dispatchers.Main) {
            val manager = VirtualDisplayManager(context)
            ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 2).use { reader ->
                try {
                    val display = manager.create("Maid input test", 320, 240, 160, reader.surface)
                    val presentation = Presentation(context, display)
                    try {
                        val laidOut = CompletableDeferred<Unit>()
                        val tapped = CompletableDeferred<Unit>()
                        val view = View(presentation.context).apply {
                            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> laidOut.complete(Unit) }
                            setOnTouchListener { _, event ->
                                if (event.actionMasked == MotionEvent.ACTION_UP) tapped.complete(Unit)
                                true
                            }
                        }
                        presentation.setContentView(view)
                        presentation.show()
                        withTimeout(5_000L) {
                            laidOut.await()
                            val location = IntArray(2).also(view::getLocationOnScreen)
                            val x = location[0] + view.width / 2f
                            val y = location[1] + view.height / 2f
                            // Layout can finish before WindowManager publishes the input window.
                            while (!manager.injectTap(x, y)) delay(50L)
                            tapped.await()
                        }
                    } finally {
                        presentation.dismiss()
                    }
                } finally {
                    manager.close()
                }
            }
        }
    }
}
