# Maid

<p align="center">
  <img src="maid-logo-concept-v4.png" width="160" alt="Maid 女仆图标">
</p>

<p align="center">把日常交给 Maid，让想法更快落地。</p>

Maid 是一款面向 Android 的 AI 助手。你可以和它自然对话、整理思路、查找资料，也可以在授权后让它协助操作设备、运行 Linux 命令。无论是一个简单问题，还是需要多步推进的任务，都可以从聊天开始。

## 让 AI 不止于聊天

### 对话与任务协作

为不同会话保留各自的聊天记录，发送文字、图片和文件。Maid 可以围绕目标拆解步骤、调用合适的能力，并在执行过程中反馈进展和结果。

### 按你的习惯选择模型

连接支持的模型服务，分别选择日常对话、复杂任务和图像理解所用的模型。也可以使用内置的端侧小模型处理部分本地事务。

### 了解更多，再给出答案

需要查资料时，Maid 可以搜索网页、阅读页面内容，并按主题寻找学术资料、代码与 Issue、技术社区或百科信息，帮助你把零散线索整理成结果。

### 在授权范围内操作设备

根据需要开启相应权限后，Maid 可以使用截图、界面交互、应用管理、文件和部分系统设置等设备能力。你可以按用途决定开放哪些权限；敏感操作仍受应用安全设置约束。

### 随时进入 Linux 环境

应用内提供基于 PRoot 的 Linux 终端。你可以在其中运行命令、管理文件，也可以让 AI 在专用工作流中使用终端完成任务。

## 开始使用

1. 从 GitHub 克隆项目，并安装 Git LFS 后执行 `git lfs pull`，获取内置端侧模型。
2. 使用 Android Studio 打开项目并完成 Gradle 同步。首次使用还需安装项目构建所需的 Android SDK、NDK 与 CMake。
3. 构建并安装 Debug 版本：

```bash
./gradlew :app:assembleDebug
```

安装后，在设置中连接模型服务并选择日常对话模型。需要设备操作、联网调研或 Linux 功能时，再按需配置对应权限和服务。

## 了解 Maid

- [使用与配置](docs/usage.md)：连接模型、管理权限、使用 Linux 与端侧模型
- [开发与构建指南](docs/development.md)：构建环境、测试与常见问题
- [项目架构](docs/architecture.md)：模块职责与主要数据流
- [固定提示词管理](docs/prompts.md)：提示词资源与修改方式
- [Agent Manager 与异步调用](docs/agent_managers.md)：任务执行机制
- [技能与执行证据](docs/skills.md)：技能加载和任务记录
- [LLM 服务商接入](docs/llm_provider.md)：接入新的模型服务
- [Shizuku 模块](docs/shizuku.md)：Shizuku 集成说明

## 0.3.0 更新内容

- **虚拟显示器 Agent**：Planner 可以创建独立虚拟显示器并委派异步操作任务，读取该显示器的 UI 树或截图；每个显示器同一时间只由一个 Agent 操作。受信任显示器由 Shizuku UserService 创建和持有，因此需要 Shizuku 授权及设备相应的多显示器支持。
- **无头浏览器**：为网页任务提供独立的离屏浏览器，可进行导航、交互、截图和页面内容检索。
- **端侧自动标题**：新会话在收到首条消息回复后可由 TinyModel 生成简短标题，失败时保留预览标题。
- **模型思考强度**：可按模型配置思考强度，也可留空使用服务端默认值，并在模型切换时保留对应配置。
- **任务与 Agent 能力**：完善异步 Agent 工具、任务调度工具、MaidLang 脚本执行和视觉分析能力。

## 隐私与使用边界

使用云端模型、网页搜索和页面阅读时，应用会按实际任务向相应服务发送请求。凭据采用加密存储；聊天记录、附件、长期记忆和 Linux 文件各自保存，保护方式并不相同。设备能力和操作范围也会受到 Android 版本、设备状态及你授予的权限影响。

PRoot Linux 使用应用内共享的文件系统，不是会话之间相互隔离的安全沙箱。请勿在其中存放你不希望其他应用内任务接触的数据。

## 致谢

Maid 使用了 [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)、[JetBrains Koog](https://github.com/JetBrains/koog)、[PRoot / Termux](https://github.com/termux/proot)、[Shizuku](https://github.com/RikkaApps/Shizuku) 等开源项目。内置端侧模型来自 [s3nh/LFM2.5-350M-abliterated](https://huggingface.co/s3nh/LFM2.5-350M-abliterated)。

第三方代码、模型及 rootfs 中软件的许可和再分发条件，以各自项目的声明为准。
