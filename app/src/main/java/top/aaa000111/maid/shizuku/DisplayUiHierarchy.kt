package top.aaa000111.maid.shizuku

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.UiAutomation
import android.graphics.Rect
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.reflect.InvocationTargetException

/** Reads only the requested secondary display using a short-lived Shizuku UiAutomation connection. */
internal object DisplayUiHierarchy {
    private const val MAX_NODES = 160
    private const val MAX_OUTPUT_CHARS = 24_000

    @Synchronized
    fun dump(displayId: Int, maxDepth: Int): String {
        require(displayId > Display.DEFAULT_DISPLAY) { "A secondary display ID is required" }
        return try {
            dumpWithUiAutomation(displayId, maxDepth.coerceIn(1, 30))
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            throw IllegalStateException(cause.message ?: "Android UI automation failed", cause)
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Android UI automation is unavailable: ${error.message}", error)
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun dumpWithUiAutomation(displayId: Int, maxDepth: Int): String {
        val api = UiAutomation::class.java
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
        val callbacks = HandlerThread("maid-display-ui").apply { start() }
        try {
            val automation = api.getConstructor(Looper::class.java, connectionType)
                .newInstance(callbacks.looper, connection)
            val disconnect = api.getMethod("disconnect")
            try {
                api.getMethod("connect", Int::class.javaPrimitiveType)
                    .invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                }
                val windows = automation.windowsOnAllDisplays[displayId].orEmpty()
                return formatWindows(displayId, windows, maxDepth)
            } finally {
                runCatching { disconnect.invoke(automation) }
            }
        } finally {
            callbacks.quitSafely()
        }
    }

    private fun formatWindows(displayId: Int, windows: List<AccessibilityWindowInfo>, maxDepth: Int): String {
        val output = StringBuilder("displayId=$displayId")
        var remaining = MAX_NODES
        fun appendNode(node: AccessibilityNodeInfo, depth: Int) {
            if (remaining <= 0 || output.length >= MAX_OUTPUT_CHARS) return
            remaining--
            if (!node.isVisibleToUser) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val text = if (node.isPassword) "[password]" else node.text.singleLine()
            val description = if (node.isPassword) "" else node.contentDescription.singleLine()
            val id = node.viewIdResourceName.singleLine()
            val kind = node.className?.toString()?.substringAfterLast('.').singleLine()
            if (text.isNotEmpty() || description.isNotEmpty() || id.isNotEmpty() ||
                node.isClickable || node.isScrollable || node.isEditable
            ) {
                output.appendLine()
                output.append("  ".repeat(depth))
                output.append("$kind id=$id text='$text' desc='$description' ")
                output.append("clickable=${node.isClickable} editable=${node.isEditable} scrollable=${node.isScrollable} ")
                output.append("bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            }
            if (depth < maxDepth) {
                for (index in 0 until node.childCount) {
                    if (remaining <= 0 || output.length >= MAX_OUTPUT_CHARS) break
                    val child = node.getChild(index) ?: continue
                    try {
                        appendNode(child, depth + 1)
                    } finally {
                        @Suppress("DEPRECATION")
                        child.recycle()
                    }
                }
            }
        }

        try {
            for (window in windows) {
                if (remaining <= 0 || output.length >= MAX_OUTPUT_CHARS) break
                if (window.displayId != displayId || window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = window.root ?: continue
                try {
                    output.appendLine()
                    output.append("window package=${root.packageName.singleLine()}")
                    appendNode(root, 0)
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            windows.forEach { it.recycle() }
        }
        if (remaining == MAX_NODES) return "No UI nodes available on virtual display $displayId."
        if (remaining <= 0 || output.length >= MAX_OUTPUT_CHARS) output.append("\n[UI tree truncated]")
        return output.toString()
    }

    private fun CharSequence?.singleLine(): String =
        this?.take(160)?.toString()?.replace('\n', ' ')?.replace('\r', ' ').orEmpty()
}
