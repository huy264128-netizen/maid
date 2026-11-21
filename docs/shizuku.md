# Shizuku 模块使用与架构文档

`top.aaa000111.maid.shizuku` 封装了 Shizuku Client API、基于 AIDL 的特权 `UserService` 进程管理、可靠的单命令输入/输出执行器，以及特权进程内通过反射获取的 `ActivityManager`、`InputManager` 与 `WindowManager` 能力。

---

## 1. 架构总览

```text
主进程 (App Process)
 └── ShizukuManager
      ├── Shizuku Client API（Binder 监听 / 权限申请 / ServerInfo / SystemProperties）
      ├── ShizukuSystemServiceHelper（ShizukuBinderWrapper / transactRemote）
      └── Binder IPC (IShizukuUserService.aidl)
           │
           ▼
特权进程 (Shizuku UserService: <package>:shizuku_service, UID 0 / UID 2000)
 └── ShizukuUserService (IShizukuUserService.Stub)
      ├── ShellCommandExecutor（并发 stdout/stderr 排空 + stdin EOF + 超时强杀）
      └── PrivilegedSystemManagers（反射获取 IActivityManager / IActivityTaskManager / IInputManager / IWindowManager）
```

### 核心文件清单

| 文件 | 职责 |
| :--- | :--- |
| `app/src/main/aidl/top/aaa000111/maid/shizuku/IShizukuUserService.aidl` | 定义特权 `UserService` 的 AIDL 跨进程接口与 Shizuku 约定的 `destroy() = 16777114` |
| `app/src/main/aidl/top/aaa000111/maid/shizuku/ShellCommandResult.aidl` | `ShellCommandResult` Parcelable 声明 |
| `app/src/main/aidl/top/aaa000111/maid/shizuku/PrivilegedProcessInfo.aidl` | `PrivilegedProcessInfo` Parcelable 声明 |
| `app/src/main/aidl/top/aaa000111/maid/shizuku/PrivilegedDisplayInfo.aidl` | `PrivilegedDisplayInfo` Parcelable 声明 |
| `app/src/main/java/top/aaa000111/maid/shizuku/ShizukuManager.kt` | 主进程统一调用入口（状态流、权限申请、UserService 绑定与全部协程 API） |
| `app/src/main/java/top/aaa000111/maid/shizuku/ShizukuUserService.kt` | 运行在 Shizuku 特权进程中的 `IShizukuUserService.Stub` 实现 |
| `app/src/main/java/top/aaa000111/maid/shizuku/PrivilegedSystemManagers.kt` | 反射解析与调用 `IActivityManager` / `IActivityTaskManager` / `IInputManager` / `IWindowManager` |
| `app/src/main/java/top/aaa000111/maid/shizuku/ShellCommandExecutor.kt` | 可靠单命令执行引擎（三线程并发 I/O，防管道死锁与 Binder 超限） |
| `app/src/main/java/top/aaa000111/maid/shizuku/ShizukuSystemServiceHelper.kt` | `ServiceManager` 与 `ShizukuBinderWrapper` / `transactRemote` 工具 |
| `app/src/main/java/top/aaa000111/maid/shizuku/ShizukuModels.kt` | `ShizukuAvailability`、`ShizukuServerInfo`、`ShizukuStatus` |

---

## 2. 工程配置要求

1. **Gradle 开启 AIDL**（[`app/build.gradle.kts`](../app/build.gradle.kts)）：
   - `buildFeatures { aidl = true }`
   - 引入 `dev.rikka.shizuku:api:13.1.5` 与 `dev.rikka.shizuku:provider:13.1.5`。
2. **注册 `ShizukuProvider`**（[`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml)）：
   - `authorities="${applicationId}.shizuku"`
   - `permission="android.permission.INTERACT_ACROSS_USERS_FULL"`
   - `exported="true"`，`multiprocess="false"`
3. **R8 混淆保留规则**（[`app/src/main/keepRules/rules.keep`](../app/src/main/keepRules/rules.keep)）：
   - 保留 `ShizukuUserService` 及其构造函数、`PrivilegedSystemManagers`、`IShizukuUserService` 及相关 Parcelable。

---

## 3. 快速上手

### 3.1 初始化与状态监听

建议在 `Application` 或 `ViewModel` 中持有单例/长生命周期的 `ShizukuManager`，并在不再使用时调用 `release()`：

```kotlin
val shizukuManager = ShizukuManager(context)

// 观察连接、权限、服务端信息与 UserService 绑定状态
lifecycleScope.launch {
    shizukuManager.status.collect { status ->
        when (status.availability) {
            ShizukuAvailability.NOT_INSTALLED -> { /* 未安装 Shizuku */ }
            ShizukuAvailability.STOPPED_OR_DISCONNECTED -> { /* Shizuku 服务未启动 */ }
            ShizukuAvailability.PERMISSION_REQUIRED -> { /* 需要请求 Shizuku 授权 */ }
            ShizukuAvailability.READY -> { /* 已就绪，可绑定 UserService 并执行调用 */ }
        }
    }
}
```

### 3.2 申请授权

```kotlin
// 方式一：触发系统权限弹窗（结果通过 status StateFlow 回调）
shizukuManager.requestPermission()

// 方式二：协程挂起等待用户授权结果
val granted: Boolean = shizukuManager.awaitPermission(timeoutMillis = 60_000L)
```

---

## 4. 可靠单命令输入/输出（UserService）

`ShizukuManager.execShell` 与 `ShizukuManager.execCommand` 会自动按需绑定 `ShizukuUserService`，在特权进程中执行命令并返回结构化的 `ShellCommandResult`。

### 4.1 可靠性设计说明

- **防管道死锁**：独立守护线程并发排空 `stdout` 与 `stderr`，避免子进程写满缓冲区卡死。
- **可控标准输入**：独立线程向 `stdin` 写入 UTF-8 数据并在写完后立即关闭流（发送 EOF），防止 `cat`、`grep` 等命令无限等待输入。
- **Binder 大小保护**：单流默认最多采集 `256 KB`（防止超过 Binder `1 MB` 事务上限），超出后继续排空管道并标记 `stdoutTruncated` / `stderrTruncated`。
- **超时强制销毁**：到达 `timeoutMillis` 未结束会调用 `destroyForcibly()` 结束子进程，返回 `timedOut = true` 及超时前已采集的部分输出。

### 4.2 调用示例

```kotlin
// 1. 执行 Shell 命令（支持管道、重定向）
val shellResult = shizukuManager.execShell(
    shellCommand = "id && getenforce",
    timeoutMillis = 10_000L,
)
if (shellResult.isSuccess) {
    println(shellResult.stdout)
} else {
    println("exit=${shellResult.exitCode}, err=${shellResult.stderr}, msg=${shellResult.errorMessage}")
}

// 2. 直接传递 argv 数组（避免 Shell 引号转义问题）并传入 stdin、工作目录与环境变量
val cmdResult = shizukuManager.execCommand(
    command = listOf("sh", "-c", "pwd; echo \$CUSTOM_VAR; cat"),
    stdin = "hello from maid\n",
    workingDir = "/data/local/tmp",
    env = mapOf("CUSTOM_VAR" to "ok"),
    timeoutMillis = 15_000L,
)
```

---

## 5. 反射系统服务能力调用指南

`ShizukuUserService` 内部通过 `PrivilegedSystemManagers` 反射获取以下 Binder 代理接口，并在主进程 `ShizukuManager` 中提供对应的协程方法（全部返回 `Result<T>`，内部自动处理 `bindUserService` 与 `Dispatchers.IO` 调度）。

### 5.1 检查反射可用性

```kotlin
val statusMap = shizukuManager.getSystemManagersStatus().getOrNull()
// keys: "activity_manager", "activity_task_manager", "input_manager", "window_manager"
```

### 5.2 ActivityManager 能力

```kotlin
// 强制停止应用（userId 默认 0）
shizukuManager.forceStopPackage("com.example.target", userId = 0)

// 清理后台进程
shizukuManager.killBackgroundProcesses("com.example.target", userId = 0)

// 获取当前运行进程列表
val processes = shizukuManager.getRunningAppProcesses().getOrNull().orEmpty()

// 获取栈顶 Activity（如 com.example.app/.MainActivity）
val topActivity = shizukuManager.getTopActivity().getOrNull()

// 以特权身份启动指定组件或 Intent
shizukuManager.startActivity(
    packageName = "com.android.settings",
    className = "com.android.settings.Settings",
)
```

### 5.3 InputManager 能力

```kotlin
// 点击屏幕坐标 (x, y)
shizukuManager.injectTap(x = 540f, y = 1200f, displayId = 0)

// 平滑滑动手势
shizukuManager.injectSwipe(
    startX = 540f,
    startY = 1600f,
    endX = 540f,
    endY = 600f,
    durationMs = 300L,
)

// 注入按键事件（支持长按）
shizukuManager.injectKeyEvent(
    keyCode = KeyEvent.KEYCODE_BACK,
    longPress = false,
)

// 注入文本（通过虚拟键盘 KeyCharacterMap）
shizukuManager.injectText("hello world")

// 注入自定义 InputEvent（KeyEvent / MotionEvent）
shizukuManager.injectInputEvent(
    event = customMotionEvent,
    mode = PrivilegedSystemManagers.INJECT_MODE_WAIT_FOR_FINISH,
)
```

### 5.4 WindowManager 能力

```kotlin
// 查询显示分辨率、DPI、旋转方向与导航栏状态
val displayInfo = shizukuManager.getDisplayInfo(displayId = 0).getOrNull()

// 强制修改与恢复分辨率
shizukuManager.setForcedDisplaySize(width = 1080, height = 2400, displayId = 0)
shizukuManager.clearForcedDisplaySize(displayId = 0)

// 强制修改与恢复显示密度 (DPI)
shizukuManager.setForcedDisplayDensity(densityDpi = 420, displayId = 0, userId = 0)
shizukuManager.clearForcedDisplayDensity(displayId = 0, userId = 0)

// 锁定与恢复屏幕旋转（Surface.ROTATION_0 / 90 / 180 / 270）
shizukuManager.freezeRotation(rotation = Surface.ROTATION_90, displayId = 0)
shizukuManager.thawRotation(displayId = 0)

// 查询是否处于锁屏状态
val locked = shizukuManager.isKeyguardLocked().getOrDefault(false)
```

---

## 6. Shizuku 基础 API 与 Binder 代理

除 `UserService` 外，`ShizukuManager` 与 `ShizukuSystemServiceHelper` 还直接提供以下 Shizuku 原生能力：

- **服务端信息**：`shizukuManager.getServerInfo()`（UID、API 版本、SELinux 上下文、`isPreV11`）
- **服务端权限校验**：`shizukuManager.checkRemotePermission("android.permission.DUMP")`
- **系统属性读写**：`shizukuManager.getSystemProperty(key, defaultValue)` / `shizukuManager.setSystemProperty(key, value)`
- **Shizuku 管理端拉起**：`shizukuManager.isShizukuInstalled()` / `shizukuManager.openShizukuApp()`
- **Binder 代理转发**：
  ```kotlin
  val wrappedPackageBinder = ShizukuSystemServiceHelper.getWrappedSystemService("package")
  ```

---

## 7. 注意事项

1. **进程隔离限制**：`ShizukuUserService` 运行在独立的 `app_process` 特权进程中（非标准 Android Application 进程），不能在其中访问 UI、主进程内存单例、`ContentResolver` 或注册常规 `BroadcastReceiver`。
2. **生命周期释放**：退出相关作用域时调用 `shizukuManager.release(stopUserService = true)`（或 `unbindUserService(remove = true)`）以触发远程 `destroy()` 并退出子进程；默认 `daemon(false)` 下主进程死亡后服务也会自动退出。
3. **Android Studio 调试更新**：修改 `ShizukuUserService` 或 `PrivilegedSystemManagers` 后运行调试时，确保 Run/Debug Configuration 中勾选 **Always install with package manager**，避免 Shizuku 加载旧版 APK 中的服务代码。
4. **单元测试**：[`ShellCommandExecutorTest.kt`](../app/src/test/java/top/aaa000111/maid/shizuku/ShellCommandExecutorTest.kt) 在本地 JVM 上覆盖了单命令 `stdout`/`stderr` 采集、非零退出码、`stdin` EOF、工作目录/环境变量、超时强杀与大输出防死锁截断测试。
