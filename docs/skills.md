# 技能与执行证据（P0 / P1）

[返回 README](../README.md) · [架构](architecture.md) · [开发指南](development.md)

当前版本建立任务事实记录和只读技能复用能力。自动回顾、候选审阅、行为评测、自动发布及技能库整理属于后续阶段，尚未启用。

## 使用

内置 `task-verification` 技能介绍如何区分工具返回、结果验证和失败排查。可以在聊天中要求“使用 task-verification 技能检查这次任务的结果”。

Chat、Planner、DeviceAction、PRoot Linux 和 Research Agent 在有任务上下文时获得两个工具：

| 工具 | 用途 |
| --- | --- |
| `listSkills(query)` | 按 ID、名称或描述检索，仅返回技能元数据 |
| `readSkill(skillId, filePath)` | 读取固定版本的 `SKILL.md` 或支持文件 |

技能加载由模型按需要选择，不保证每次任务都加载内置技能。读取技能不会授予 Android 权限，不绕过既有工具授权和安全策略，也不会自动执行技能包中的脚本。

P1 没有新增技能管理页面或导入工具。内置包由应用安装；额外技能可通过受信任的应用侧 `SkillRepository.install` 接口安装，模型没有写入接口。

## 模块

生产代码位于 `app/src/main/java/top/aaa000111/maid/`：

| 文件 | 职责 |
| --- | --- |
| `learning/TaskEvidence.kt` | 执行状态、验证状态、工具调用、输入与技能使用数据 |
| `learning/TaskEvidenceRepository.kt` | 独立 JSON 证据仓库、并发更新、进程中断恢复 |
| `learning/TaskExecutionContext.kt` | 协程上下文、父子任务、工具证据及任务固定技能快照 |
| `learning/LearningRuntime.kt` | 应用级初始化、内置技能安装和仓储组合 |
| `skill/SkillRepository.kt` | 完整技能包哈希、不可变版本和活动索引 |
| `skill/SkillResolver.kt` | 确定性的名称与描述检索 |
| `skill/SkillToolSet.kt` | 只读技能工具 |
| `skill/SkillAgentIntegration.kt` | 为 Agent 注册技能工具和使用说明 |

## 执行事实

`ChatTaskDispatcher` 创建对话执行上下文，模型调用通过 `ChatAgentManager` 执行；各类 Manager 将调用任务与证据关联，具体见 [Agent Manager](agent_managers.md)。直接调用底层 ChatAgent 时，也可在运行态已初始化后建立根任务。Manager 在异步执行规划、设备操作、Linux 或调研任务时建立子任务，保留相同 turnId 与父任务 ID。启动工具返回 ID 只表示提交完成；子任务的证据状态随实际执行结束更新。

回答待处理问题和规划中的补充输入关联到当前任务，不生成新的完成任务。规划器记录 planId 和检查点里程碑；检查点推进与最终退出是不同事件。

执行状态与验证状态独立：

- `COMPLETED`：执行流程正常返回，不代表业务结果已验证。
- `FAILED`：执行抛出异常。
- `CANCELLED`：协程被取消。
- `INTERRUPTED`：下次初始化恢复时发现上次进程留下的 RUNNING 记录。
- `REJECTED`：例如模型未配置，任务未进入模型执行。
- 验证状态默认 `UNKNOWN`，不从模型自述或工具自然语言结果推断通过。

工具区分 RUNNING、RETURNED、THREW、CANCELLED 与 INTERRUPTED。RETURNED 不意味着命令退出码为零，也不意味着返回的业务结果成功。

记录保留任务、会话、模型、权限概况、时间、异常类型与所读技能版本。输入、最终输出和工具卡片通过消息 ID 与已有会话关联；直接调用不生成 GUI 消息的 Agent API 时，相应消息引用可为空。证据仓库不保存用户原文、工具参数、结果正文或异常消息，避免复制敏感载荷。未来回顾需要通过明确的脱敏证据构建步骤选择材料，当前不会把聊天历史自动发送给额外模型。

## 存储与恢复

数据位于 `context.noBackupFilesDir/learning/`，与聊天快照和 Koog 长期记忆分开：

```text
tasks/<taskId>.json
skills/active.json
skills/versions/<skillId>-<sha256>.json
```

仓库在 IO 调度器中执行操作，使用进程内 Mutex、同目录临时文件、文件同步和原子替换。损坏文件显式报错，不默默视为不存在。首次后台任务按需完成初始化和中断恢复，不在主线程扫描或写入技能库。

这是单应用进程的仓储设计；未提供跨进程写入协议。PRoot 与其他同 UID 代码也不能被此目录约定视为强隔离沙箱。

证据目录当前没有自动清理或回顾队列；后续版本需增加保留策略。当前设置中的聊天记录清理不等于清除此独立证据目录。

## 技能包与版本

P1 技能包以 `Map<String, String>` 表示，支持 UTF-8 文本：

- 必须有 `SKILL.md`。
- 支持 `references/`、`templates/`、`scripts/`、`assets/` 下的文本文件。
- 不允许绝对路径、上级路径、反斜杠或非法路径分段。
- 最多 64 个文件，单文件最多 128 KiB，完整包最多 512 KiB。
- frontmatter 必须有单行 `name` 和 `description`，并有非空正文。当前不是完整 YAML 解析器，不支持多行描述。
- ID 使用小写字母、数字、连字符和下划线，最长 64 字符。
- 完整包的所有文件共同参与 SHA-256；仅修改参考文件也会产生新版。
- 安装先持久化完整包，再原子替换活动索引；可通过 `expectedVersion` 拒绝过期写入。

任务开始时固定技能目录版本，子任务继承同一快照。即使新版在首次读取前发布，旧任务仍读取旧版；新任务读取新索引。每次读取校验包内容哈希，并记录实际读取的技能、版本和文件路径；同一任务重复读同一文件不会重复累计使用记录。

内置技能源文件：`app/src/main/assets/skills/task-verification/SKILL.md`。应用不会覆盖同 ID、来源为 local 的活动技能。旧版本暂时保留，供运行中任务读取。

## 验证

```bash
./gradlew :app:testDebugUnitTest --tests 'top.aaa000111.maid.learning.*' --tests 'top.aaa000111.maid.skill.*'
./gradlew :app:testDebugUnitTest --tests 'top.aaa000111.maid.chat.ChatTaskDispatcherTest' --tests 'top.aaa000111.maid.agent.ChatAgentTest' --tests 'top.aaa000111.maid.agent.PlannerAgentTest'
./gradlew :app:assembleDebug
```

测试使用临时目录和 fake 模型，覆盖版本固定、重启读取、非法路径、哈希损坏、写入冲突、取消、中断恢复、并发工具、父子任务、问题回答、规划里程碑和真实 Koog 技能工具循环。真实设备上的权限后端与 PRoot 行为仍应另外验证。
