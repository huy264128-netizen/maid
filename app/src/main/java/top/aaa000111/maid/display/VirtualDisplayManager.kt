package top.aaa000111.maid.display

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.aaa000111.maid.agent.VisionToolSet
import top.aaa000111.maid.settings.ModelSafetyRepository
import top.aaa000111.maid.shizuku.ShizukuManager
import java.io.ByteArrayOutputStream

/** 管理一个虚拟显示器。直接传入的输出 Surface 由调用方释放。 */
class VirtualDisplayManager(context: Context) : AutoCloseable, ToolSet {
    override val name = "virtual_display"
    sealed interface State {
        data object Idle : State
        data class Active(val displayId: Int) : State
        data object Closed : State
    }

    private class ActiveDisplay(
        val display: Display,
        val widthPx: Int,
        val heightPx: Int,
        val localHandle: VirtualDisplay? = null,
        val localCallback: VirtualDisplay.Callback? = null,
        val remoteLease: ShizukuManager.TrustedDisplayLease? = null,
    ) {
        fun requirePoint(x: Float, y: Float) {
            require(x >= 0f && x < widthPx && y >= 0f && y < heightPx) {
                "Input coordinates must be inside the virtual display"
            }
        }
    }

    private val appContext = context.applicationContext
    private val displays = appContext.getSystemService(DisplayManager::class.java)
        ?: error("DisplayManager unavailable")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inputMutex = Mutex()
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()

    private var activeDisplay: ActiveDisplay? = null
    private var capture: VirtualDisplayCapture? = null
    private val remoteDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (activeDisplay?.remoteLease?.displayId == displayId) releaseDisplay(State.Idle)
        }
    }

    @MainThread
    fun create(name: String, widthPx: Int, heightPx: Int, densityDpi: Int, surface: Surface): Display {
        checkMainThread()
        check(state.value == State.Idle) { "Virtual display manager is already active or closed" }
        require(name.isNotBlank())
        require(widthPx > 0 && heightPx > 0 && densityDpi > 0)
        require(surface.isValid) { "The output surface is invalid" }

        val callback = object : VirtualDisplay.Callback() {
            override fun onStopped() {
                if (activeDisplay?.localCallback === this) stop()
            }
        }
        val handle = displays.createVirtualDisplay(
            name,
            widthPx,
            heightPx,
            densityDpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            callback,
            mainHandler,
        ) ?: error("Unable to create the virtual display")

        val display = handle.display
        if (display == null || display.displayId <= Display.DEFAULT_DISPLAY || !display.isValid) {
            handle.release()
            error("Android did not return a valid virtual display")
        }
        activeDisplay = ActiveDisplay(display, widthPx, heightPx, localHandle = handle, localCallback = callback)
        mutableState.value = State.Active(display.displayId)
        return display
    }

    internal suspend fun createForAgent(name: String, width: Int, height: Int, densityDpi: Int): Int {
        withContext(Dispatchers.IO) {
            requireAgentPermission()
            check(appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
                "This device does not support launching activities on secondary displays"
            }
            val services = ShizukuManager.getSystemManagersStatus().getOrThrow()
            check(services["input_manager"] == true) { "Shizuku InputManager is unavailable" }
        }
        return withContext(Dispatchers.Main.immediate) {
            ensureActive()
            check(state.value == State.Idle) { "Virtual display manager is already active or closed" }
            val reader = VirtualDisplayCapture(width, height, mainHandler)
            var lease: ShizukuManager.TrustedDisplayLease? = null
            try {
                // Keep the newly created lease even if cancellation arrives during the Binder call.
                val created = withContext(NonCancellable) {
                    ShizukuManager.createTrustedVirtualDisplay(name, width, height, densityDpi, reader.surface)
                        .getOrThrow()
                }
                lease = created
                ensureActive()
                check(state.value == State.Idle) { "Virtual display manager is already active or closed" }
                val display = displays.getDisplay(created.displayId)
                check(display != null && display.isValid && display.displayId > Display.DEFAULT_DISPLAY) {
                    "The trusted virtual display is unavailable"
                }
                displays.registerDisplayListener(remoteDisplayListener, mainHandler)
                activeDisplay = ActiveDisplay(display, width, height, remoteLease = created)
                capture = reader
                mutableState.value = State.Active(created.displayId)
                created.displayId
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    try {
                        runCatching { lease?.release() }
                    } finally {
                        reader.close()
                    }
                }
                throw error
            }
        }
    }

    @Tool
    @LLMDescription("Open an explicit Android Activity component in this virtual display. Pass package/name, for example com.example.app/.MainActivity.")
    suspend fun launchVirtualActivity(component: String): String = withActiveDisplay { target ->
        withContext(Dispatchers.IO) { requireAgentPermission() }
        check(ModelSafetyRepository.getSafetyConfig().allowAppLifecycleControl) {
            "Activity launch is disabled by the model safety settings"
        }
        val activity = requireNotNull(ComponentName.unflattenFromString(component.trim())) {
            "Specify an explicit package/Activity component"
        }
        check(activeDisplay === target && target.display.isValid) { "The virtual display has stopped" }
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        val result = ShizukuManager.execCommand(
            listOf(
                "/system/bin/am", "start", "-W", "--user", "current",
                "--display", target.display.displayId.toString(),
                "-f", flags.toString(), "-n", activity.flattenToString(),
            ),
            timeoutMillis = 15_000L,
        )
        check(result.isSuccess && result.stdout.lineSequence().any { it.trim() == "Status: ok" } &&
            !result.stdout.contains("Warning:") && !result.stderr.contains("Warning:")) {
            (result.stderr.ifBlank { result.stdout }).trim().take(400).ifBlank { "Android rejected the activity launch" }
        }
        "Android accepted the launch of ${activity.flattenToShortString()} with virtual display ${target.display.displayId}."
    }

    /** 返回本地画面的独立副本，由调用方回收 Bitmap。 */
    internal suspend fun captureFrame(): Bitmap = withActiveDisplay { snapshotFrame() }

    @Tool
    @LLMDescription("Dump visible UI nodes and display-pixel bounds from the bound virtual display through Shizuku. Use this to read labels or find tap targets.")
    suspend fun dumpVirtualDisplayUi(maxDepth: Int = 15): String = withActiveDisplay { target ->
        withContext(Dispatchers.IO) { requireAgentPermission() }
        ShizukuManager.dumpDisplayUi(target.display.displayId, maxDepth).getOrElse { error ->
            if (error is CancellationException) throw error
            "UI dump unavailable on display ${target.display.displayId}: ${error.message.orEmpty()}"
        }
    }

    @Tool
    @LLMDescription("Inspect a screenshot of the bound virtual display using the configured fast vision model. Returns the native display dimensions for converting relative coordinates into pixels.")
    suspend fun inspectVirtualDisplay(question: String = ""): String {
        val frame = withActiveDisplay { target ->
            val bitmap = snapshotFrame()
            try {
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        output.toByteArray()
                    }
                }
                Triple(target.widthPx, target.heightPx, bytes)
            } finally {
                bitmap.recycle()
            }
        }
        val (width, height, bytes) = frame
        val description = VisionToolSet.default.analyzeImageBytes(
            bytes = bytes,
            mimeType = "image/png",
            question = question.trim().ifEmpty { VisionToolSet.DEFAULT_VISION_QUESTION } +
                "\nWhen reporting positions, use integers from 0 to 1000 relative to the full image.",
        )
        return "widthPx=$width heightPx=$height\n$description"
    }

    private suspend fun snapshotFrame(): Bitmap {
        withContext(Dispatchers.IO) { requireAgentPermission() }
        return checkNotNull(capture) { "No frame reader for this display" }.snapshot()
    }

    internal fun requireAgentPermission() {
        check(ShizukuManager.checkPermission()) { "Shizuku is unavailable or permission was not granted" }
        check(ShizukuManager.checkRemotePermission("android.permission.INJECT_EVENTS") &&
            ShizukuManager.checkRemotePermission("android.permission.INTERNAL_SYSTEM_WINDOW")) {
            "Shizuku lacks INJECT_EVENTS or INTERNAL_SYSTEM_WINDOW"
        }
    }

    /** 释放当前显示器，允许再次调用 create。 */
    @MainThread
    fun stop() {
        checkMainThread()
        check(activeDisplay?.remoteLease == null) { "Agent displays require closeAgentDisplay()" }
        releaseDisplay(State.Idle)
    }

    /** 释放当前显示器并关闭此管理器。可以重复调用。 */
    @MainThread
    override fun close() {
        checkMainThread()
        check(activeDisplay?.remoteLease == null) { "Agent displays require closeAgentDisplay()" }
        releaseDisplay(State.Closed)
    }

    internal suspend fun closeAgentDisplay() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        val lease = activeDisplay?.remoteLease
        try {
            lease?.release()
        } finally {
            releaseDisplay(State.Closed)
        }
    }

    @Tool
    @LLMDescription("Tap the bound virtual display at the supplied pixel coordinates.")
    suspend fun injectTap(x: Float, y: Float): Boolean = inject { target ->
        target.requirePoint(x, y)
        ShizukuManager.injectTap(x, y, target.display.displayId).getOrThrow()
    }

    @Tool
    @LLMDescription("Swipe between two pixel positions on the bound virtual display.")
    suspend fun injectSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L,
    ): Boolean = inject { target ->
        target.requirePoint(startX, startY)
        target.requirePoint(endX, endY)
        require(durationMs in 16L..10_000L) { "Swipe duration must be between 16 and 10000 ms" }
        ShizukuManager.injectSwipe(startX, startY, endX, endY, durationMs, target.display.displayId).getOrThrow()
    }

    @Tool
    @LLMDescription("Send an Android navigation or editing key to the bound display. Use injectText to type text.")
    suspend fun sendVirtualDisplayKey(keyCode: Int): Boolean {
        require(keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ESCAPE)) {
            "Only navigation and editing keys are supported in virtual display tasks"
        }
        return injectKeyEvent(keyCode)
    }

    suspend fun injectKeyEvent(keyCode: Int, longPress: Boolean = false, metaState: Int = 0): Boolean =
        inject { target ->
            ShizukuManager.injectKeyEvent(keyCode, longPress, metaState, target.display.displayId).getOrThrow()
        }

    @Tool
    @LLMDescription("Type characters supported by Android's virtual keyboard on the bound virtual display.")
    suspend fun injectText(text: String): Boolean = inject { target ->
        ShizukuManager.injectText(text, target.display.displayId).getOrThrow()
    }

    private suspend fun inject(send: suspend (ActiveDisplay) -> Boolean): Boolean = withActiveDisplay { target ->
        if (capture != null) withContext(Dispatchers.IO) { requireAgentPermission() }
        check(activeDisplay === target && target.display.isValid) { "The virtual display has stopped" }
        send(target)
    }

    private suspend fun <T> withActiveDisplay(send: suspend (ActiveDisplay) -> T): T =
        withContext(Dispatchers.Main.immediate) {
            val target = checkNotNull(activeDisplay) { "No active virtual display" }
            inputMutex.withLock {
                check(activeDisplay === target && target.display.isValid) { "The virtual display has stopped" }
                send(target)
            }
        }

    private fun releaseDisplay(nextState: State) {
        if (state.value == State.Closed) return
        val target = activeDisplay
        activeDisplay = null
        try {
            if (target?.remoteLease != null) displays.unregisterDisplayListener(remoteDisplayListener)
            target?.localHandle?.release()
        } finally {
            try {
                capture?.close()
            } finally {
                capture = null
                mutableState.value = nextState
            }
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === mainHandler.looper) { "Virtual display lifecycle calls must run on the main thread" }
    }

}
