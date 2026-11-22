# 开发与构建指南

[返回 README](../README.md) · [架构](architecture.md) · [使用与配置](usage.md)

## 工具链与版本来源

以下为仓库当前配置；升级时以对应配置文件为准，不将文档中的版本作为升级目标。

| 项目 | 当前配置 | 来源 |
| --- | --- | --- |
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Gradle Daemon JVM | Java 25 | `gradle/gradle-daemon-jvm.properties` |
| Java 编译兼容级别 | 17 | `app/build.gradle.kts` |
| Android Gradle Plugin | 9.5.1 | `gradle/libs.versions.toml` |
| Kotlin Compose 插件 | 2.4.21 | `gradle/libs.versions.toml` |
| Compose BOM | 2026.09.00 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 31 | `app/build.gradle.kts` |
| CMake | 3.22.1 | `app/build.gradle.kts` |
| 应用打包 ABI | arm64-v8a、x86_64 | `defaultConfig.ndk.abiFilters` |

Java 17 是源码编译兼容级别，不能据此把 Gradle Daemon 配置改成 Java 17。AGP 提供内置 Kotlin 支持，项目没有单独应用 `org.jetbrains.kotlin.android` 插件。Gradle 自身显示的嵌入式 Kotlin 版本也不等于应用使用的插件版本。

## 环境准备

1. 用 Android Studio 打开包含 `settings.gradle.kts` 的根目录。
2. 在 SDK Manager 中安装 Android SDK Platform 37、相应 Build Tools、NDK（Side by side）和 CMake 3.22.1。项目未显式固定 `ndkVersion`，具体选用版本以 AGP 同步结果为准。
3. 配置本机 SDK 路径：通过 IDE 生成 `local.properties` 的 `sdk.dir`，或使用正确的 SDK 环境配置；不要共享个人 SDK 绝对路径。
4. 保留仓库中的 Gradle Wrapper 与 Daemon JVM 配置。首次运行可能需要联网获取 Java 25 工具链和依赖。
5. 确保可以访问配置中的 Google Maven、Maven Central、Gradle Plugin Portal 和 JitPack。Koog 使用 `main-SNAPSHOT`，依赖解析受远端快照变化影响。
6. 保留完整的原生源码、`third_party/vtfx`、`third_party/maidlang` 和内置资产。模型及 rootfs 归档会影响 APK 大小、打包时间和设备端磁盘占用。

先检查实际工具链：

```bash
./gradlew --version
```

Windows 使用 `gradlew.bat` 替代 `./gradlew`。

## 构建

在项目根目录执行：

```bash
./gradlew :app:assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

构建会通过 CMake 编译 PRoot 与 loader，并将可执行文件以 `libproot.so`、`libproot_loader.so` 的名称放入原生库目录。不要仅因后缀为 `.so` 就把它们改成普通共享库。当前打包启用了 `jniLibs.useLegacyPackaging`；修改前应验证 PRoot 启动路径。

发布包构建入口：

```bash
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

当前 Gradle 文件没有配置生产签名；上述命令不代表得到可直接发布的已签名包。签名材料和凭据应由本地环境或发布系统提供。

## 测试与静态检查

| 检查 | 命令 | 适用场景 |
| --- | --- | --- |
| JVM 单元测试 | `./gradlew :app:testDebugUnitTest` | 状态、协议解析、Agent、仓储、工具路由等逻辑 |
| 单个测试类 | `./gradlew :app:testDebugUnitTest --tests 'top.aaa000111.maid.provider.ChatGptResponsesPromptExecutorTest'` | 定位模块回归 |
| Android Lint | `./gradlew :app:lintDebug` | 资源、Manifest 和 Android 静态检查 |
| 设备端测试 | `./gradlew :app:connectedDebugAndroidTest` | 已连接兼容设备或模拟器时 |
| 安装 Debug 包 | `./gradlew :app:installDebug` | 已连接目标设备时 |

JVM 测试位于 `app/src/test/java/top/aaa000111/maid/`，覆盖 Agent、会话、权限调度、Provider、搜索、PRoot、TinyModel 等模块。优先为行为变化选择相关现有测试，使用项目已有的 fake、可注入依赖及测试初始化入口。

设备端测试位于 `app/src/androidTest/`，现有覆盖有限。单元测试通过不能替代真实设备上的授权、截图、输入注入、GPU 推理和终端验证。

纯文档修改只需核对命令、代码路径与相对链接，不要求重新运行整套测试。新增功能或修复行为时运行对应测试；涉及资源或构建配置时至少构建 Debug APK，并按影响范围补充 Lint 或设备验证。

## 固定提示词

默认系统提示和固定指导片段放在 `app/src/main/resources/prompts/`，按用途拆为 UTF-8 Markdown。修改后重新构建 APK；加载器不依赖 Context，JVM 测试读取同一份资源。动态权限、模型和会话数据继续在代码中组装，详见[固定提示词管理](prompts.md)。

## 调试路径

- **聊天无法发送**：检查 `FAST_CHEAP` 是否完整配置、服务商是否就绪，再查看 `ChatViewModel` 和 `ChatBackgroundRuntime`。
- **模型协议异常**：检查 `provider/` 的请求、流式事件、工具调用及推理内容处理；优先增加脱敏协议样例测试。
- **设备工具不可用**：检查权限状态、工具集声明和会话授权，再查看 `AndroidToolsetDispatcher` 与 `PrivilegeBackend`。
- **终端启动异常**：先核对设备 ABI、原生库打包和 rootfs 初始化状态，再检查 `TermuxProotRuntime`、`ProotConnection`。
- **端侧推理异常**：检查模型文件及后端设置；`AUTO` 会按模型能力尝试 GPU 并在初始化失败时回退 CPU，强制 GPU 模式不等同于 AUTO。
- **联网检索无结果**：检查网络、分类、实例地址与引擎可用性缓存，再定位 `SearchEngineRouter` 和具体引擎。

日志与测试样例应隐藏 API Key、OAuth token、授权码、会话内容和附件隐私数据。

## 常见构建问题

### Gradle JVM 不匹配

检查 `./gradlew --version` 中的 Daemon JVM 与 `gradle/gradle-daemon-jvm.properties`。确认 Java 25 可用或工具链下载可达；不要通过修改源码兼容级别绕过工具链问题。

### 无法解析 Koog 或其他依赖

检查 `settings.gradle.kts` 的仓库配置与网络访问。JitPack 快照可能发生变化；先区分网络、缓存与上游 API 变化，再决定是否刷新依赖。不要在排障时顺手升级所有依赖。

### SDK、NDK 或 CMake 缺失

使用 SDK Manager 安装构建日志要求的组件，核对 SDK 路径与 CMake 3.22.1。CMake 支持的架构分支不代表 Gradle 已启用对应 ABI；当前应用只打包两种 64 位 ABI。

### APK 较大或首次启动较慢

检查打包的 `assets/models/` 与 rootfs 归档。端侧模型解包和 rootfs 初始化也需要额外设备存储；先定位耗时和文件大小，不要直接移除运行依赖。

## 图标资源

当前采用第四版女仆设计，源图为根目录 `maid-logo-concept-v4.png`。

- 自适应入口：`res/mipmap-anydpi/ic_launcher.xml` 与 `ic_launcher_round.xml`。
- 彩色前景：`res/drawable-nodpi/maid_launcher_foreground.png`。
- 背景：`res/drawable/ic_launcher_background.xml`。
- 主题单色前景：`res/drawable-nodpi/maid_launcher_monochrome.png`。
- 各密度资源：`res/mipmap-*/ic_launcher.webp` 与 `ic_launcher_round.webp`。

上述 `res/` 均相对于 `app/src/main/`。更新时同步检查 Manifest 引用、自适应图标的裁切范围及主题图标。仓库仍有旧版 `ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`，`AppIconPreview.kt` 仍引用旧前景；检查当前启动器图标应以自适应入口 XML 的实际引用为准。
