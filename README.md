# Maid

<p align="center">
  <img src="maid-logo-concept-v4.png" width="160" alt="Maid 女仆图标">
</p>

Maid 是一个 Android LLM 助手，使用 Kotlin、Jetpack Compose Material 3 和 JetBrains Koog，支持多会话聊天、任务规划、设备操作、联网调研及 PRoot Linux 终端。

项目只有一个 Android 应用模块 `:app`，包名为 `top.aaa000111.maid`。最低支持 Android 12（API 31），当前编译和目标 SDK 均为 API 37。

## 功能

- **多会话聊天**：Markdown 回复、文件与图片附件、工具执行状态、会话持久化；切换会话不会主动中断正在执行的后台任务。
- **模型服务接入**：API Key、Sign in with ChatGPT，以及多个自定义 OpenAI 兼容端点；分别配置对话、规划和视觉模型。
- **任务执行**：五类 Agent 通过对应 Manager 工具提交任务并返回 ID，再调用等待工具取得文字结果，支持并发执行；规划任务按检查点推进。见 [Agent Manager](docs/agent_managers.md)。
- **Android 工具**：输入、截图、应用与窗口控制、文件、系统设置、使用情况和设备策略等；按当前授权动态开放能力。
- **Linux 环境**：内置 PRoot 与 rootfs 归档，提供交互终端及 Agent 专用终端。
- **联网调研**：分类搜索、可用性探测、同类引擎回退、网页正文提取。
- **技能复用与执行证据**：只读技能发现与加载、任务固定版本、父子任务与工具执行记录；当前完成 P0/P1，自动回顾尚未启用。详情见[技能与执行证据](docs/skills.md)。
- **端侧模型**：通过 LiteRT-LM 运行内置或导入的 `.litertlm` 模型，用于内部事务，并提供本地 HTTP 桥接。
- **设置与存储**：人设、Agent 步数、安全策略、长期记忆，以及基于 Tink 和 Android KeyStore 的敏感凭据加密存储。

## 快速开始

1. 使用支持项目 AGP 版本的 Android Studio 打开根目录并同步 Gradle。
2. 安装 Android SDK Platform 37、NDK（Side by side）与 CMake 3.22.1。Gradle Daemon 使用 Java 25，具体环境见[开发与构建指南](docs/development.md)。
3. 确认内置资产存在：`app/src/main/assets/debian-rootfs.tar.zst` 与 `app/src/main/assets/models/LFM2.5-350M-abliterated_int4.litertlm`。模型通过 Git LFS 管理，克隆仓库时需安装 Git LFS 并执行 `git lfs pull`。
4. 构建 Debug APK：

```bash
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。可使用 Android Studio 的 Run 安装到 API 31 及以上的 `arm64-v8a` 或 `x86_64` 设备。

首次进入应用后，在设置中配置服务商凭据，并在「模型选择」中至少配置快速廉价模型（`FAST_CHEAP`），再开始聊天。设备操作所需权限按功能单独配置，详见[使用与配置](docs/usage.md)。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [使用与配置](docs/usage.md) | 首次聊天、四种模型角色、权限、Linux 与端侧模型 |
| [开发与构建指南](docs/development.md) | 工具链、构建、测试、调试、常见问题 |
| [架构与代码导航](docs/architecture.md) | 运行态、会话、Agent、模型、权限及数据流 |
| [固定提示词管理](docs/prompts.md) | 外部 Markdown 提示词、动态组装与验证 |
| [Agent Manager 与异步调用](docs/agent_managers.md) | Manager 工具、任务 ID、等待结果与内存归属 |
| [技能与执行证据](docs/skills.md) | 技能版本、只读工具、任务事实与存储边界 |
| [LLM 服务商接入](docs/llm_provider.md) | Provider 注册、鉴权、执行器及接入自检 |
| [Shizuku 模块](docs/shizuku.md) | Binder、UserService、命令执行与系统服务调用 |
| [AGENTS.md](AGENTS.md) | 面向代码助手的项目约定与验证要求 |

## 目录

```text
app/
  src/main/java/top/aaa000111/maid/   应用 Kotlin 代码
  src/main/aidl/                     Shizuku IPC 接口
  src/main/cpp/                      PRoot、loader、talloc 与 CMake
  src/main/assets/                   rootfs、端侧模型与默认人设
  src/main/res/                      字符串、主题与图标
  src/test/                         JVM 单元测试
  src/androidTest/                  设备端测试
third_party/vtfx/                    直接编入 app 的终端源码
gradle/                             Wrapper、版本目录与 Daemon JVM 配置
docs/                               使用、开发和模块文档
```

## 数据与能力边界

凭据加密存储不代表聊天记录、附件和 Linux 文件全部经过加密。云端模型、搜索引擎和网页抓取会产生网络请求；发送内容由实际选择的服务商与工具调用决定。PRoot 使用应用私有目录中的共享 rootfs，不能视为会话之间的强隔离安全沙箱。

## 致谢

- [s3nh/LFM2.5-350M-abliterated](https://huggingface.co/s3nh/LFM2.5-350M-abliterated)：内置 TinyModel 模型来源。
- [Liquid AI](https://huggingface.co/LiquidAI)、[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)、[JetBrains Koog](https://github.com/JetBrains/koog)、[PRoot / Termux](https://github.com/termux/proot)、[Shizuku](https://github.com/RikkaApps/Shizuku)。

第三方源码、模型及 rootfs 中软件的许可和再分发条件应分别核对各自声明。
