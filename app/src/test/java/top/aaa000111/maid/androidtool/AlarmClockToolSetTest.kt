package top.aaa000111.maid.androidtool

import android.app.Application
import android.content.Context
import android.provider.AlarmClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aaa000111.maid.runtime.MaidLangNativeRegistry
import java.util.Calendar

class AlarmClockToolSetTest {

    private fun createToolSet(
        recorder: MutableList<AlarmClockRequest>,
        failure: Throwable? = null,
    ): AlarmClockToolSet {
        val fakeContext = allocateStubInstance<Context>(Application::class.java)
        return AlarmClockToolSet(
            context = fakeContext,
            launcher = AlarmClockActionLauncher { request ->
                recorder.add(request)
                if (failure != null) Result.failure(failure)
                else Result.success("OK:${request::class.simpleName}")
            },
        )
    }

    @Test
    fun setSystemAlarm_validatesAndDispatchesExpectedRequest() {
        val recorded = mutableListOf<AlarmClockRequest>()
        val toolSet = createToolSet(recorded)

        val result = toolSet.setSystemAlarm(
            hour = 7,
            minute = 30,
            message = "起床",
            days = "周一, 周三, fri",
            vibrate = false,
            skipUi = true,
        )
        assertEquals("OK:SetAlarm", result)
        assertEquals(1, recorded.size)
        assertEquals(
            AlarmClockRequest.SetAlarm(
                hour = 7,
                minute = 30,
                message = "起床",
                days = listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY),
                vibrate = false,
                skipUi = true,
            ),
            recorded.single(),
        )

        // 非法时间与非法星期应被拦截且不发送请求
        assertTrue(toolSet.setSystemAlarm(hour = 24, minute = 0).startsWith("Error:"))
        assertTrue(toolSet.setSystemAlarm(hour = 8, minute = 60).startsWith("Error:"))
        assertTrue(toolSet.setSystemAlarm(hour = 8, minute = 0, days = "funday").startsWith("Error:"))
        assertEquals(1, recorded.size)
    }

    @Test
    fun parseRepeatDays_supportsShortcutsChineseAndNumericTokens() {
        assertEquals(
            listOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY,
            ),
            AlarmClockToolSet.parseRepeatDays("工作日").getOrThrow(),
        )
        assertEquals(
            listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            AlarmClockToolSet.parseRepeatDays("weekends").getOrThrow(),
        )
        assertEquals(
            listOf(Calendar.MONDAY, Calendar.SUNDAY),
            AlarmClockToolSet.parseRepeatDays("1, 7").getOrThrow(),
        )
        assertEquals(7, AlarmClockToolSet.parseRepeatDays("everyday").getOrThrow().size)
    }

    @Test
    fun setSystemTimer_andShowActions_dispatchCorrectly() {
        val recorded = mutableListOf<AlarmClockRequest>()
        val toolSet = createToolSet(recorded)

        assertTrue(toolSet.setSystemTimer(lengthSeconds = 0).startsWith("Error:"))
        assertTrue(toolSet.setSystemTimer(lengthSeconds = -10).startsWith("Error:"))
        assertTrue(recorded.isEmpty())

        assertEquals("OK:SetTimer", toolSet.setSystemTimer(lengthSeconds = 300, message = "泡茶", skipUi = true))
        assertEquals("OK:ShowAlarms", toolSet.showSystemAlarms())
        assertEquals("OK:ShowTimers", toolSet.showSystemTimers())

        assertEquals(
            listOf(
                AlarmClockRequest.SetTimer(lengthSeconds = 300, message = "泡茶", skipUi = true),
                AlarmClockRequest.ShowAlarms,
                AlarmClockRequest.ShowTimers,
            ),
            recorded,
        )
    }

    @Test
    fun dismissAndSnoozeSystemAlarm_validateArgumentsAndDispatch() {
        val recorded = mutableListOf<AlarmClockRequest>()
        val toolSet = createToolSet(recorded)

        assertEquals("OK:DismissAlarm", toolSet.dismissSystemAlarm(searchMode = "next"))
        assertEquals(
            "OK:DismissAlarm",
            toolSet.dismissSystemAlarm(searchMode = "time", hour = 6, minute = 45),
        )
        assertEquals(
            "OK:DismissAlarm",
            toolSet.dismissSystemAlarm(searchMode = "label", label = "晨跑"),
        )
        assertTrue(toolSet.dismissSystemAlarm(searchMode = "time", hour = -1, minute = 0).startsWith("Error:"))
        assertTrue(toolSet.dismissSystemAlarm(searchMode = "label", label = "  ").startsWith("Error:"))
        assertTrue(toolSet.dismissSystemAlarm(searchMode = "unknown").startsWith("Error:"))

        assertEquals("OK:SnoozeAlarm", toolSet.snoozeSystemAlarm(snoozeDurationMinutes = 0))
        assertEquals("OK:SnoozeAlarm", toolSet.snoozeSystemAlarm(snoozeDurationMinutes = 10))
        assertTrue(toolSet.snoozeSystemAlarm(snoozeDurationMinutes = -5).startsWith("Error:"))

        assertEquals(
            listOf(
                AlarmClockRequest.DismissAlarm(AlarmClock.ALARM_SEARCH_MODE_NEXT, null, null, ""),
                AlarmClockRequest.DismissAlarm(AlarmClock.ALARM_SEARCH_MODE_TIME, 6, 45, ""),
                AlarmClockRequest.DismissAlarm(AlarmClock.ALARM_SEARCH_MODE_LABEL, null, null, "晨跑"),
                AlarmClockRequest.SnoozeAlarm(null),
                AlarmClockRequest.SnoozeAlarm(10),
            ),
            recorded,
        )
    }

    @Test
    fun launcherFailure_isReturnedAsErrorMessage_andRegisteredInMaidLang() {
        val toolSet = createToolSet(
            recorder = mutableListOf(),
            failure = IllegalStateException("No clock app installed"),
        )
        val out = toolSet.showSystemAlarms()
        assertEquals("Error: No clock app installed", out)

        // 确认 alarm_clock 工具集无需特殊权限且已注册到 MaidLangNativeRegistry
        assertTrue(toolSet.requiredPrivileges().isEmpty())
        assertTrue("alarm_clock" in MaidLangNativeRegistry.registeredToolsetIds())
        val check = MaidLangNativeRegistry.check("""setSystemAlarm(7, 30, "起床", "", true, true);""")
        assertTrue(check.isSatisfied)
        assertEquals(setOf("alarm_clock"), check.requiredToolsets)
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
