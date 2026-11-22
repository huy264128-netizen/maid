package top.aaa000111.maid.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadlessBrowserTest {
    @Test
    fun tabsRenderFramesAndRejectCoordinatesFromAnotherTab() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val browser = HeadlessBrowser.open(context)
        try {
            val first = browser.openTab()
            withTimeout(10_000) {
                browser.events.first { it.tab.id == first.id && it.type == "NavigationFinished" }
            }
            val firstFrame = browser.capture(first.id)
            try {
                assertEquals(firstFrame.viewport.widthPx, firstFrame.bitmap.width)
                assertEquals(firstFrame.viewport.heightPx, firstFrame.bitmap.height)
                val second = browser.openTab()
                assertNotEquals(first.id, second.id)
                withTimeout(10_000) {
                    browser.events.first { it.tab.id == second.id && it.type == "NavigationFinished" }
                }
                val point = firstFrame.bitmap.width / 2f to firstFrame.bitmap.height / 2f
                val stale = runCatching { browser.gesture(second.id, firstFrame.id, listOf(point, point), 0) }
                assertTrue(stale.isFailure)
                val secondFrame = browser.capture(second.id)
                try {
                    val state = browser.gesture(second.id, secondFrame.id, listOf(point, point), 0)
                    assertEquals(second.id, state.id)
                    assertEquals("about:blank", state.url)
                } finally {
                    secondFrame.bitmap.recycle()
                }
                assertTrue(browser.selectTab(first.id).active)
                assertEquals(2, browser.tabStates().size)
            } finally {
                firstFrame.bitmap.recycle()
            }
        } finally {
            browser.close()
        }
    }
}
