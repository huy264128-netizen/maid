# 固定提示词管理

[返回 README](../README.md) · [开发指南](development.md) · [架构](architecture.md)

固定提示词集中在 [app/src/main/resources/prompts/](../app/src/main/resources/prompts/)，使用 UTF-8 Markdown 文件。每份文件就是发送给模型的原文，不需要 Kotlin 字符串转义，也没有模板语法。

## 文件导航

| 目录 | 内容 |
| --- | --- |
| [chat/](../app/src/main/resources/prompts/chat/) | 对话系统提示、信息来源、长期记忆开关说明、用户附件与 Persona 引导 |
| [planner/](../app/src/main/resources/prompts/planner/) | 规划系统提示、设备/Linux/调研委托、异步等待、视觉与云端 API 开关说明 |
| [device/](../app/src/main/resources/prompts/device/) | 设备执行系统提示、视觉工具选择与步数预算建议 |
| [linux/](../app/src/main/resources/prompts/linux/) | PRoot 系统提示、共享文件系统说明、内置端侧模型用法与视觉指导 |
| [research/](../app/src/main/resources/prompts/research/) | 调研系统提示、quick/standard/deep 深度要求与视觉指导 |
| [vision/](../app/src/main/resources/prompts/vision/) | 独立视觉分析系统提示与默认问题 |
| [search/](../app/src/main/resources/prompts/search/) | TinyModel 搜索结果相关性过滤提示 |
| [skill/](../app/src/main/resources/prompts/skill/) | 技能发现、读取与权限边界说明 |

五类 Agent 的默认系统提示均为各自目录下的 `system.md`。调整 Planner 的启动后等待约定，修改 `planner/async_tasks.md`；调整调研深度，修改 `research/depth_*.md`。

## 加载与生效

`prompt/PromptResources.kt` 从 classpath 的 `/prompts/<相对路径>` 读取文本并缓存。Gradle 的默认 Java resources 打包会将这些文件放入 APK，JVM 单元测试也读取相同资源，不需要 Context 或应用初始化。加载时去掉文件末尾空白，保留内部换行；资源缺失或内容为空会明确报错。

修改现有文本后重新构建并安装应用，文件会随 APK 更新；这不是运行时外部文件覆盖或热更新功能。进程内缓存不会监听文件变化。

Agent 构造器的 `systemPrompt` 参数仍可显式注入替代默认系统提示。用户 Persona 继续由原有设置与 `assets/persona/` 管理，不与系统提示词文件合并。

## 代码保留的职责

- 权限、安全开关、可用工具和模型角色决定是否追加某个固定片段，选择逻辑仍由代码控制。
- 当前模型 ID、步数、会话 ID、附件、用户输入、规划进度等动态值仍在代码中组装；不把它们写成静态能力承诺。
- 动态安全策略、授权工具目录及其配置值仍来自原有实现，提示词外置不改变执行时校验。
- `@Tool` / `@LLMDescription` 属于编译期工具 schema，注解文案继续放在源码；本目录管理实际拼装的提示词正文。

新增固定片段时添加 Markdown 文件，再在相应组装位置调用 `PromptResources.read("目录/文件.md")`。不要引入额外模板框架，也不要维护一份重复的源码内 fallback。

## 验证

```bash
./gradlew :app:testDebugUnitTest --tests 'top.aaa000111.maid.prompt.*' --tests 'top.aaa000111.maid.agent.*'
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

资源加载测试核对提示词可以从实际 main classpath 读取；现有 Agent、步数和搜索测试验证动态内容仍正确组装。涉及工具协议、安全约束或输出格式的文案调整，也需要运行对应业务测试。
