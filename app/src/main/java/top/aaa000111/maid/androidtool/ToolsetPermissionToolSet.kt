package top.aaa000111.maid.androidtool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import top.aaa000111.maid.chat.ChatSession

/**
 * 单个 Android 工具集的权限元数据描述：
 * - [toolsetId]：标准工具集名称（如 `input`、`screen`、`shell` 等）；
 * - [displayTitle]：面向用户的中文工具集名称；
 * - [description]：工具集功能简介；
 * - [requiredPrivileges]：解锁该工具集的候选权限列表（满足其中任意一项即可解锁）；
 * - [aliases]：模型调用时可匹配的别名集合（大小写不敏感）。
 */
data class ToolsetPermissionSpec(
    val toolsetId: String,
    val displayTitle: String,
    val description: String,
    val requiredPrivileges: List<AndroidPrivilege>,
    val aliases: Set<String> = emptySet(),
) {
    fun isSatisfiedBy(availablePrivileges: Set<AndroidPrivilege>): Boolean {
        return requiredPrivileges.isEmpty() || requiredPrivileges.any { it in availablePrivileges }
    }

    fun matchedPrivileges(availablePrivileges: Set<AndroidPrivilege>): List<AndroidPrivilege> {
        return requiredPrivileges.filter { it in availablePrivileges }
    }
}

/**
 * 用户在权限请求卡片上点击“确定”后重新检查权限的返回结果。
 */
data class ToolsetPermissionCheckResult(
    val requestId: String,
    val toolsetId: String,
    val toolsetTitle: String,
    val requiredPrivileges: List<AndroidPrivilege>,
    val availablePrivileges: Set<AndroidPrivilege>,
    val matchedPrivileges: List<AndroidPrivilege>,
    val isGranted: Boolean,
    val summaryText: String,
)

/**
 * 内置 Android 工具集权限目录与权限检查辅助。
 *
 * 模型只需知道自己需要哪个 `toolset`（例如 `input`、`screen`、`shell`、`app_control`、`window`、`root_system`、`device_policy`），
 * 无需自行维护权限映射表。
 */
object ToolsetPermissionCatalog {

    val INPUT = ToolsetPermissionSpec(
        toolsetId = "input",
        displayTitle = "输入与手势交互工具集",
        description = "提供屏幕点击、滑动与文本输入能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ACCESSIBILITY,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "input",
            "input_toolset",
            "inputtoolset",
            "input_tools",
            "输入",
            "输入与手势",
            "输入工具集",
            "tap",
            "swipe",
            "inputtext",
        ),
    )

    val KEY_INPUT = ToolsetPermissionSpec(
        toolsetId = "key_input",
        displayTitle = "按键注入工具集",
        description = "注入 Android 键码及长按事件",
        requiredPrivileges = listOf(AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT),
        aliases = setOf("presskey", "按键注入"),
    )

    val SCREEN = ToolsetPermissionSpec(
        toolsetId = "screen",
        displayTitle = "屏幕观察与元素交互工具集",
        description = "提供屏幕截图、导出 UI 层级树、按文本/ID 查找或点击控件及执行系统全局动作能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.ACCESSIBILITY,
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "screen",
            "screen_toolset",
            "screentoolset",
            "screen_tools",
            "屏幕",
            "屏幕观察",
            "屏幕工具集",
            "capturescreenshot",
            "dumpuitree",
            "findnodesbytext",
            "clicknodebytext",
            "clicknodebyviewid",
        ),
    )

    val SHELL = ToolsetPermissionSpec(
        toolsetId = "shell",
        displayTitle = "通用 Shell 命令工具集",
        description = "提供通过 Shizuku 或 Root 执行通用 Shell 命令并获取标准输出与退出码的能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "shell",
            "shell_toolset",
            "shelltoolset",
            "shell_tools",
            "命令",
            "终端",
            "executeshell",
        ),
    )

    val APP_CONTROL = ToolsetPermissionSpec(
        toolsetId = "app_control",
        displayTitle = "应用与进程控制工具集",
        description = "提供查询前台 Activity、启动应用组件、强行停止应用、清理后台进程与列举运行进程能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "app_control",
            "appcontrol",
            "app_control_toolset",
            "appcontroltoolset",
            "app",
            "应用控制",
            "进程控制",
            "应用与进程控制",
            "gettopactivity",
            "startactivity",
            "forcestoppackage",
            "killbackgroundprocesses",
            "listrunningprocesses",
        ),
    )

    val WINDOW = ToolsetPermissionSpec(
        toolsetId = "window",
        displayTitle = "窗口与显示管理工具集",
        description = "提供查询屏幕分辨率/DPI/方向、调整显示尺寸与密度、控制屏幕旋转及检查锁屏状态能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "window",
            "window_toolset",
            "windowtoolset",
            "window_tools",
            "display",
            "窗口",
            "显示管理",
            "窗口与显示",
            "getdisplayinfo",
            "setdisplaysize",
            "setdisplaydensity",
            "setdisplayrotation",
            "iskeyguardlocked",
        ),
    )

    val ROOT_SYSTEM = ToolsetPermissionSpec(
        toolsetId = "root_system",
        displayTitle = "Root 级系统管理工具集",
        description = "提供 UID=0 Root Shell 执行、系统属性读写、受保护文件读取及系统设置项修改能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.ROOT_SHIZUKU,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "root_system",
            "rootsystem",
            "root_system_toolset",
            "rootsystemtoolset",
            "root",
            "root_tools",
            "系统管理",
            "root系统管理",
            "executerootShell",
            "executerootshell",
            "getsystemproperty",
            "setsystemproperty",
            "readprotectedfile",
        ),
    )

    val DEVICE_POLICY = ToolsetPermissionSpec(
        toolsetId = "device_policy",
        displayTitle = "设备管理员策略工具集",
        description = "基于设备所有者（Dhizuku）提供应用隐藏、应用冻结/停用、禁止卸载与立即锁屏能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.DHIZUKU,
        ),
        aliases = setOf(
            "device_policy",
            "devicepolicy",
            "device_policy_toolset",
            "devicepolicytoolset",
            "dhizuku",
            "设备策略",
            "设备管理员",
            "setapplicationhidden",
            "setpackagesuspended",
            "setuninstallblocked",
            "lockdevicenow",
        ),
    )

    val FILE_MANAGEMENT = ToolsetPermissionSpec(
        toolsetId = "file_management",
        displayTitle = "文件管理与存储工具集",
        description = "通过所有文件访问权限（MANAGE_EXTERNAL_STORAGE）、Shizuku 或 Root 提供目录浏览、文本文件读写、创建目录、复制/移动/删除文件、按名称搜索文件及读取 content:// URI 能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.FILE_MANAGEMENT,
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "file_management",
            "filemanagement",
            "file_management_toolset",
            "filemanagementtoolset",
            "file",
            "files",
            "storage",
            "文件管理",
            "文件",
            "存储管理",
            "listdirectory",
            "readtextfile",
            "writetextfile",
            "createdirectory",
            "copyormovefile",
            "deletefileordirectory",
            "searchfiles",
            "getfiledetails",
        ),
    )

    val USAGE_STATS = ToolsetPermissionSpec(
        toolsetId = "usage_stats",
        displayTitle = "应用使用情况统计工具集",
        description = "通过使用情况访问权限（PACKAGE_USAGE_STATS）、Shizuku 或 Root 提供最近活跃前台应用查询与各应用前台使用时长统计能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.USAGE_STATS,
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "usage_stats",
            "usagestats",
            "usage_stats_toolset",
            "usagestatstoolset",
            "usage",
            "使用情况",
            "应用使用情况",
            "getrecentforegroundapps",
            "getappusagesummary",
        ),
    )

    val SYSTEM_SETTINGS = ToolsetPermissionSpec(
        toolsetId = "system_settings",
        displayTitle = "普通系统设置读写工具集",
        description = "通过修改系统设置权限（WRITE_SETTINGS）、Shizuku 或 Root 提供屏幕亮度、自动亮度模式和息屏超时时间读写能力",
        requiredPrivileges = listOf(
            AndroidPrivilege.WRITE_SETTINGS,
            AndroidPrivilege.SHIZUKU,
            AndroidPrivilege.ROOT,
        ),
        aliases = setOf(
            "system_settings",
            "systemsettings",
            "system_settings_toolset",
            "systemsettingstoolset",
            "write_settings",
            "writesettings",
            "系统设置",
            "getdisplaysettings",
            "setscreenbrightness",
            "setscreenofftimeout",
        ),
    )

    val AUDIO = ToolsetPermissionSpec(
        toolsetId = "audio",
        displayTitle = "音量工具集",
        description = "读取与调节音量，遵循设备音量和勿扰策略",
        requiredPrivileges = emptyList(),
        aliases = setOf("getstreamvolumes", "setstreamvolume", "音量"),
    )

    val allSpecs: List<ToolsetPermissionSpec> = listOf(
        INPUT,
        KEY_INPUT,
        SCREEN,
        SHELL,
        APP_CONTROL,
        WINDOW,
        ROOT_SYSTEM,
        DEVICE_POLICY,
        FILE_MANAGEMENT,
        USAGE_STATS,
        SYSTEM_SETTINGS,
        AUDIO,
    )

    fun resolve(
        rawToolsetName: String,
        customToolSets: Iterable<AndroidToolSet> = emptyList(),
    ): ToolsetPermissionSpec? {
        val cleaned = rawToolsetName.trim()
        if (cleaned.isEmpty()) return null
        val normalized = normalizeKey(cleaned)

        allSpecs.firstOrNull { spec ->
            normalizeKey(spec.toolsetId) == normalized ||
                normalizeKey(spec.displayTitle) == normalized ||
                spec.aliases.any { normalizeKey(it) == normalized }
        }?.let { return it }

        allSpecs.firstOrNull { spec ->
            normalized.contains(normalizeKey(spec.toolsetId)) ||
                spec.aliases.any { alias ->
                    val normAlias = normalizeKey(alias)
                    normAlias.length >= 4 && normalized.contains(normAlias)
                }
        }?.let { return it }

        customToolSets.firstOrNull { custom ->
            normalizeKey(custom.name) == normalized ||
                normalizeKey(custom::class.simpleName.orEmpty()) == normalized
        }?.let { custom ->
            return ToolsetPermissionSpec(
                toolsetId = custom.name,
                displayTitle = custom.name,
                description = "自定义工具集 ${custom.name}",
                requiredPrivileges = custom.requiredPrivileges().toList(),
                aliases = setOf(custom.name, custom::class.simpleName.orEmpty()),
            )
        }

        return null
    }

    fun privilegeShortLabel(privilege: AndroidPrivilege): String = when (privilege) {
        AndroidPrivilege.ACCESSIBILITY -> "无障碍服务"
        AndroidPrivilege.SHIZUKU -> "Shizuku"
        AndroidPrivilege.DHIZUKU -> "Dhizuku (设备所有者)"
        AndroidPrivilege.ROOT -> "Root (su)"
        AndroidPrivilege.ROOT_SHIZUKU -> "Root 模式 Shizuku"
        AndroidPrivilege.FILE_MANAGEMENT -> "所有文件访问权限"
        AndroidPrivilege.USAGE_STATS -> "应用使用情况访问权限"
        AndroidPrivilege.WRITE_SETTINGS -> "修改系统设置权限"
        AndroidPrivilege.SYSTEM_ALERT_WINDOW -> "悬浮窗权限"
    }

    fun privilegeDisplayName(privilege: AndroidPrivilege): String = when (privilege) {
        AndroidPrivilege.ACCESSIBILITY -> "无障碍服务 (ACCESSIBILITY)"
        AndroidPrivilege.SHIZUKU -> "Shizuku (SHIZUKU)"
        AndroidPrivilege.DHIZUKU -> "Dhizuku 设备所有者 (DHIZUKU)"
        AndroidPrivilege.ROOT -> "Root 权限 (ROOT)"
        AndroidPrivilege.ROOT_SHIZUKU -> "Root 模式 Shizuku (ROOT_SHIZUKU)"
        AndroidPrivilege.FILE_MANAGEMENT -> "所有文件访问权限 (FILE_MANAGEMENT)"
        AndroidPrivilege.USAGE_STATS -> "使用情况访问权限 (USAGE_STATS)"
        AndroidPrivilege.WRITE_SETTINGS -> "修改系统设置权限 (WRITE_SETTINGS)"
        AndroidPrivilege.SYSTEM_ALERT_WINDOW -> "悬浮窗权限 (SYSTEM_ALERT_WINDOW)"
    }

    suspend fun checkCurrentPrivileges(
        requiredPrivileges: Set<AndroidPrivilege> = emptySet(),
    ): Set<AndroidPrivilege> {
        val shouldCheckRoot = requiredPrivileges.isEmpty() ||
            AndroidPrivilege.ROOT in requiredPrivileges ||
            AndroidPrivilege.ROOT_SHIZUKU in requiredPrivileges
        return if (PrivilegeManager.isInitialized) {
            runCatching {
                PrivilegeManager.refreshOverview(checkRoot = shouldCheckRoot).availablePrivileges
            }.getOrDefault(emptySet())
        } else {
            PrivilegeManager.overview.value.availablePrivileges
        }
    }

    fun buildResultSummary(
        spec: ToolsetPermissionSpec,
        availablePrivileges: Set<AndroidPrivilege>,
    ): ToolsetPermissionCheckResult {
        val matched = spec.matchedPrivileges(availablePrivileges)
        val granted = spec.isSatisfiedBy(availablePrivileges)
        val summary = if (granted) {
            val matchedLabels = if (matched.isEmpty()) {
                "无需额外权限"
            } else {
                matched.joinToString("、") { privilegeShortLabel(it) }
            }
            "权限检查通过：已满足所需权限（当前已就绪：$matchedLabels），工具集「${spec.displayTitle} (${spec.toolsetId})」已解锁可用。"
        } else {
            val requiredLabels = spec.requiredPrivileges.joinToString(" / ") { privilegeShortLabel(it) }
            "权限检查未通过：仍未检测到可用权限。工具集「${spec.displayTitle} (${spec.toolsetId})」需要开启以下权限之一：$requiredLabels。"
        }
        return ToolsetPermissionCheckResult(
            requestId = "",
            toolsetId = spec.toolsetId,
            toolsetTitle = spec.displayTitle,
            requiredPrivileges = spec.requiredPrivileges,
            availablePrivileges = availablePrivileges,
            matchedPrivileges = matched,
            isGranted = granted,
            summaryText = summary,
        )
    }

    /**
     * 当模型请求一个**当前已经满足权限**的工具集时，无需向聊天界面发送重复的权限申请卡片，
     * 直接构造已就绪结果返回给模型，并明确提醒不要重复申请已就绪的工具集。
     */
    fun buildAlreadySatisfiedSummary(
        spec: ToolsetPermissionSpec,
        availablePrivileges: Set<AndroidPrivilege>,
    ): ToolsetPermissionCheckResult {
        val matched = spec.matchedPrivileges(availablePrivileges)
        val matchedLabels = if (matched.isEmpty()) {
            "无需额外权限"
        } else {
            matched.joinToString("、") { privilegeShortLabel(it) }
        }
        val summary = "无需重复申请：工具集「${spec.displayTitle} (${spec.toolsetId})」当前已满足所需权限（已就绪：$matchedLabels），" +
            "系统已直接放行（未向用户发送多余的权限请求卡片）。请勿再次对该已就绪工具集调用 requestToolsetPermission，请直接调用对应执行工具完成任务。"
        return ToolsetPermissionCheckResult(
            requestId = "already_granted_${spec.toolsetId}",
            toolsetId = spec.toolsetId,
            toolsetTitle = spec.displayTitle,
            requiredPrivileges = spec.requiredPrivileges,
            availablePrivileges = availablePrivileges,
            matchedPrivileges = matched,
            isGranted = true,
            summaryText = summary,
        )
    }

    fun formatToolOutput(result: ToolsetPermissionCheckResult): String = buildString {
        appendLine("toolset=${result.toolsetId} (${result.toolsetTitle})")
        appendLine("granted=${result.isGranted}")
        appendLine("alreadySatisfied=${result.requestId.startsWith("already_granted_")}")
        appendLine("requiredAnyOf=${result.requiredPrivileges.joinToString(",") { it.name }}")
        appendLine("matchedPrivileges=${result.matchedPrivileges.joinToString(",") { it.name }.ifEmpty { "NONE" }}")
        appendLine("availablePrivileges=${result.availablePrivileges.joinToString(",") { it.name }.ifEmpty { "NONE" }}")
        append("summary=${result.summaryText}")
    }

    fun buildUnknownToolsetMessage(rawToolsetName: String): String {
        val validList = allSpecs.joinToString(", ") { spec ->
            "'${spec.toolsetId}' (${spec.displayTitle})"
        }
        return "未知的 toolset 名称 '$rawToolsetName'。你只需要传入以下标准 toolset 名称之一即可：$validList。"
    }

    fun buildPromptGuidance(
        availablePrivileges: Set<AndroidPrivilege> = emptySet(),
    ): String = buildString {
        appendLine("[工具集权限请求说明（requestToolsetPermission）]")
        appendLine("当你需要使用某个 Android 工具集但当前权限不足（或需要先向用户申请对应工具集的权限）时，请调用 `requestToolsetPermission(toolsetName)` 工具。")
        appendLine("你只需要知道并传入自己需要的 `toolsetName` 即可，系统会自动向用户发送权限请求卡片、列出该工具集所需的候选权限（满足其中之一即可），并在用户点击“确定”后重新检查权限并返回结果。")
        appendLine("【特别重要原则：严禁重复申请已拥有的能力】")
        appendLine("- 在调用 `requestToolsetPermission` 之前，请务必核对下方当前已解锁/已满足的工具集列表；")
        appendLine("- 凡是已经被标记为【已就绪/无需申请】的工具集，系统与设备执行器已经具备对应执行能力，你绝对不要再次调用 `requestToolsetPermission` 向用户申请！（直接通过 `planAndExecuteTask` 或 `executeDeviceTask` 执行即可）")
        appendLine("- 只有当任务确实需要使用【未就绪】列表中的工具集时，才针对性地调用 `requestToolsetPermission`。")

        val unlockedSpecs = allSpecs.filter { it.isSatisfiedBy(availablePrivileges) }
        val lockedSpecs = allSpecs.filter { !it.isSatisfiedBy(availablePrivileges) }
        if (unlockedSpecs.isNotEmpty() || availablePrivileges.isNotEmpty()) {
            val activePrivilegeLabels = availablePrivileges.joinToString("、") { privilegeShortLabel(it) }
                .ifEmpty { "无额外设备权限" }
            appendLine()
            appendLine("[当前设备已就绪权限与工具集状态（已持有的权限：$activePrivilegeLabels）]")
            if (unlockedSpecs.isNotEmpty()) {
                appendLine("以下 ${unlockedSpecs.size} 个工具集【已就绪/无需申请】，已经可以直接使用，严禁对它们调用 `requestToolsetPermission`：")
                unlockedSpecs.forEach { spec ->
                    val matchedLabels = spec.matchedPrivileges(availablePrivileges).joinToString("、") { privilegeShortLabel(it) }
                        .ifEmpty { "无需额外权限" }
                    appendLine("- `${spec.toolsetId}`（${spec.displayTitle}）——【已就绪/无需申请】（满足条件：$matchedLabels）")
                }
            }
            if (lockedSpecs.isNotEmpty()) {
                appendLine("仅以下 ${lockedSpecs.size} 个工具集当前权限【未就绪】，若任务必须使用才可调用 `requestToolsetPermission` 申请：")
                lockedSpecs.forEach { spec ->
                    val needed = spec.requiredPrivileges.joinToString(" / ") { privilegeShortLabel(it) }
                    appendLine("- `${spec.toolsetId}`（${spec.displayTitle}）：${spec.description}（需开启其一：$needed）")
                }
            } else {
                appendLine("当前所有内置 Android 工具集均【已全部就绪】，本次任务严禁调用 `requestToolsetPermission`！")
            }
        } else {
            appendLine()
            appendLine("可用 `toolsetName` 列表（当前尚未检测到任何已开启的设备权限）：")
            allSpecs.forEach { spec ->
                appendLine("- `${spec.toolsetId}`（${spec.displayTitle}）：${spec.description}")
            }
        }
    }.trimEnd()

    private fun normalizeKey(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace("-", "_")
            .replace(" ", "")
    }
}

/**
 * 向用户请求指定 Android 工具集权限的 Koog [ToolSet]：
 * 模型只需指定所需的 `toolsetName`（如 `input`、`screen`、`shell`、`app_control`、`window`、`root_system`、`device_policy`），
 * 调用时会在当前 [ChatSession] 中发送一张权限请求卡片，告知用户需要该 toolset 因此需要候选权限列表中的任意一项权限；
 * 待用户在卡片上点击“确定”后，立即重新检查当前权限并将最新检查结果返回给模型。
 */
class ToolsetPermissionToolSet(
    private val session: ChatSession,
    private val privilegeChecker: (suspend (Set<AndroidPrivilege>) -> Set<AndroidPrivilege>)? = null,
    private val customToolSets: List<AndroidToolSet> = emptyList(),
) : ToolSet {

    override val name: String = TOOLSET_NAME

    @Tool
    @LLMDescription(
        "Request permission from the user ONLY for an Android toolset whose required privilege is NOT currently satisfied. " +
            "IMPORTANT: Do NOT call this tool for any toolset already marked as ready/unlocked ('已就绪/无需申请') in the system prompt! " +
            "Pass the toolsetName you need (one of: 'input', 'key_input', 'screen', 'shell', 'app_control', 'window', 'root_system', 'device_policy', 'file_management', 'usage_stats', 'system_settings'). " +
            "If the toolset is already satisfied, it immediately returns granted=true without showing any card. " +
            "Otherwise, it sends a permission request card to the chat UI and waits for the user to click Confirm ('确定').",
    )
    suspend fun requestToolsetPermission(
        @LLMDescription(
            "Identifier of a currently LOCKED/UNGRANTED toolset that is required for the task. " +
                "Never pass a toolset that is already ready/unlocked. Valid values: " +
                "'input' (tap/swipe/text input), " +
                "'key_input' (Android keycodes and long press), " +
                "'screen' (screenshot/UI tree/node click/global action), " +
                "'shell' (general shell commands), " +
                "'app_control' (top activity/start/stop apps/processes), " +
                "'window' (display size/density/rotation/keyguard), " +
                "'root_system' (UID 0 root shell/sysprop/protected files), " +
                "'device_policy' (Dhizuku hide/suspend/uninstall-block/lock), " +
                "'file_management' (standard Android file/directory browse, read, write, copy, move, delete, search), " +
                "'usage_stats' (standard Android recent foreground apps and app usage duration), " +
                "'system_settings' (standard Android screen brightness, auto-brightness, screen-off timeout).",
        )
        toolsetName: String,
    ): String {
        val spec = ToolsetPermissionCatalog.resolve(
            rawToolsetName = toolsetName,
            customToolSets = customToolSets,
        ) ?: return ToolsetPermissionCatalog.buildUnknownToolsetMessage(toolsetName)

        val checkResult = session.requestToolsetPermissionAndWait(
            spec = spec,
            privilegeChecker = privilegeChecker,
        )
        return ToolsetPermissionCatalog.formatToolOutput(checkResult)
    }

    companion object {
        const val TOOLSET_NAME = "toolset_permission"
    }
}
