package top.aaa000111.maid.shizuku

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Point
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * 运行在 Shizuku UserService 特权进程中，通过反射获取并封装
 * `IActivityManager`、`IActivityTaskManager`、`IInputManager` 与 `IWindowManager` 的隐藏/特权能力。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal class PrivilegedSystemManagers {

    companion object {
        const val KEY_ACTIVITY_MANAGER = "activity_manager"
        const val KEY_ACTIVITY_TASK_MANAGER = "activity_task_manager"
        const val KEY_INPUT_MANAGER = "input_manager"
        const val KEY_WINDOW_MANAGER = "window_manager"
        const val KEY_INPUT_EVENT = "input_event"

        const val INJECT_MODE_ASYNC = 0
        const val INJECT_MODE_WAIT_FOR_RESULT = 1
        const val INJECT_MODE_WAIT_FOR_FINISH = 2
    }

    private val activityManager: Any? by lazy { resolveActivityManager() }
    private val activityTaskManager: Any? by lazy { resolveActivityTaskManager() }
    private val inputManager: Any? by lazy { resolveInputManager() }
    private val windowManager: Any? by lazy { resolveWindowManager() }

    fun getManagersStatus(): Bundle {
        return Bundle().apply {
            putBoolean(KEY_ACTIVITY_MANAGER, activityManager != null)
            putBoolean(KEY_ACTIVITY_TASK_MANAGER, activityTaskManager != null)
            putBoolean(KEY_INPUT_MANAGER, inputManager != null)
            putBoolean(KEY_WINDOW_MANAGER, windowManager != null)
        }
    }

    // region System service reflection resolution

    private fun getServiceBinder(name: String): IBinder? {
        return runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
        }.getOrNull()
    }

    private fun asInterface(stubClassName: String, binder: IBinder?): Any? {
        if (binder == null) return null
        return runCatching {
            val stubClass = Class.forName(stubClassName)
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder)
        }.getOrNull()
    }

    private fun resolveActivityManager(): Any? {
        return asInterface("android.app.IActivityManager\$Stub", getServiceBinder("activity"))
            ?: runCatching {
                ActivityManager::class.java.getMethod("getService").invoke(null)
            }.getOrNull()
    }

    private fun resolveActivityTaskManager(): Any? {
        return asInterface("android.app.IActivityTaskManager\$Stub", getServiceBinder("activity_task"))
            ?: runCatching {
                Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService")
                    .invoke(null)
            }.getOrNull()
    }

    private fun resolveInputManager(): Any? {
        return asInterface("android.hardware.input.IInputManager\$Stub", getServiceBinder("input"))
            ?: runCatching {
                Class.forName("android.hardware.input.InputManagerGlobal")
                    .getMethod("getInstance")
                    .invoke(null)
            }.getOrNull()
            ?: runCatching {
                Class.forName("android.hardware.input.InputManager")
                    .getMethod("getInstance")
                    .invoke(null)
            }.getOrNull()
    }

    private fun resolveWindowManager(): Any? {
        return asInterface("android.view.IWindowManager\$Stub", getServiceBinder("window"))
            ?: runCatching {
                Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService")
                    .invoke(null)
            }.getOrNull()
    }

    private fun callingPackageForCurrentUid(): String {
        return when (Process.myUid()) {
            0 -> "android"
            2000 -> "com.android.shell"
            else -> "com.android.shell"
        }
    }

    // endregion

    // region ActivityManager capabilities

    fun forceStopPackage(packageName: String, userId: Int): Boolean {
        val am = activityManager ?: return false
        return runCatching {
            val method = am.javaClass.getMethod(
                "forceStopPackage",
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(am, packageName, userId)
            true
        }.getOrDefault(false)
    }

    fun killBackgroundProcesses(packageName: String, userId: Int): Boolean {
        val am = activityManager ?: return false
        return runCatching {
            val method = am.javaClass.getMethod(
                "killBackgroundProcesses",
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(am, packageName, userId)
            true
        }.getOrDefault(false)
    }

    @Suppress("UNCHECKED_CAST")
    fun getRunningAppProcesses(): List<PrivilegedProcessInfo> {
        val am = activityManager ?: return emptyList()
        return runCatching {
            val method = am.javaClass.getMethod("getRunningAppProcesses")
            val rawList = method.invoke(am) as? List<ActivityManager.RunningAppProcessInfo>
                ?: return emptyList()
            rawList.map { info ->
                PrivilegedProcessInfo(
                    pid = info.pid,
                    uid = info.uid,
                    processName = info.processName.orEmpty(),
                    importance = info.importance,
                    packages = info.pkgList?.toList().orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun getTopActivity(): String? {
        val atm = activityTaskManager ?: activityManager ?: return null
        val tasks = invokeGetTasks(atm) ?: return null
        val topTask = tasks.firstOrNull() ?: return null
        val component = runCatching {
            topTask.javaClass.getField("topActivity").get(topTask) as? ComponentName
        }.getOrNull() ?: runCatching {
            topTask.javaClass.getField("baseActivity").get(topTask) as? ComponentName
        }.getOrNull()
        return component?.flattenToShortString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeGetTasks(target: Any): List<Any>? {
        val methods = target.javaClass.methods.filter { it.name == "getTasks" }
        for (candidate in methods.sortedBy { it.parameterCount }) {
            val params = candidate.parameterTypes
            val result = runCatching {
                when (params.size) {
                    1 -> candidate.invoke(target, 1)
                    2 -> candidate.invoke(target, 1, false)
                    3 -> candidate.invoke(target, 1, false, false)
                    4 -> candidate.invoke(target, 1, false, false, -1)
                    else -> null
                }
            }.getOrNull() as? List<Any>
            if (result != null) return result
        }
        return null
    }

    fun startActivityAsUser(intent: Intent, userId: Int): Int {
        val am = activityManager ?: return -1
        val atm = activityTaskManager
        val callingPackage = callingPackageForCurrentUid()

        val targets = listOfNotNull(am, atm)
        val methodNames = listOf(
            "startActivityAsUserWithFeature",
            "startActivityAsUser",
            "startActivityWithFeature",
        )

        for (target in targets) {
            for (methodName in methodNames) {
                val method = target.javaClass.methods.firstOrNull { it.name == methodName }
                    ?: continue
                val args = buildStartActivityArgs(method, intent, callingPackage, userId) ?: continue
                val result = runCatching {
                    method.invoke(target, *args) as? Int
                }.getOrNull()
                if (result != null) return result
            }
        }
        return -1
    }

    private fun buildStartActivityArgs(
        method: Method,
        intent: Intent,
        callingPackage: String,
        userId: Int,
    ): Array<Any?>? {
        val paramTypes = method.parameterTypes
        val args = arrayOfNulls<Any?>(paramTypes.size)
        var stringIndex = 0
        var intIndex = 0

        for (i in paramTypes.indices) {
            val type = paramTypes[i]
            args[i] = when {
                type == Intent::class.java -> intent
                type == String::class.java -> {
                    val value = when (stringIndex) {
                        0 -> callingPackage
                        else -> null // callingFeatureId, resolvedType, resultWho
                    }
                    stringIndex++
                    value
                }
                type == Int::class.javaPrimitiveType || type == Int::class.java -> {
                    val isLastParam = i == paramTypes.lastIndex
                    val value = when {
                        isLastParam && method.name.contains("AsUser") -> userId
                        else -> 0 // requestCode, flags
                    }
                    intIndex++
                    value
                }
                type == IBinder::class.java -> null
                type == Bundle::class.java -> null
                else -> null
            }
        }
        return args
    }

    // endregion

    // region InputManager capabilities

    fun injectInputEvent(event: InputEvent, mode: Int = INJECT_MODE_WAIT_FOR_FINISH): Boolean {
        val im = inputManager ?: return false

        val twoArg = runCatching {
            val method = im.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(im, event, mode) as? Boolean
        }.getOrNull()
        if (twoArg != null) return twoArg

        val threeArg = runCatching {
            val method = im.javaClass.getMethod(
                "injectInputEventToTarget",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            method.invoke(im, event, mode, -1) as? Boolean
        }.getOrNull()
        return threeArg ?: false
    }

    fun injectTap(x: Float, y: Float, displayId: Int = 0): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val upTime = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            val downOk = injectInputEventOnDisplay(down, displayId)
            val upOk = injectInputEventOnDisplay(up, displayId)
            downOk && upOk
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun injectSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L,
        displayId: Int = 0,
    ): Boolean {
        val safeDuration = durationMs.coerceIn(16L, 10_000L)
        val steps = (safeDuration / 16L).toInt().coerceAtLeast(2)
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            if (!injectInputEventOnDisplay(down, displayId)) return false
        } finally {
            down.recycle()
        }

        val stepDelay = safeDuration / steps
        for (step in 1 until steps) {
            SystemClock.sleep(stepDelay)
            val fraction = step.toFloat() / steps.toFloat()
            val curX = startX + (endX - startX) * fraction
            val curY = startY + (endY - startY) * fraction
            val move = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                curX,
                curY,
                0,
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                if (!injectInputEventOnDisplay(move, displayId)) return false
            } finally {
                move.recycle()
            }
        }

        SystemClock.sleep(stepDelay)
        val up = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            endX,
            endY,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            injectInputEventOnDisplay(up, displayId)
        } finally {
            up.recycle()
        }
    }

    fun injectKeyEvent(
        keyCode: Int,
        longPress: Boolean = false,
        metaState: Int = 0,
        displayId: Int = 0,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )

        if (!injectInputEventOnDisplay(down, displayId)) return false

        if (longPress) {
            val repeatTime = SystemClock.uptimeMillis()
            val repeat = KeyEvent(
                now,
                repeatTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                1,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_LONG_PRESS,
                InputDevice.SOURCE_KEYBOARD,
            )
            if (!injectInputEventOnDisplay(repeat, displayId)) return false
        }

        val upTime = SystemClock.uptimeMillis()
        val up = KeyEvent(
            now,
            upTime,
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )

        return injectInputEventOnDisplay(up, displayId)
    }

    fun injectText(text: String, displayId: Int = 0): Boolean {
        if (text.isEmpty()) return true
        val keyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = keyCharacterMap.getEvents(text.toCharArray()) ?: return false
        for (event in events) {
            if (!injectInputEventOnDisplay(event, displayId)) {
                return false
            }
        }
        return true
    }

    private fun injectInputEventOnDisplay(event: InputEvent, displayId: Int): Boolean {
        if (displayId != 0) {
            val targeted = runCatching {
                val method = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                method.invoke(event, displayId)
            }.isSuccess
            if (!targeted) return false
        }
        return injectInputEvent(event, INJECT_MODE_WAIT_FOR_FINISH)
    }

    // endregion

    // region WindowManager capabilities

    fun getDisplayInfo(displayId: Int = 0): PrivilegedDisplayInfo? {
        val wm = windowManager ?: return null
        val baseSize = Point()
        val initialSize = Point()

        val gotBase = runCatching {
            wm.javaClass.getMethod(
                "getBaseDisplaySize",
                Int::class.javaPrimitiveType,
                Point::class.java,
            ).invoke(wm, displayId, baseSize)
            true
        }.getOrDefault(false)

        val gotInitial = runCatching {
            wm.javaClass.getMethod(
                "getInitialDisplaySize",
                Int::class.javaPrimitiveType,
                Point::class.java,
            ).invoke(wm, displayId, initialSize)
            true
        }.getOrDefault(false)

        if (!gotBase && !gotInitial) return null

        val baseDensity = runCatching {
            wm.javaClass.getMethod("getBaseDisplayDensity", Int::class.javaPrimitiveType)
                .invoke(wm, displayId) as? Int
        }.getOrNull() ?: 0

        val initialDensity = runCatching {
            wm.javaClass.getMethod("getInitialDisplayDensity", Int::class.javaPrimitiveType)
                .invoke(wm, displayId) as? Int
        }.getOrNull() ?: 0

        val rotation = runCatching {
            wm.javaClass.getMethod("getDefaultDisplayRotation").invoke(wm) as? Int
        }.getOrNull() ?: 0

        val hasNavBar = runCatching {
            wm.javaClass.getMethod("hasNavigationBar", Int::class.javaPrimitiveType)
                .invoke(wm, displayId) as? Boolean
        }.getOrNull() ?: false

        return PrivilegedDisplayInfo(
            displayId = displayId,
            baseWidth = baseSize.x,
            baseHeight = baseSize.y,
            initialWidth = initialSize.x,
            initialHeight = initialSize.y,
            baseDensityDpi = baseDensity,
            initialDensityDpi = initialDensity,
            rotation = rotation,
            hasNavigationBar = hasNavBar,
        )
    }

    fun setForcedDisplaySize(displayId: Int, width: Int, height: Int): Boolean {
        val wm = windowManager ?: return false
        return runCatching {
            wm.javaClass.getMethod(
                "setForcedDisplaySize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, width, height)
            true
        }.getOrDefault(false)
    }

    fun clearForcedDisplaySize(displayId: Int): Boolean {
        val wm = windowManager ?: return false
        return runCatching {
            wm.javaClass.getMethod("clearForcedDisplaySize", Int::class.javaPrimitiveType)
                .invoke(wm, displayId)
            true
        }.getOrDefault(false)
    }

    fun setForcedDisplayDensity(displayId: Int, densityDpi: Int, userId: Int = 0): Boolean {
        val wm = windowManager ?: return false
        val withUser = runCatching {
            wm.javaClass.getMethod(
                "setForcedDisplayDensityForUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, densityDpi, userId)
            true
        }.getOrDefault(false)
        if (withUser) return true

        return runCatching {
            wm.javaClass.getMethod(
                "setForcedDisplayDensity",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, densityDpi)
            true
        }.getOrDefault(false)
    }

    fun clearForcedDisplayDensity(displayId: Int, userId: Int = 0): Boolean {
        val wm = windowManager ?: return false
        val withUser = runCatching {
            wm.javaClass.getMethod(
                "clearForcedDisplayDensityForUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, userId)
            true
        }.getOrDefault(false)
        if (withUser) return true

        return runCatching {
            wm.javaClass.getMethod("clearForcedDisplayDensity", Int::class.javaPrimitiveType)
                .invoke(wm, displayId)
            true
        }.getOrDefault(false)
    }

    fun freezeRotation(displayId: Int, rotation: Int): Boolean {
        val wm = windowManager ?: return false
        val twoArg = runCatching {
            wm.javaClass.getMethod(
                "freezeDisplayRotation",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, rotation)
            true
        }.getOrDefault(false)
        if (twoArg) return true

        return runCatching {
            wm.javaClass.getMethod("freezeRotation", Int::class.javaPrimitiveType)
                .invoke(wm, rotation)
            true
        }.getOrDefault(false)
    }

    fun thawRotation(displayId: Int): Boolean {
        val wm = windowManager ?: return false
        val oneArg = runCatching {
            wm.javaClass.getMethod("thawDisplayRotation", Int::class.javaPrimitiveType)
                .invoke(wm, displayId)
            true
        }.getOrDefault(false)
        if (oneArg) return true

        return runCatching {
            wm.javaClass.getMethod("thawRotation").invoke(wm)
            true
        }.getOrDefault(false)
    }

    fun isKeyguardLocked(): Boolean {
        val wm = windowManager ?: return false
        return runCatching {
            wm.javaClass.getMethod("isKeyguardLocked").invoke(wm) as? Boolean
        }.getOrNull() ?: false
    }

    // endregion
}
