# Agent Manager 与异步工具

[返回 README](../README.md) · [架构](architecture.md) · [技能与执行证据](skills.md)

Manager 是一个 ToolSet。具体业务工具用 `@Tool` 注册，提交执行闭包后立即返回任务 ID；等待工具按 ID 返回最终 String 结果。

## 最小模型

`AgentManager` 内部只有一张 `taskId → Deferred<String>` 任务表。参数由具体工具的闭包捕获，不建立通用 Request 模型，不再经过额外的异步包装器或任务控制器。

`submitTask { ... }` 在启动前将任务放入表中，然后返回 ID。任务可以并发执行，一个任务失败不会取消其他任务。执行体中的业务结果在返回前转换为 String。

`waitTask(id)` 复用同一 Deferred：等待不会消耗结果，可以重复等待。取消等待协程不取消任务；任务异常返回失败文本，未知 ID 返回 Unknown task。提交返回 ID 仅表示任务已创建，不表示业务完成或验证通过。

## 工具入口

生产代码位于 `app/src/main/java/top/aaa000111/maid/agent/`。

| Manager | 启动工具（返回 ID） | 等待工具（返回结果） | 内部业务边界 |
| --- | --- | --- | --- |
| ChatAgentManager | executeChatTask | waitChatTask | ChatAgent 与聊天记忆 |
| PlannerAgentManager | planAndExecuteTask | waitPlannerTask | 检查点和规划笔记 |
| DeviceActionAgentManager | executeDeviceTask | waitDeviceTask | Android 授权、安全检查和设备锁 |
| ProotLinuxAgentManager | executeProotLinuxTask、executeProotLinuxTaskWithLlmApi | waitLinuxTask | 专用终端、任务锁、临时凭据清理 |
| ResearchAgentManager | executeResearchTask | waitResearchTask | 检索、阅读与来源整理 |
| BrowserUseAgentManager | executeBrowserTask | waitBrowserTask | 应用内 WebView 标签页、导航事件、离屏画面与输入 |
Planner 在 Android 运行时可委托 Browser Use Agent 操作公开 HTTPS 网页。每个任务建立自己的离屏浏览器，最多四个标签页，结束后释放。浏览器返回标签的 URL 和导航状态，支持按版本等待事件、使用截图像素输入，以及通过 `TimedWait` 定时挂起或启动异步计时。截图理解需要配置快速视觉模型。`searchBrowserHtml(tabId, goal)` 读取当前标签页的渲染后 HTML，并由 `FAST_CHEAP` 提取相关信息和证据，HTML 输入按快速模型上下文分配预算，并为提示和回复保留空间。WebView 单次回传限制在 256 KiB 的页面数据内，截断时会在结果中标明。


| SearchEngineManager（`search/`） | 五类 search 工具、readWebPage、readRawPage、readRenderedWebPage、checkSearchEngines | waitSearchTask | 搜索路由、网页抓取和来源记录 |

ResearchAgent 每次调研创建 SearchEngineManager。静态正文和原始响应分别由 readWebPage 和 readRawPage 读取。readRenderedWebPage 单独使用系统 WebView 渲染公开 HTTPS 页面，再用 Ksoup 提取正文。三个入口均返回任务 ID，由 waitSearchTask 取得结果。搜索次数、阅读次数和来源在实际任务执行中记录。底层 SearchEngine / SearchEngineRouter 的挂起业务 API 继续供内部和设置页使用。

等待逻辑由基类实现。每个具体 Manager 只用一个具名 `@Tool` 方法转发到基类，避免多个 Manager 同时挂载时产生同名工具；具体启动工具仍正常通过 Koog 的 `tools(manager)` 注册。

调用示例：

```kotlin
val manager = ResearchAgentManager(researchAgent)
val taskId = manager.executeResearchTask(topic = "主题 A")
// 此时可以继续提交其他任务。
val result: String = manager.waitResearchTask(taskId)
```

ChatAgent 挂载 Planner 和 Research Manager，PlannerAgent 挂载 Device、Linux 和 Research Manager。Android 运行时中，两者也挂载 Browser Use Manager。内部 Agent 只挂载其需要的下游工具，不把自己的 Manager 注册给自己。

每次构造上层 Agent 的工具集时创建对应 Manager，ID 只属于同一个 Manager 实例。模型需在依赖结果、推进检查点或汇报完成前调用对应等待工具；不能把 ID 当作结论，也不能在下一轮重新创建 Manager 后继续使用旧 ID。

## 内存归属

任务表保留已完成结果，直到 Manager 不再被引用。不设过期、结果分页、历史淘汰、全局登记、统一关闭或持久任务队列。

启动任务不绑定本次启动工具的 Job，因此工具返回、调用方结束或某个等待者取消都不会自动取消它。运行中的协程可以继续持有其需要的对象；执行结束且外部引用释放后，Manager 与表内结果由 GC 回收。若调用方有意长期持有 Manager，结果也会保留相同时间。

进程死亡会丢失任务表和结果，不自动重放设备操作。聊天界面的停止仍取消当前聊天 turn，不负责清理已提交的独立工具任务。

## 内部同步调用与证据

聊天 UI 继续调用 `ChatAgentManager.chatWithDetails`，在 ChatTaskDispatcher 的原有协程里执行并返回 ChatAgentReply。内部 `runPlan`、`execute`、`research`、`runTask` 仍可直接返回结构化业务结果；它们不额外建立一套通用任务句柄。

异步任务继承提交时的会话和技能上下文，在实际执行期间建立子任务证据，保留父任务 ID 和 turnId。启动工具本身的 RETURNED 只表示已返回 ID，子任务完成由自己的执行证据记录；不会因启动工具提前返回而把子任务提前标记为 COMPLETED。任务结果正文仍只留在内存，不写入 P0 证据仓库。

设备串行锁、规划检查点、Linux 任务锁与执行时安全检查仍由各自业务实现承担，简化任务表不改变这些约束。

## 验证

```bash
./gradlew :app:testDebugUnitTest --tests 'top.aaa000111.maid.agent.*' --tests 'top.aaa000111.maid.chat.ChatTaskDispatcherTest' --tests 'top.aaa000111.maid.learning.*'
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

测试覆盖提交立即返回、并发、重复等待、取消等待者、失败隔离、结果保留、实例间 ID 隔离、多个 Manager 的实际 Koog 注册，以及启动/等待工具循环、设备锁、Linux 安全与父子执行证据。真实设备上的权限后端和 PRoot 行为需另行验证。
