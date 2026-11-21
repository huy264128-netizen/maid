package top.aaa000111.maid.androidtool

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShizukuManager
import java.io.File

/**
 * 屏幕界面读取、截屏与元素级交互工具集：支持屏幕截图、导出 UI 层级树、按文本/ID 点击控件及执行系统全局动作。
 *
 * 支持权限：`ACCESSIBILITY`、`SHIZUKU`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class ScreenToolSet(
    context: Context,
) : AndroidToolSet() {

    private val appContext by lazy { context.applicationContext }

    override val name: String = "screen"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.ACCESSIBILITY,
        AndroidPrivilege.SHIZUKU,
        AndroidPrivilege.ROOT,
    )

    @Tool
    @LLMDescription("Capture a screenshot of the current device screen and save it to app cache. Optionally return base64-encoded image content.")
    suspend fun captureScreenshot(
        @LLMDescription("Target display ID (default 0).") displayId: Int = Display.DEFAULT_DISPLAY,
        @LLMDescription("Image format: 'jpeg' or 'png' (default 'jpeg').") format: String = "jpeg",
        @LLMDescription("Resize longest edge to at most maxDimension pixels (0 keeps original resolution; default 1280).") maxDimension: Int = 1280,
        @LLMDescription("JPEG compression quality 10..100 (default 85).") quality: Int = 85,
        @LLMDescription("Include base64-encoded image string in the output (default false).") includeBase64: Boolean = false,
    ): String {
        val captured = PrivilegeBackend.captureScreenshot(
            context = appContext,
            displayId = displayId,
            format = format,
            maxDimension = maxDimension,
            quality = quality,
        ).getOrElse { error ->
            return "Failed to capture screenshot: ${error.message}"
        }

        return buildString {
            appendLine("backend=${captured.backend}")
            appendLine("filePath=${captured.filePath}")
            appendLine("mimeType=${captured.mimeType}")
            appendLine("width=${captured.width} height=${captured.height} byteSize=${captured.byteSize}")
            if (includeBase64) {
                val bytes = runCatching { File(captured.filePath).readBytes() }.getOrNull()
                if (bytes != null) {
                    appendLine("base64=${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
                } else {
                    appendLine("base64Error=failed to read file bytes")
                }
            }
        }.trimEnd()
    }

    @Tool
    @LLMDescription("Dump the visible UI hierarchy tree with text, resource IDs, clickability, and bounds.")
    suspend fun dumpUiTree(
        @LLMDescription("Maximum hierarchy depth when using Accessibility (default 15).") maxDepth: Int = 15,
        @LLMDescription("Include nodes not currently visible to user (default false).") includeInvisible: Boolean = false,
    ): String {
        val accessibility = PrivilegeBackend.getAccessibilityService()
        if (accessibility != null) {
            val root = accessibility.rootInActiveWindow
                ?: return "Accessibility service connected, but active window root is currently unavailable"
            val lines = mutableListOf<String>()
            appendNodeTree(
                node = root,
                depth = 0,
                maxDepth = maxDepth.coerceIn(1, 40),
                includeInvisible = includeInvisible,
                out = lines,
            )
            return if (lines.isEmpty()) {
                "No visible UI nodes found"
            } else {
                "backend=accessibility package=${root.packageName}\n" + lines.joinToString("\n")
            }
        }

        val xml = dumpXmlViaShell()
            ?: return "Failed to dump UI hierarchy via Shizuku or Root"
        val nodes = PrivilegeBackend.parseUiAutomatorXml(xml)
        if (nodes.isEmpty()) {
            return "UI dump returned no nodes"
        }
        return buildString {
            appendLine("backend=uiautomator totalNodes=${nodes.size}")
            for (node in nodes.take(150)) {
                if (node.text.isBlank() && node.contentDesc.isBlank() && node.resourceId.isBlank() && !node.clickable) {
                    continue
                }
                appendLine(
                    "- class=${node.className.substringAfterLast('.')} " +
                        "id=${node.resourceId.ifBlank { "-" }} " +
                        "text=${node.text.ifBlank { "-" }} " +
                        "desc=${node.contentDesc.ifBlank { "-" }} " +
                        "clickable=${node.clickable} " +
                        "bounds=[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]",
                )
            }
        }.trimEnd()
    }

    @Tool
    @LLMDescription("Find UI nodes matching a text or content-description substring and return their bounds and info.")
    suspend fun findNodesByText(
        @LLMDescription("Text or description keyword to search for.") query: String,
        @LLMDescription("Whether to require an exact text match (default false).") exactMatch: Boolean = false,
    ): String {
        if (query.isBlank()) return "Query must not be blank"

        val accessibility = PrivilegeBackend.getAccessibilityService()
        if (accessibility != null) {
            val root = accessibility.rootInActiveWindow
                ?: return "Active window root is unavailable"
            val matches = collectAccessibilityNodes(root) { node ->
                matchesText(node.text?.toString(), query, exactMatch) ||
                    matchesText(node.contentDescription?.toString(), query, exactMatch)
            }
            if (matches.isEmpty()) return "No UI nodes matched '$query'"
            return matches.take(30).joinToString("\n") { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                "text='${node.text?.toString().orEmpty()}' desc='${node.contentDescription?.toString().orEmpty()}' " +
                    "id='${node.viewIdResourceName.orEmpty()}' clickable=${node.isClickable} " +
                    "bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
            }
        }

        val xml = dumpXmlViaShell() ?: return "Failed to dump UI hierarchy via Shizuku or Root"
        val matches = PrivilegeBackend.parseUiAutomatorXml(xml).filter { node ->
            matchesText(node.text, query, exactMatch) ||
                matchesText(node.contentDesc, query, exactMatch)
        }
        if (matches.isEmpty()) return "No UI nodes matched '$query'"
        return matches.take(30).joinToString("\n") { node ->
            "text='${node.text}' desc='${node.contentDesc}' id='${node.resourceId}' " +
                "clickable=${node.clickable} bounds=[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]"
        }
    }

    @Tool
    @LLMDescription("Find a UI element by text or content description and click it.")
    suspend fun clickNodeByText(
        @LLMDescription("Text or description to find and click.") query: String,
        @LLMDescription("Whether to require an exact match (default false).") exactMatch: Boolean = false,
    ): String {
        if (query.isBlank()) return "Query must not be blank"

        val accessibility = PrivilegeBackend.getAccessibilityService()
        if (accessibility != null) {
            val root = accessibility.rootInActiveWindow
                ?: return "Active window root is unavailable"
            val target = collectAccessibilityNodes(root).firstOrNull { node ->
                matchesText(node.text?.toString(), query, exactMatch) ||
                    matchesText(node.contentDescription?.toString(), query, exactMatch)
            } ?: return "No UI node matched '$query'"

            return clickAccessibilityNode(accessibility, target, "text '$query'")
        }

        val xml = dumpXmlViaShell() ?: return "Failed to dump UI hierarchy via Shizuku or Root"
        val target = PrivilegeBackend.parseUiAutomatorXml(xml).firstOrNull { node ->
            matchesText(node.text, query, exactMatch) ||
                matchesText(node.contentDesc, query, exactMatch)
        } ?: return "No UI node matched '$query'"

        return tapRectCenterViaShell(target.bounds, "text '$query'")
    }

    @Tool
    @LLMDescription("Find a UI element by its viewIdResourceName (full or suffix) and click it.")
    suspend fun clickNodeByViewId(
        @LLMDescription("View resource ID, e.g. 'com.example:id/submit' or 'submit'.") viewId: String,
    ): String {
        if (viewId.isBlank()) return "viewId must not be blank"

        val accessibility = PrivilegeBackend.getAccessibilityService()
        if (accessibility != null) {
            val root = accessibility.rootInActiveWindow
                ?: return "Active window root is unavailable"
            val target = collectAccessibilityNodes(root).firstOrNull { node ->
                val id = node.viewIdResourceName.orEmpty()
                id == viewId || id.endsWith("/$viewId") || id.endsWith(":id/$viewId")
            } ?: return "No UI node matched viewId '$viewId'"

            return clickAccessibilityNode(accessibility, target, "viewId '$viewId'")
        }

        val xml = dumpXmlViaShell() ?: return "Failed to dump UI hierarchy via Shizuku or Root"
        val target = PrivilegeBackend.parseUiAutomatorXml(xml).firstOrNull { node ->
            val id = node.resourceId
            id == viewId || id.endsWith("/$viewId") || id.endsWith(":id/$viewId")
        } ?: return "No UI node matched viewId '$viewId'"

        return tapRectCenterViaShell(target.bounds, "viewId '$viewId'")
    }

    @Tool
    @LLMDescription("Perform a global system navigation action: back, home, recents, notifications, quick_settings, power_dialog, lock_screen, or take_screenshot.")
    suspend fun performGlobalAction(
        @LLMDescription("Action name: back, home, recents, notifications, quick_settings, power_dialog, lock_screen, or take_screenshot.") action: String,
    ): String {
        val normalized = action.trim().lowercase()
        val accessibility = PrivilegeBackend.getAccessibilityService()
        if (accessibility != null) {
            val actionId = when (normalized) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                "take_screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                else -> null
            } ?: return "Unsupported global action: $action"

            val ok = accessibility.performGlobalAction(actionId)
            return if (ok) {
                "Performed global action '$normalized' via Accessibility"
            } else {
                "Accessibility rejected global action '$normalized'"
            }
        }

        val shellCmd = when (normalized) {
            "back" -> "input keyevent 4"
            "home" -> "input keyevent 3"
            "recents" -> "input keyevent 187"
            "notifications" -> "cmd statusbar expand-notifications"
            "quick_settings" -> "cmd statusbar expand-settings"
            "power_dialog" -> "input keyevent --longpress 26"
            "lock_screen" -> "input keyevent 223"
            "take_screenshot" -> "input keyevent 120"
            else -> return "Unsupported global action: $action"
        }

        val result = PrivilegeBackend.execShizukuOrRootShell(shellCmd)
        return if (result.isSuccess) {
            "Performed global action '$normalized' via shell"
        } else {
            "Failed to perform global action '$normalized': ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    private suspend fun dumpXmlViaShell(): String? {
        val result = PrivilegeBackend.execShizukuOrRootShell(
            shellCommand = "uiautomator dump /dev/tty 2>/dev/null || (uiautomator dump /data/local/tmp/maid_ui.xml >/dev/null && cat /data/local/tmp/maid_ui.xml && rm -f /data/local/tmp/maid_ui.xml)",
            timeoutMillis = 10_000L,
        )
        return result.stdout.takeIf { result.isSuccess && it.contains("<hierarchy") }
    }

    private suspend fun clickAccessibilityNode(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        label: String,
    ): String {
        var clickableNode: AccessibilityNodeInfo? = node
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }
        if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "Clicked $label via Accessibility node action"
        }

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) {
            return "Target $label is not clickable and has empty bounds"
        }
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val path = Path().apply { moveTo(cx, cy) }
        val ok = PrivilegeBackend.dispatchAccessibilityGesture(service, path, 50L)
        return if (ok) {
            "Clicked $label at ($cx, $cy) via Accessibility gesture"
        } else {
            "Failed to click $label"
        }
    }

    private suspend fun tapRectCenterViaShell(bounds: Rect, label: String): String {
        if (bounds.isEmpty) return "Target $label has empty bounds"
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        if (PrivilegeBackend.isShizukuReady()) {
            val ok = ShizukuManager.injectTap(cx, cy).getOrDefault(false)
            if (ok) return "Clicked $label at ($cx, $cy) via Shizuku"
        }
        val result = PrivilegeBackend.execShizukuOrRootShell("input tap $cx $cy")
        return if (result.isSuccess) {
            "Clicked $label at ($cx, $cy) via shell"
        } else {
            "Failed to click $label: ${result.stderr.ifBlank { result.errorMessage.orEmpty() }}"
        }
    }

    private fun appendNodeTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        includeInvisible: Boolean,
        out: MutableList<String>,
    ) {
        if (depth > maxDepth || out.size >= 200) return
        if (!includeInvisible && !node.isVisibleToUser) return

        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()

        val hasMeaning = text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty() ||
            node.isClickable || node.isEditable || node.isScrollable
        if (hasMeaning) {
            val indent = "  ".repeat(depth)
            out.add(
                "$indent[$cls] id=$id text='$text' desc='$desc' " +
                    "click=${node.isClickable} edit=${node.isEditable} scroll=${node.isScrollable} " +
                    "bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            appendNodeTree(child, depth + 1, maxDepth, includeInvisible, out)
        }
    }

    private fun collectAccessibilityNodes(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean = { true },
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (predicate(current)) {
                result.add(current)
            }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let(queue::add)
            }
        }
        return result
    }

    private fun matchesText(candidate: String?, query: String, exactMatch: Boolean): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return if (exactMatch) {
            candidate == query
        } else {
            candidate.contains(query, ignoreCase = true)
        }
    }
}
