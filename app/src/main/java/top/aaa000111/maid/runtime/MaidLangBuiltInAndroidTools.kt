package top.aaa000111.maid.runtime

import MaidValue
import android.content.Context
import kotlinx.coroutines.runBlocking
import top.aaa000111.maid.androidtool.AlarmClockToolSet
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.AppControlToolSet
import top.aaa000111.maid.androidtool.AudioToolSet
import top.aaa000111.maid.androidtool.DevicePolicyToolSet
import top.aaa000111.maid.androidtool.FileManagementToolSet
import top.aaa000111.maid.androidtool.InputToolSet
import top.aaa000111.maid.androidtool.KeyInputToolSet
import top.aaa000111.maid.androidtool.RootSystemToolSet
import top.aaa000111.maid.androidtool.ScreenToolSet
import top.aaa000111.maid.androidtool.ShellToolSet
import top.aaa000111.maid.androidtool.SystemSettingsToolSet
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog
import top.aaa000111.maid.androidtool.ToolsetPermissionSpec
import top.aaa000111.maid.androidtool.UsageStatsToolSet
import top.aaa000111.maid.androidtool.WindowToolSet

/**
 * 将应用内置的 13 个 Android 工具集（共 59 个 `@Tool` 能力）映射为 [MaidLangNativeEntry]，
 * 并附带所属 `toolsetId` 与解锁该工具集所需的候选 [AndroidPrivilege] 集合。
 */
internal object MaidLangBuiltInAndroidTools {

    fun buildEntries(contextProvider: () -> Context): List<MaidLangNativeEntry> {
        val inputToolSet = InputToolSet()
        val keyInputToolSet = KeyInputToolSet()
        val shellToolSet = ShellToolSet()
        val appControlToolSet = AppControlToolSet()
        val windowToolSet = WindowToolSet()
        val rootSystemToolSet = RootSystemToolSet()

        val audioToolSet = ContextBoundToolSet(contextProvider) { AudioToolSet(it) }
        val alarmClockToolSet = ContextBoundToolSet(contextProvider) { AlarmClockToolSet(it) }
        val screenToolSet = ContextBoundToolSet(contextProvider) { ScreenToolSet(it) }
        val devicePolicyToolSet = ContextBoundToolSet(contextProvider) { DevicePolicyToolSet(it) }
        val fileManagementToolSet = ContextBoundToolSet(contextProvider) { FileManagementToolSet(it) }
        val usageStatsToolSet = ContextBoundToolSet(contextProvider) { UsageStatsToolSet(it) }
        val systemSettingsToolSet = ContextBoundToolSet(contextProvider) { SystemSettingsToolSet(it) }

        return buildList {
            // 1. input
            addEntry(
                spec = ToolsetPermissionCatalog.INPUT,
                name = "tap",
                paramTypes = listOf(MaidLangNativeType.FLOAT, MaidLangNativeType.FLOAT, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "点击屏幕坐标 (x, y, displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        inputToolSet.tap(
                            x = args.floatVal(0),
                            y = args.floatVal(1),
                            displayId = args.intVal(2),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.INPUT,
                name = "swipe",
                paramTypes = listOf(
                    MaidLangNativeType.FLOAT,
                    MaidLangNativeType.FLOAT,
                    MaidLangNativeType.FLOAT,
                    MaidLangNativeType.FLOAT,
                    MaidLangNativeType.INT,
                    MaidLangNativeType.INT,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "滑动屏幕 (startX, startY, endX, endY, durationMs, displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        inputToolSet.swipe(
                            startX = args.floatVal(0),
                            startY = args.floatVal(1),
                            endX = args.floatVal(2),
                            endY = args.floatVal(3),
                            durationMs = args.positiveLongOrDefault(4, 300L),
                            displayId = args.intVal(5),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.INPUT,
                name = "inputText",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "向当前焦点输入文本 (text, displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        inputToolSet.inputText(
                            text = args.str(0),
                            displayId = args.intVal(1),
                        )
                    },
                )
            }

            // 2. key_input
            addEntry(
                spec = ToolsetPermissionCatalog.KEY_INPUT,
                name = "pressKey",
                paramTypes = listOf(MaidLangNativeType.INT, MaidLangNativeType.BOOL, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "注入 Android 按键事件 (keyCode, longPress, displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        keyInputToolSet.pressKey(
                            keyCode = args.intVal(0),
                            longPress = args.boolVal(1),
                            displayId = args.intVal(2),
                        )
                    },
                )
            }

            // 3. audio
            addEntry(
                spec = ToolsetPermissionCatalog.AUDIO,
                name = "getStreamVolumes",
                paramTypes = emptyList(),
                returnType = MaidLangNativeType.STRING,
                description = "读取媒体、铃声、通知与闹钟音量",
            ) {
                MaidValue.StringVal(audioToolSet.get().getStreamVolumes())
            }
            addEntry(
                spec = ToolsetPermissionCatalog.AUDIO,
                name = "setStreamVolume",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "设置指定音频流音量百分比 (streamType, volumePercent)",
            ) { args ->
                MaidValue.StringVal(
                    audioToolSet.get().setStreamVolume(
                        streamType = args.str(0),
                        volumePercent = args.intVal(1),
                    ),
                )
            }

            // 4. screen
            addEntry(
                spec = ToolsetPermissionCatalog.SCREEN,
                name = "captureScreenshot",
                paramTypes = listOf(
                    MaidLangNativeType.INT,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.INT,
                    MaidLangNativeType.INT,
                    MaidLangNativeType.BOOL,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "截取屏幕图像 (displayId, format, maxDimension, quality, includeBase64)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        screenToolSet.get().captureScreenshot(
                            displayId = args.intVal(0),
                            format = args.str(1).ifBlank { "jpeg" },
                            maxDimension = args.intVal(2),
                            quality = args.intVal(3),
                            includeBase64 = args.boolVal(4),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.SCREEN,
                name = "dumpUiTree",
                paramTypes = listOf(MaidLangNativeType.INT, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "导出当前界面 UI 层级树 (maxDepth, includeInvisible)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        screenToolSet.get().dumpUiTree(
                            maxDepth = args.intVal(0),
                            includeInvisible = args.boolVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.SCREEN,
                name = "findNodesByText",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "按文本或描述查找界面控件 (query, exactMatch)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        screenToolSet.get().findNodesByText(
                            query = args.str(0),
                            exactMatch = args.boolVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.SCREEN,
                name = "clickNodeByText",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "按文本或描述查找并点击控件 (query, exactMatch)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        screenToolSet.get().clickNodeByText(
                            query = args.str(0),
                            exactMatch = args.boolVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.SCREEN,
                name = "clickNodeByViewId",
                paramTypes = listOf(MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "按 viewId 查找并点击控件 (viewId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        screenToolSet.get().clickNodeByViewId(viewId = args.str(0))
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.SCREEN,
                name = "performGlobalAction",
                paramTypes = listOf(MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "执行系统全局导航动作 (action)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        screenToolSet.get().performGlobalAction(action = args.str(0))
                    },
                )
            }

            // 5. shell
            addEntry(
                spec = ToolsetPermissionCatalog.SHELL,
                name = "executeShell",
                paramTypes = listOf(
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.INT,
                    MaidLangNativeType.BOOL,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "通过 Shizuku 或 Root 执行 Shell 命令 (command, workingDir, timeoutMs, preferRoot)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        shellToolSet.executeShell(
                            command = args.str(0),
                            workingDir = args.nullableStr(1),
                            timeoutMs = args.positiveLongOrDefault(2, 15_000L),
                            preferRoot = args.boolVal(3),
                        )
                    },
                )
            }

            // 6. app_control
            addEntry(
                spec = ToolsetPermissionCatalog.APP_CONTROL,
                name = "getTopActivity",
                paramTypes = emptyList(),
                returnType = MaidLangNativeType.STRING,
                description = "获取当前前台顶层 Activity",
            ) {
                MaidValue.StringVal(runBlocking { appControlToolSet.getTopActivity() })
            }
            addEntry(
                spec = ToolsetPermissionCatalog.APP_CONTROL,
                name = "startActivity",
                paramTypes = listOf(
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.INT,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "启动应用 Activity (packageName, className, action, userId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        appControlToolSet.startActivity(
                            packageName = args.str(0),
                            className = args.nullableStr(1),
                            action = args.nullableStr(2),
                            userId = args.intVal(3),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.APP_CONTROL,
                name = "forceStopPackage",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "强行停止指定应用包 (packageName, userId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        appControlToolSet.forceStopPackage(
                            packageName = args.str(0),
                            userId = args.intVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.APP_CONTROL,
                name = "killBackgroundProcesses",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "清理指定应用后台进程 (packageName, userId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        appControlToolSet.killBackgroundProcesses(
                            packageName = args.str(0),
                            userId = args.intVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.APP_CONTROL,
                name = "getRunningProcesses",
                paramTypes = listOf(MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "列举运行中的应用进程 (limit)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        appControlToolSet.getRunningProcesses(limit = args.intVal(0))
                    },
                )
            }

            // 7. window
            addEntry(
                spec = ToolsetPermissionCatalog.WINDOW,
                name = "getDisplayInfo",
                paramTypes = listOf(MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "查询屏幕分辨率、DPI 与旋转方向 (displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking { windowToolSet.getDisplayInfo(displayId = args.intVal(0)) },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.WINDOW,
                name = "setDisplaySize",
                paramTypes = listOf(MaidLangNativeType.INT, MaidLangNativeType.INT, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "覆盖屏幕分辨率 (width, height, displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        windowToolSet.setDisplaySize(
                            width = args.intVal(0),
                            height = args.intVal(1),
                            displayId = args.intVal(2),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.WINDOW,
                name = "clearDisplaySize",
                paramTypes = listOf(MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "重置屏幕分辨率 (displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking { windowToolSet.clearDisplaySize(displayId = args.intVal(0)) },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.WINDOW,
                name = "setDisplayDensity",
                paramTypes = listOf(MaidLangNativeType.INT, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "覆盖屏幕 DPI (densityDpi, displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        windowToolSet.setDisplayDensity(
                            densityDpi = args.intVal(0),
                            displayId = args.intVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.WINDOW,
                name = "clearDisplayDensity",
                paramTypes = listOf(MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "重置屏幕 DPI (displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking { windowToolSet.clearDisplayDensity(displayId = args.intVal(0)) },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.WINDOW,
                name = "freezeRotation",
                paramTypes = listOf(MaidLangNativeType.INT, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "锁定屏幕旋转方向 (rotation, displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        windowToolSet.freezeRotation(
                            rotation = args.intVal(0),
                            displayId = args.intVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.WINDOW,
                name = "thawRotation",
                paramTypes = listOf(MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "恢复屏幕自动旋转 (displayId)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking { windowToolSet.thawRotation(displayId = args.intVal(0)) },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.WINDOW,
                name = "isKeyguardLocked",
                paramTypes = emptyList(),
                returnType = MaidLangNativeType.STRING,
                description = "检查锁屏状态",
            ) {
                MaidValue.StringVal(runBlocking { windowToolSet.isKeyguardLocked() })
            }

            // 8. root_system
            addEntry(
                spec = ToolsetPermissionCatalog.ROOT_SYSTEM,
                name = "executeRootShell",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.STRING, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "以 UID 0 Root 权限执行 Shell 命令 (command, workingDir, timeoutMs)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        rootSystemToolSet.executeRootShell(
                            command = args.str(0),
                            workingDir = args.nullableStr(1),
                            timeoutMs = args.positiveLongOrDefault(2, 15_000L),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.ROOT_SYSTEM,
                name = "getSystemProperty",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "读取 Android 系统属性 (key, defaultValue)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        rootSystemToolSet.getSystemProperty(
                            key = args.str(0),
                            defaultValue = args.str(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.ROOT_SYSTEM,
                name = "setSystemProperty",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "以 Root 权限设置 Android 系统属性 (key, value)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        rootSystemToolSet.setSystemProperty(
                            key = args.str(0),
                            value = args.str(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.ROOT_SYSTEM,
                name = "readProtectedFile",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "读取受 Root 保护的文件内容 (path, maxBytes)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        rootSystemToolSet.readProtectedFile(
                            path = args.str(0),
                            maxBytes = args.intVal(1),
                        )
                    },
                )
            }

            // 9. device_policy
            addEntry(
                spec = ToolsetPermissionCatalog.DEVICE_POLICY,
                name = "getDhizukuInfo",
                paramTypes = emptyList(),
                returnType = MaidLangNativeType.STRING,
                description = "查询 Dhizuku 服务版本与委托范围",
            ) {
                MaidValue.StringVal(devicePolicyToolSet.get().getDhizukuInfo())
            }
            addEntry(
                spec = ToolsetPermissionCatalog.DEVICE_POLICY,
                name = "setApplicationHidden",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "隐藏或取消隐藏应用 (packageName, hidden)",
            ) { args ->
                MaidValue.StringVal(
                    devicePolicyToolSet.get().setApplicationHidden(
                        packageName = args.str(0),
                        hidden = args.boolVal(1),
                    ),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.DEVICE_POLICY,
                name = "isApplicationHidden",
                paramTypes = listOf(MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "查询应用是否已隐藏 (packageName)",
            ) { args ->
                MaidValue.StringVal(
                    devicePolicyToolSet.get().isApplicationHidden(packageName = args.str(0)),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.DEVICE_POLICY,
                name = "setPackagesSuspended",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "冻结或解冻逗号分隔的应用包列表 (packageNamesCsv, suspended)",
            ) { args ->
                MaidValue.StringVal(
                    devicePolicyToolSet.get().setPackagesSuspended(
                        packageNamesCsv = args.str(0),
                        suspended = args.boolVal(1),
                    ),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.DEVICE_POLICY,
                name = "isPackageSuspended",
                paramTypes = listOf(MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "查询应用是否已冻结 (packageName)",
            ) { args ->
                MaidValue.StringVal(
                    devicePolicyToolSet.get().isPackageSuspended(packageName = args.str(0)),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.DEVICE_POLICY,
                name = "setUninstallBlocked",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "禁止或允许卸载指定应用 (packageName, blocked)",
            ) { args ->
                MaidValue.StringVal(
                    devicePolicyToolSet.get().setUninstallBlocked(
                        packageName = args.str(0),
                        blocked = args.boolVal(1),
                    ),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.DEVICE_POLICY,
                name = "isUninstallBlocked",
                paramTypes = listOf(MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "查询指定应用是否禁止卸载 (packageName)",
            ) { args ->
                MaidValue.StringVal(
                    devicePolicyToolSet.get().isUninstallBlocked(packageName = args.str(0)),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.DEVICE_POLICY,
                name = "lockScreenNow",
                paramTypes = emptyList(),
                returnType = MaidLangNativeType.STRING,
                description = "通过设备策略立即锁屏",
            ) {
                MaidValue.StringVal(devicePolicyToolSet.get().lockScreenNow())
            }

            // 10. file_management
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "listDirectory",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.BOOL, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "列出目录内容 (path, includeHidden, maxEntries)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().listDirectory(
                            path = args.str(0),
                            includeHidden = args.boolVal(1),
                            maxEntries = args.intVal(2),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "readTextFile",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "读取 UTF-8 文本文件或附件 (pathOrUri, maxChars)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().readTextFile(
                            pathOrUri = args.str(0),
                            maxChars = args.intVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "writeTextFile",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.STRING, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "写入或追加 UTF-8 文本文件 (path, content, append)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().writeTextFile(
                            path = args.str(0),
                            content = args.str(1),
                            append = args.boolVal(2),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "createDirectory",
                paramTypes = listOf(MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "创建目录及缺失的父目录 (path)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().createDirectory(path = args.str(0))
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "copyOrMoveFile",
                paramTypes = listOf(
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.BOOL,
                    MaidLangNativeType.BOOL,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "复制或移动文件/目录 (sourcePath, targetPath, move, overwrite)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().copyOrMoveFile(
                            sourcePath = args.str(0),
                            targetPath = args.str(1),
                            move = args.boolVal(2),
                            overwrite = args.boolVal(3),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "deleteFileOrDirectory",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "删除文件或目录 (path, recursive)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().deleteFileOrDirectory(
                            path = args.str(0),
                            recursive = args.boolVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "searchFiles",
                paramTypes = listOf(
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.INT,
                    MaidLangNativeType.INT,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "按文件名关键字检索文件 (nameQuery, directoryPath, maxDepth, maxResults)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().searchFiles(
                            nameQuery = args.str(0),
                            directoryPath = args.str(1),
                            maxDepth = args.intVal(2),
                            maxResults = args.intVal(3),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "importFileToSession",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.STRING, MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "导入文件到当前会话附件存储 (uri, description, fileName)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().importFileToSession(
                            uri = args.str(0),
                            description = args.str(1),
                            fileName = args.str(2),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "exportFileFromSession",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.STRING, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "从当前会话导出附件到目标路径 (attachmentId, targetUri, overwrite)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().exportFileFromSession(
                            attachmentId = args.str(0),
                            targetUri = args.str(1),
                            overwrite = args.boolVal(2),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.FILE_MANAGEMENT,
                name = "getFileDetails",
                paramTypes = listOf(MaidLangNativeType.STRING),
                returnType = MaidLangNativeType.STRING,
                description = "查询文件或附件的元信息 (pathOrUri)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        fileManagementToolSet.get().getFileDetails(pathOrUri = args.str(0))
                    },
                )
            }

            // 11. usage_stats
            addEntry(
                spec = ToolsetPermissionCatalog.USAGE_STATS,
                name = "getRecentForegroundApps",
                paramTypes = listOf(MaidLangNativeType.INT, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "查询最近活跃前台应用 (withinMinutes, limit)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        usageStatsToolSet.get().getRecentForegroundApps(
                            withinMinutes = args.intVal(0),
                            limit = args.intVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.USAGE_STATS,
                name = "getAppUsageSummary",
                paramTypes = listOf(MaidLangNativeType.STRING, MaidLangNativeType.INT, MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "查询应用前台使用时长统计 (packageName, days, limit)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        usageStatsToolSet.get().getAppUsageSummary(
                            packageName = args.str(0),
                            days = args.intVal(1),
                            limit = args.intVal(2),
                        )
                    },
                )
            }

            // 12. system_settings
            addEntry(
                spec = ToolsetPermissionCatalog.SYSTEM_SETTINGS,
                name = "getDisplaySettings",
                paramTypes = emptyList(),
                returnType = MaidLangNativeType.STRING,
                description = "读取屏幕亮度、自动亮度与息屏超时设置",
            ) {
                MaidValue.StringVal(runBlocking { systemSettingsToolSet.get().getDisplaySettings() })
            }
            addEntry(
                spec = ToolsetPermissionCatalog.SYSTEM_SETTINGS,
                name = "setScreenBrightness",
                paramTypes = listOf(MaidLangNativeType.INT, MaidLangNativeType.BOOL),
                returnType = MaidLangNativeType.STRING,
                description = "设置屏幕亮度与自动亮度模式 (brightness, autoBrightness)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        systemSettingsToolSet.get().setScreenBrightness(
                            brightness = args.intVal(0),
                            autoBrightness = args.boolVal(1),
                        )
                    },
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.SYSTEM_SETTINGS,
                name = "setScreenOffTimeout",
                paramTypes = listOf(MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "设置息屏超时秒数 (timeoutSeconds)",
            ) { args ->
                MaidValue.StringVal(
                    runBlocking {
                        systemSettingsToolSet.get().setScreenOffTimeout(timeoutSeconds = args.intVal(0))
                    },
                )
            }

            // 13. alarm_clock
            addEntry(
                spec = ToolsetPermissionCatalog.ALARM_CLOCK,
                name = "setSystemAlarm",
                paramTypes = listOf(
                    MaidLangNativeType.INT,
                    MaidLangNativeType.INT,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.BOOL,
                    MaidLangNativeType.BOOL,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "调用手机自带闹钟设置闹钟 (hour, minute, message, days, vibrate, skipUi)",
            ) { args ->
                MaidValue.StringVal(
                    alarmClockToolSet.get().setSystemAlarm(
                        hour = args.intVal(0),
                        minute = args.intVal(1),
                        message = args.str(2),
                        days = args.str(3),
                        vibrate = args.boolVal(4),
                        skipUi = args.boolVal(5),
                    ),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.ALARM_CLOCK,
                name = "setSystemTimer",
                paramTypes = listOf(
                    MaidLangNativeType.INT,
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.BOOL,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "调用手机自带时钟启动倒计时 (lengthSeconds, message, skipUi)",
            ) { args ->
                MaidValue.StringVal(
                    alarmClockToolSet.get().setSystemTimer(
                        lengthSeconds = args.intVal(0),
                        message = args.str(1),
                        skipUi = args.boolVal(2),
                    ),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.ALARM_CLOCK,
                name = "showSystemAlarms",
                paramTypes = emptyList(),
                returnType = MaidLangNativeType.STRING,
                description = "打开手机自带闹钟列表",
            ) {
                MaidValue.StringVal(alarmClockToolSet.get().showSystemAlarms())
            }
            addEntry(
                spec = ToolsetPermissionCatalog.ALARM_CLOCK,
                name = "showSystemTimers",
                paramTypes = emptyList(),
                returnType = MaidLangNativeType.STRING,
                description = "打开手机自带计时器列表",
            ) {
                MaidValue.StringVal(alarmClockToolSet.get().showSystemTimers())
            }
            addEntry(
                spec = ToolsetPermissionCatalog.ALARM_CLOCK,
                name = "dismissSystemAlarm",
                paramTypes = listOf(
                    MaidLangNativeType.STRING,
                    MaidLangNativeType.INT,
                    MaidLangNativeType.INT,
                    MaidLangNativeType.STRING,
                ),
                returnType = MaidLangNativeType.STRING,
                description = "关闭手机自带闹钟 (searchMode, hour, minute, label)",
            ) { args ->
                MaidValue.StringVal(
                    alarmClockToolSet.get().dismissSystemAlarm(
                        searchMode = args.str(0),
                        hour = args.intVal(1),
                        minute = args.intVal(2),
                        label = args.str(3),
                    ),
                )
            }
            addEntry(
                spec = ToolsetPermissionCatalog.ALARM_CLOCK,
                name = "snoozeSystemAlarm",
                paramTypes = listOf(MaidLangNativeType.INT),
                returnType = MaidLangNativeType.STRING,
                description = "延后当前响铃的系统闹钟 (snoozeDurationMinutes，0 使用系统默认)",
            ) { args ->
                MaidValue.StringVal(
                    alarmClockToolSet.get().snoozeSystemAlarm(
                        snoozeDurationMinutes = args.intVal(0),
                    ),
                )
            }
        }
    }

    private fun MutableList<MaidLangNativeEntry>.addEntry(
        spec: ToolsetPermissionSpec,
        name: String,
        paramTypes: List<MaidLangNativeType>,
        returnType: MaidLangNativeType,
        description: String,
        implementation: (List<MaidValue>) -> MaidValue,
    ) {
        val privileges: Set<AndroidPrivilege> = spec.requiredPrivileges.toSet()
        add(
            MaidLangNativeEntry(
                declaration = MaidLangNativeDeclaration(
                    name = name,
                    paramTypes = paramTypes,
                    returnType = returnType,
                    description = description,
                ),
                implementation = implementation,
                toolsetId = spec.toolsetId,
                requiredPrivileges = privileges,
            ),
        )
    }

    private class ContextBoundToolSet<T>(
        private val contextProvider: () -> Context,
        private val factory: (Context) -> T,
    ) {
        @Volatile
        private var cachedContext: Context? = null

        @Volatile
        private var cachedInstance: T? = null

        fun get(): T {
            val ctx = contextProvider().applicationContext
            val existing = cachedInstance
            if (existing != null && cachedContext === ctx) return existing
            return synchronized(this) {
                val again = cachedInstance
                if (again != null && cachedContext === ctx) {
                    again
                } else {
                    factory(ctx).also {
                        cachedContext = ctx
                        cachedInstance = it
                    }
                }
            }
        }
    }

    private fun List<MaidValue>.str(index: Int): String = (this[index] as MaidValue.StringVal).value

    private fun List<MaidValue>.nullableStr(index: Int): String? = str(index).takeIf { it.isNotBlank() }

    private fun List<MaidValue>.intVal(index: Int): Int = this[index].asInt()

    private fun List<MaidValue>.floatVal(index: Int): Float = this[index].asFloat()

    private fun List<MaidValue>.boolVal(index: Int): Boolean = this[index].asBool()

    private fun List<MaidValue>.positiveLongOrDefault(index: Int, defaultValue: Long): Long {
        val raw = intVal(index)
        return if (raw > 0) raw.toLong() else defaultValue
    }
}
