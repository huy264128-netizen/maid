package top.aaa000111.maid.chat

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.executeTool
import ai.koog.prompt.message.Message
import kotlinx.coroutines.currentCoroutineContext
import top.aaa000111.maid.learning.recordToolExecution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 协程上下文元素：用于在 `ChatAgent` -> `PlannerLifecycleManager` -> `PlannerAgent` -> `DeviceActionAgent`
 * 的跨层调用链中自动传递当前所属的 [ChatSession]，以便任何层级的工具调用都能实时在会话界面中展示。
 */
class ChatSessionContext(
    val session: ChatSession,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChatSessionContext>
}

/**
 * 从当前协程上下文中获取活跃的 [ChatSession]（若存在）。
 */
suspend fun currentChatSession(): ChatSession? {
    return currentCoroutineContext()[ChatSessionContext]?.session
}

/**
 * 工具调用展示文案与参数/结果摘要格式化器。
 */
object ToolCallDisplayFormatter {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val knownToolLabels: Map<String, String> = mapOf(
        // 会话 / 权限 / 视觉 / 规划工具
        "output" to "输出消息",
        "sendFileCard" to "发送文件卡片",
        "requestToolsetPermission" to "请求工具集权限",
        "analyzeImage" to "视觉模型识图",
        "readImage" to "直接读取图片",
        "planAndExecuteTask" to "委托复杂任务规划",
        "setCheckpoints" to "设置规划检查点",
        "updateLifecycleNote" to "记录规划工作记忆",
        "finish" to "推进/完成规划检查点",
        "executeDeviceTask" to "执行设备操作任务",
        // ResearchAgent / 分类联网检索与网页阅读工具
        "executeResearchTask" to "委托联网资料调研",
        "checkSearchEngines" to "探测可用搜索引擎",
        "searchWeb" to "通用网页搜索",
        "searchAcademic" to "学术论文搜索 (arXiv)",
        "searchCodeAndIssues" to "开源代码与 Issue 搜索 (GitHub)",
        "searchTechCommunity" to "技术社区讨论搜索 (HN)",
        "searchEncyclopedia" to "百科词条搜索 (Wikipedia)",
        "readWebPage" to "阅读网页正文",
        "readRawPage" to "读取原始页面文本",
        "webSearch" to "执行网页搜索",
        "multiEngineWebSearch" to "多引擎联合搜索",
        // ProotLinuxAgent / 专用 PRoot Linux 终端与文件 I/O 工具
        "executeProotLinuxTask" to "执行 PRoot Linux 任务",
        "executeProotLinuxTaskWithLlmApi" to "执行带 LLM API 变量的 PRoot Linux 任务",
        "executeProotCommand" to "执行 PRoot 终端命令",
        "getProotTerminalStatus" to "查询 PRoot 终端状态",
        "resetProotTerminal" to "重置 PRoot 专用终端",
        "listSessionAttachments" to "查看会话附件列表",
        "importSessionFileToProot" to "导入会话文件到 PRoot",
        "exportProotFileToSession" to "导出 PRoot 文件到会话",
        "transferFileBetweenHostAndProot" to "宿主与 PRoot 文件传输",
        "readProotTextFile" to "读取 PRoot 文本文件",
        "writeProotTextFile" to "写入 PRoot 文本文件",
        // InputToolSet
        "tap" to "点击屏幕",
        "doubleTap" to "双击屏幕",
        "longPress" to "长按屏幕",
        "swipe" to "滑动屏幕",
        "pressKey" to "发送按键",
        "inputText" to "输入文本",
        // ScreenToolSet
        "captureScreenshot" to "截取屏幕",
        "dumpUiTree" to "导出 UI 层级树",
        "findNodesByText" to "按文本查找控件",
        "clickNodeByText" to "按文本点击控件",
        "clickNodeByViewId" to "按 ID 点击控件",
        "performGlobalAction" to "执行系统全局动作",
        // ShellToolSet
        "executeShell" to "执行 Shell 命令",
        // AppControlToolSet
        "getTopActivity" to "查询前台 Activity",
        "startActivity" to "启动应用组件",
        "forceStopPackage" to "强行停止应用",
        "killBackgroundProcesses" to "清理后台进程",
        "listRunningProcesses" to "列举运行中进程",
        // WindowToolSet
        "getDisplayInfo" to "获取屏幕显示信息",
        "setDisplaySize" to "调整屏幕分辨率",
        "setDisplayDensity" to "调整屏幕 DPI",
        "setDisplayRotation" to "设置屏幕旋转方向",
        "isKeyguardLocked" to "检查锁屏状态",
        // RootSystemToolSet
        "executeRootShell" to "执行 Root Shell",
        "getSystemProperty" to "读取系统属性",
        "setSystemProperty" to "修改系统属性",
        "readProtectedFile" to "读取受保护文件",
        "putSystemSetting" to "修改系统设置项",
        // DevicePolicyToolSet
        "getDhizukuInfo" to "查询 Dhizuku 状态",
        "setApplicationHidden" to "隐藏/显示应用",
        "setPackageSuspended" to "冻结/解冻应用",
        "setPackagesSuspended" to "冻结/解冻应用",
        "setUninstallBlocked" to "设置应用防卸载",
        "lockDeviceNow" to "立即锁屏",
        "lockScreenNow" to "立即锁屏",
        // FileManagementToolSet
        "listDirectory" to "浏览目录文件",
        "readTextFile" to "读取文本文件",
        "writeTextFile" to "写入文本文件",
        "createDirectory" to "创建目录",
        "copyOrMoveFile" to "复制/移动文件",
        "deleteFileOrDirectory" to "删除文件/目录",
        "searchFiles" to "搜索文件",
        "getFileDetails" to "查看文件详情",
        "importFileToSession" to "移入文件到会话",
        "exportFileFromSession" to "从会话导出文件",
        // UsageStatsToolSet
        "getRecentForegroundApps" to "查询最近前台应用",
        "getAppUsageSummary" to "查询应用使用时长统计",
        // SystemSettingsToolSet
        "getDisplaySettings" to "读取显示设置",
        "setScreenBrightness" to "调整屏幕亮度",
        "setScreenOffTimeout" to "设置息屏超时时间",
        "getStreamVolumes" to "读取音频流音量",
        "setStreamVolume" to "调整音频流音量",
    )

    /**
     * 生成包含具体工具名称与中文说明的展示标签，例如：`tap（点击屏幕）` 或 `getBatteryLevel`。
     */
    fun formatToolDisplayLabel(toolName: String): String {
        val cleaned = toolName.trim()
        val cnLabel = knownToolLabels[cleaned]
        return if (cnLabel != null) {
            "$cleaned（$cnLabel）"
        } else {
            cleaned
        }
    }

    /**
     * 生成工具状态卡片标题，带有可选智能体来源前缀与具体工具名。
     */
    fun formatCardTitle(
        toolName: String,
        agentScopeLabel: String = "",
    ): String {
        val prefix = if (agentScopeLabel.isNotBlank()) "[$agentScopeLabel] " else ""
        return "${prefix}调用工具：${formatToolDisplayLabel(toolName)}"
    }

    /**
     * 将工具调用 JSON 参数格式化为简洁可读的单行摘要。
     */
    fun formatArgumentsSummary(
        rawArgsJson: String,
        maxLength: Int = 120,
    ): String {
        val trimmed = rawArgsJson.trim()
        if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "null") {
            return "无参数"
        }
        val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        val formatted = when (parsed) {
            is JsonObject -> {
                if (parsed.isEmpty()) {
                    "无参数"
                } else {
                    parsed.entries.joinToString(", ") { (k, v) ->
                        "$k=${formatJsonElementCompact(v)}"
                    }
                }
            }
            null -> trimmed.replace('\n', ' ')
            else -> formatJsonElementCompact(parsed)
        }
        return truncateSingleLine(formatted, maxLength)
    }

    /**
     * 格式化工具执行完成后的详情文本（包含参数摘要与返回结果摘要）。
     */
    fun formatCompletedDetail(
        argsSummary: String,
        rawResultText: String,
        maxResultLength: Int = 140,
    ): String {
        val cleanedResult = truncateSingleLine(
            rawResultText.trim().ifEmpty { "已完成" },
            maxResultLength,
        )
        return if (argsSummary.isBlank() || argsSummary == "无参数") {
            "结果：$cleanedResult"
        } else {
            "$argsSummary\n结果：$cleanedResult"
        }
    }

    private fun formatJsonElementCompact(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> truncateSingleLine(element.content, 60)
        is JsonArray -> "[${element.joinToString(", ") { formatJsonElementCompact(it) }}]"
        is JsonObject -> "{${element.entries.joinToString(", ") { (k, v) -> "$k:${formatJsonElementCompact(v)}" }}}"
    }

    private fun truncateSingleLine(text: String, maxLength: Int): String {
        val singleLine = text.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= maxLength) {
            singleLine
        } else {
            singleLine.take(maxLength) + "…"
        }
    }
}

/**
 * 替代默认 `nodeExecuteTool` 的策略节点构造函数：
 * - 保持与 Koog `nodeExecuteTool` 完全一致的单节点步数（不额外增加 `maxAgentIterations` 步数消耗）；
 * - 在调用具体工具前，向当前 [ChatSession] 写入“执行中”的 [ChatItem.ToolStatusItem] 并更新 `activeToolName`；
 * - 在工具执行结束后，将对应 [ChatItem.ToolStatusItem] 更新为“已完成”并附带结果摘要。
 */
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeExecuteToolWithStatus(
    name: String,
    agentScopeLabel: String = "",
    sessionProvider: (suspend () -> ChatSession?)? = null,
): AIAgentNodeDelegate<Message.Tool.Call, ReceivedToolResult> =
    node(name = name) { toolCall ->
        val session = sessionProvider?.invoke() ?: currentChatSession()
        val displayLabel = ToolCallDisplayFormatter.formatToolDisplayLabel(toolCall.tool)
        val cardTitle = ToolCallDisplayFormatter.formatCardTitle(
            toolName = toolCall.tool,
            agentScopeLabel = agentScopeLabel,
        )
        val argsSummary = ToolCallDisplayFormatter.formatArgumentsSummary(toolCall.content)
        val statusItemId = session?.beginToolCall(
            toolDisplayName = displayLabel,
            title = cardTitle,
            detail = argsSummary,
        )
        try {
            val result = recordToolExecution(toolCall.tool, agentScopeLabel, statusItemId) {
                environment.executeTool(toolCall)
            }
            if (session != null && statusItemId != null) {
                session.endToolCall(
                    itemId = statusItemId,
                    toolDisplayName = displayLabel,
                    completedDetail = ToolCallDisplayFormatter.formatCompletedDetail(
                        argsSummary = argsSummary,
                        rawResultText = result.content,
                    ),
                )
            }
            result
        } catch (error: Throwable) {
            if (session != null && statusItemId != null) {
                session.endToolCall(
                    itemId = statusItemId,
                    toolDisplayName = displayLabel,
                    completedDetail = ToolCallDisplayFormatter.formatCompletedDetail(
                        argsSummary = argsSummary,
                        rawResultText = "执行异常：${error.message ?: error::class.simpleName}",
                    ),
                )
            }
            throw error
        }
    }
