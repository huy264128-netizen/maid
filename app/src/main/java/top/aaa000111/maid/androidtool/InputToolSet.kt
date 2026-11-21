package top.aaa000111.maid.androidtool

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShizukuManager

/**
 * 输入与交互工具集：涵盖屏幕点击、滑动与文本输入。
 *
 * 支持权限：`SHIZUKU`、`ACCESSIBILITY`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class InputToolSet : AndroidToolSet() {

    override val name: String = "input"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.SHIZUKU,
        AndroidPrivilege.ACCESSIBILITY,
        AndroidPrivilege.ROOT,
    )

    @Tool
    @LLMDescription("Tap on the screen at the given (x, y) pixel coordinates.")
    suspend fun tap(
        @LLMDescription("Horizontal X coordinate in pixels.") x: Float,
        @LLMDescription("Vertical Y coordinate in pixels.") y: Float,
        @LLMDescription("Target display ID (default 0 for main display).") displayId: Int = 0,
    ): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.injectTap(x, y, displayId).getOrDefault(false)
            return if (ok) {
                "Tapped ($x, $y) on display $displayId via Shizuku"
            } else {
                "Failed to tap ($x, $y) via Shizuku"
            }
        }

        val accessibility = PrivilegeBackend.getAccessibilityService()
        if (accessibility != null) {
            val path = Path().apply { moveTo(x, y) }
            val ok = PrivilegeBackend.dispatchAccessibilityGesture(accessibility, path, 50L)
            return if (ok) {
                "Tapped ($x, $y) via Accessibility"
            } else {
                "Failed to tap ($x, $y) via Accessibility"
            }
        }

        if (PrivilegeBackend.isRootAvailable()) {
            val result = PrivilegeBackend.execRootShell("input tap $x $y")
            return if (result.isSuccess) {
                "Tapped ($x, $y) via Root"
            } else {
                "Failed to tap via Root: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
            }
        }

        return "No available privilege (Shizuku / Accessibility / Root) to perform tap"
    }

    @Tool
    @LLMDescription("Swipe on the screen from (startX, startY) to (endX, endY) over durationMs milliseconds.")
    suspend fun swipe(
        @LLMDescription("Start X coordinate in pixels.") startX: Float,
        @LLMDescription("Start Y coordinate in pixels.") startY: Float,
        @LLMDescription("End X coordinate in pixels.") endX: Float,
        @LLMDescription("End Y coordinate in pixels.") endY: Float,
        @LLMDescription("Swipe duration in milliseconds (default 300).") durationMs: Long = 300L,
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        val safeDuration = durationMs.coerceIn(50L, 10_000L)

        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.injectSwipe(startX, startY, endX, endY, safeDuration, displayId)
                .getOrDefault(false)
            return if (ok) {
                "Swiped ($startX, $startY) -> ($endX, $endY) in ${safeDuration}ms via Shizuku"
            } else {
                "Failed to swipe via Shizuku"
            }
        }

        val accessibility = PrivilegeBackend.getAccessibilityService()
        if (accessibility != null) {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val ok = PrivilegeBackend.dispatchAccessibilityGesture(accessibility, path, safeDuration)
            return if (ok) {
                "Swiped ($startX, $startY) -> ($endX, $endY) in ${safeDuration}ms via Accessibility"
            } else {
                "Failed to swipe via Accessibility"
            }
        }

        if (PrivilegeBackend.isRootAvailable()) {
            val result = PrivilegeBackend.execRootShell(
                "input swipe $startX $startY $endX $endY $safeDuration",
            )
            return if (result.isSuccess) {
                "Swiped ($startX, $startY) -> ($endX, $endY) in ${safeDuration}ms via Root"
            } else {
                "Failed to swipe via Root: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
            }
        }

        return "No available privilege (Shizuku / Accessibility / Root) to perform swipe"
    }

    @Tool
    @LLMDescription("Input text into the currently focused field or via system key character injection.")
    suspend fun inputText(
        @LLMDescription("Text content to input.") text: String,
        @LLMDescription("Target display ID (default 0).") displayId: Int = 0,
    ): String {
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.injectText(text, displayId).getOrDefault(false)
            return if (ok) {
                "Injected text (length=${text.length}) via Shizuku"
            } else {
                "Failed to inject text via Shizuku"
            }
        }

        val accessibility = PrivilegeBackend.getAccessibilityService()
        if (accessibility != null) {
            val focusedNode = findFocusedEditableNode(accessibility)
            if (focusedNode != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return if (ok) {
                    "Set text on focused node (length=${text.length}) via Accessibility"
                } else {
                    "Failed to set text on focused node via Accessibility"
                }
            }
            if (!PrivilegeBackend.isRootAvailable()) {
                return "No focused editable input node found via Accessibility"
            }
        }

        if (PrivilegeBackend.isRootAvailable()) {
            val escaped = text.replace(" ", "%s")
            val result = PrivilegeBackend.execRootShell("input text ${PrivilegeBackend.shellQuote(escaped)}")
            return if (result.isSuccess) {
                "Input text (length=${text.length}) via Root"
            } else {
                "Failed to input text via Root: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
            }
        }

        return "No available privilege (Shizuku / Accessibility / Root) to input text"
    }

    private fun findFocusedEditableNode(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocused != null && inputFocused.isEditable) return inputFocused

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isEditable && current.isFocused) return current
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let(queue::add)
            }
        }
        return inputFocused
    }
}
