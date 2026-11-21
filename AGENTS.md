# 项目协作约定

适用于仓库根目录及其子目录。进入有独立 `AGENTS.md` 的目录时，同时阅读对应说明；特别是 `app/src/main/cpp/proot/AGENTS.md`。本文件描述当前项目与修改约定，不替代用户当次任务指令。

## 项目速览

- 单模块 Android 应用：`:app`，包名 `top.aaa000111.maid`。
- Kotlin、Jetpack Compose Material 3、Koog、Ktor、DataStore、Tink、Shizuku/Dhizuku、LiteRT-LM。
- Android SDK：min 31、compile/target 37；打包 ABI：`arm64-v8a`、`x86_64`。
- Gradle Wrapper 9.6.0；Daemon Java 25；Java 源码兼容级别 17。版本来源是 Gradle 配置和 `gradle/libs.versions.toml`。应用版本遵循 `大版本号.中间版本号.小版本号`，`versionCode = 大版本号 * 10000 + 中间版本号 * 100 + 小版本号`；每次发布同步更新 `versionName` 与 `versionCode`。
- CMake 3.22.1 编译 PRoot 和 loader；`third_party/vtfx/src/main/kotlin` 直接加入 app source set。
- 开始修改前阅读 [README](README.md)，按任务参考[开发指南](docs/development.md)和[架构说明](docs/architecture.md)。

## 代码导航

生产代码根目录：`app/src/main/java/top/aaa000111/maid/`。

| 目录 / 入口 | 职责 |
| --- | --- |
| `MaidApplication.kt`、`runtime/AppRuntime.kt` | 初始化、共享资源、角色模型绑定与缓存 |
| `chat/` | UI、会话、后台调度、附件和持久化 |
| `agent/` | 对话、规划、设备操作、Linux、调研及视觉工具 |
| `provider/`、`security/` | 模型服务商、鉴权、协议和凭据存储 |
| `androidtool/`、`shizuku/`、`accessibility/` | 权限、后端、系统工具及 IPC |
| `proot/`、`app/src/main/cpp/` | Linux 运行态、终端连接、原生构建 |
| `search/` | 分类检索、网页抓取和 SearXNG |
| `tinymodel/` | 模型发现、导入、推理和本地桥接 |
| `settings/`、`memory/` | 配置与长期记忆 |
| `app/src/test/`、`app/src/androidTest/` | JVM 测试与设备端测试 |

## 实现原则

- 沿用现有 Kotlin 风格、四空格缩进与包结构。用户可见文案放在 `res/values/strings.xml`，复用现有 Material 3 主题。
- 维持当前显式初始化、仓储与管理器边界；不为局部任务额外引入 DI、导航或持久化框架。
- 文件、网络、原生模型与进程等待避免阻塞主线程；保留现有协程取消、超时、状态通知和资源释放语义。
- 聊天输入和会话列表由 `ChatBackgroundRuntime` / `ChatTaskDispatcher` 协调，模型调用转交 `ChatAgentManager`。各 Manager 继承实现 ToolSet 的 `AgentManager`，具体 `@Tool` 提交闭包并返回 ID，基类内存任务表保存 String 结果，等待工具复用基类实现。不要增加通用请求模型、额外生命周期控制器或全局任务登记。内部同步业务 API 和 UI 可保留结构化结果；会话切换不应取消其他会话任务。参见 [Manager 文档](docs/agent_managers.md)。
- 固定系统提示与指导片段放在 `app/src/main/resources/prompts/`，通过 `PromptResources` 加载；动态上下文及工具/权限选择留在代码中。不要增加重复的源码内提示词 fallback。参见[提示词管理](docs/prompts.md)。
- `ChatAgent` 独占长期对话记忆。规划生命周期笔记、Linux 上下文与用户聊天记忆保持各自职责。
- Provider 修改同时检查鉴权方式、就绪状态、角色选择、缓存失效和流式协议。新增接入参考 [LLM 文档](docs/llm_provider.md)；不要把 OAuth 服务商加入仅用于 API Key 的列表。
- Android 工具通过 `AndroidToolSet`、调度器与权限后端接入。`requiredPrivileges` 默认是满足任意一项，而非必须全部满足。保留会话授权与执行时敏感操作校验。
- 会话与配置持久化变更考虑已有数据读取、缺失字段及附件引用；避免无关格式重写。
- 网络解析和协议变更优先使用脱敏样例与 fake HTTP 测试，避免测试依赖真实账号、密钥和外部网络。
- 不在源码、文档、测试样例或日志中写入真实 API Key、OAuth token、授权码、签名密钥或私人会话内容。

## 原生、第三方与大文件

- 修改 PRoot 前阅读其目录内的 `AGENTS.md`。第三方源码和许可证按各自约定处理，避免无关格式化或批量重写。
- `libproot.so`、`libproot_loader.so` 是按当前打包方案命名的可执行文件；调整目标类型、loader 地址或原生库提取方式前先检查启动链路。
- rootfs 为应用级共享目录，不提供会话间强隔离；不要把提示词中的工作目录建议当作文件系统权限边界。
- 保留模型的 Engine/Conversation 隔离与后端回退策略，除非任务包含相应行为变更和验证。
- 内置模型、rootfs 归档和图像是二进制资产。避免为非相关任务重新生成或替换；不要编辑 `build/`、`.gradle/`、`.cxx/` 等生成目录来修复源码问题。
- 图标以 `mipmap-anydpi/ic_launcher*.xml` 的实际引用为准。当前前景为 `drawable-nodpi/maid_launcher_*.png`，源图为 `maid-logo-concept-v4.png`；旧预览资源不代表当前启动器图标。

## 验证

在仓库根目录使用 Wrapper：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

按改动选择检查：

- 逻辑变化：运行相关测试类，必要时扩展到完整 JVM 测试。例如 `./gradlew :app:testDebugUnitTest --tests 'top.aaa000111.maid.provider.ChatGptResponsesPromptExecutorTest'`。
- UI、资源、Manifest 或构建配置：至少构建 Debug APK，并按影响范围执行 Lint 或设备验证。
- Shizuku、Root、截图、输入注入、PRoot 与 LiteRT GPU：相关单元测试之外还需区分哪些行为已在真实设备验证，哪些尚未验证。
- 设备端测试入口：`./gradlew :app:connectedDebugAndroidTest`，需兼容设备在线。
- 纯文档修改：核对描述、命令、文件路径和相对链接，无需为了文档重跑整套构建与测试。
- 测试应覆盖可观察行为与回归风险，优先扩展已有测试，不为低影响的机械性改动增加镜像实现的测试。

工具不可用或检查失败时记录实际原因；不要声称未执行的检查已通过。除非与本次任务直接相关，不顺带升级 Gradle、AGP、Kotlin 或 JitPack 快照。

## 文档与交付

- 项目文档默认使用中文，代码符号保留原名。
- 功能、构建步骤或架构边界改变时同步更新相关文档；细节放在 `docs/`，README 保留项目介绍、快速开始与导航。
- 配置值以代码为准；不把计划能力写成已经完成的功能，也不把权限、持久化或 PRoot 描述成比实现更强的保证。
- 完成后说明具体改动、验证结果及仍需设备或环境验证的部分。保留用户已有改动，不做与任务无关的清理。
