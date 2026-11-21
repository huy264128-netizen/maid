package top.aaa000111.maid.chat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.agent.waitForAgentTask
import top.aaa000111.maid.agent.DeviceActionAgent
import top.aaa000111.maid.agent.PlannerAgent
import top.aaa000111.maid.agent.PlannerLifecycleManager
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.AndroidToolSet
import top.aaa000111.maid.androidtool.AndroidToolsetDispatcher
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.settings.ModelSafetyConfig

class ToolCallStatusTrackerTest {

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gpt-5",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.Vision.Image,
        ),
        contextLength = 128_000L,
    )

    private class SuspendingPromptExecutor(
        private val responder: suspend (prompt: Prompt, tools: List<ToolDescriptor>) -> List<Message.Response>,
    ) : PromptExecutor {
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> = responder(prompt, tools)

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = emptyFlow()

        override suspend fun moderate(
            prompt: Prompt,
            model: LLModel,
        ): ModerationResult = throw UnsupportedOperationException()
    }

    internal class SuspendingDemoToolSet(
        private val enteredGate: CompletableDeferred<Unit>,
        private val releaseGate: CompletableDeferred<Unit>,
    ) : ToolSet {
        @Tool
        @LLMDescription("Query weather for a city")
        suspend fun queryWeather(
            @LLMDescription("City name") city: String,
        ): String {
            enteredGate.complete(Unit)
            releaseGate.await()
            return "$city 晴 24°C"
        }
    }

    internal class SuspendingDeviceTapToolSet(
        private val enteredTapGate: CompletableDeferred<Unit>,
        private val releaseTapGate: CompletableDeferred<Unit>,
    ) : AndroidToolSet() {
        override val name: String = "input"

        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(AndroidPrivilege.SHIZUKU)

        @Tool
        @LLMDescription("Tap screen at coordinates")
        suspend fun tap(
            @LLMDescription("X coordinate") x: Int,
            @LLMDescription("Y coordinate") y: Int,
        ): String {
            enteredTapGate.complete(Unit)
            releaseTapGate.await()
            return "Tapped ($x, $y)"
        }
    }

    @Test
    fun chatAgent_showsSpecificToolWhileRunning_andMarksToolStatusCompletedWhenFinished() = runBlocking {
        val enteredGate = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val demoTools = SuspendingDemoToolSet(enteredGate, releaseGate)

        var step = 0
        val executor = SuspendingPromptExecutor { _, _ ->
            step += 1
            if (step == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "call_weather_1",
                        tool = "queryWeather",
                        content = """{"city":"杭州"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Assistant(
                        content = "杭州今天晴，24°C。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val session = ChatSession(id = "tool-status-session")
        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            provider = LlmProvider.OPENAI,
            toolRegistry = ToolRegistry { tools(demoTools) },
            enableMemory = false,
            autoSaveMemory = false,
        )

        val chatDeferred = async {
            agent.chat(userMessage = "查一下杭州天气", session = session)
        }

        // 工具正在执行时：activeToolName 显示具体工具名，消息列表中存在未完成的 ToolStatusItem
        withTimeout(3_000L) { enteredGate.await() }
        assertEquals("queryWeather", session.activeToolName)
        val runningItem = session.items.filterIsInstance<ChatItem.ToolStatusItem>().single()
        assertEquals("调用工具：queryWeather", runningItem.title)
        assertTrue(runningItem.detail.contains("city=杭州"))
        assertFalse(runningItem.isCompleted)

        // 放行工具执行完成
        releaseGate.complete(Unit)
        val reply = withTimeout(3_000L) { chatDeferred.await() }
        assertEquals("杭州今天晴，24°C。", reply)

        // 工具执行结束后：activeToolName 清空，ToolStatusItem 更新为已完成并包含结果摘要
        assertNull(session.activeToolName)
        val completedItem = session.items.filterIsInstance<ChatItem.ToolStatusItem>().single()
        assertEquals("调用工具：queryWeather", completedItem.title)
        assertTrue(completedItem.isCompleted)
        assertTrue(completedItem.detail.contains("city=杭州"))
        assertTrue(completedItem.detail.contains("杭州 晴 24°C"))
    }

    @Test
    fun asyncPlannerAndDeviceTasksTrackSubmissionWaitersAndActualOperation() = runBlocking {
        val enteredTapGate = CompletableDeferred<Unit>()
        val releaseTapGate = CompletableDeferred<Unit>()
        val tapToolSet = SuspendingDeviceTapToolSet(enteredTapGate, releaseTapGate)
        val androidDispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(tapToolSet),
            privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU) },
        )

        var deviceStep = 0
        val deviceExecutor = SuspendingPromptExecutor { _, _ ->
            deviceStep += 1
            if (deviceStep == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "dev_tap_1",
                        tool = "tap",
                        content = """{"x":320,"y":640}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Assistant(
                        content = "已在 (320, 640) 完成点击",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }
        val deviceActionAgent = DeviceActionAgent(
            promptExecutor = deviceExecutor,
            model = testModel,
            dispatcher = androidDispatcher,
            directImageInputProvider = { true },
            safetyConfigProvider = { ModelSafetyConfig.BALANCED },
        )

        var plannerStep = 0
        val plannerExecutor = SuspendingPromptExecutor { prompt, _ ->
            plannerStep += 1
            if (plannerStep == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "plan_dev_1",
                        tool = "executeDeviceTask",
                        content = """{"task":"点击屏幕中央按钮"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else if (plannerStep == 2) {
                waitForAgentTask(prompt, "waitDeviceTask")
            } else {
                listOf(
                    Message.Tool.Call(
                        id = "plan_finish_1",
                        tool = "finish",
                        content = """{"action":"final_exit","summary":"点击任务已完成"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }
        val plannerAgent = PlannerAgent(
            promptExecutor = plannerExecutor,
            model = testModel,
            deviceActionAgent = deviceActionAgent,
            directImageInputProvider = { true },
        )
        val lifecycleManager = PlannerLifecycleManager(plannerAgent = plannerAgent)

        var chatStep = 0
        val chatExecutor = SuspendingPromptExecutor { prompt, _ ->
            chatStep += 1
            if (chatStep == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "chat_plan_1",
                        tool = "planAndExecuteTask",
                        content = """{"goal":"帮我点击屏幕按钮"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else if (chatStep == 2) {
                waitForAgentTask(prompt, "waitPlannerTask")
            } else {
                listOf(
                    Message.Assistant(
                        content = "已经帮你点击屏幕按钮啦！",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val session = ChatSession(id = "nested-tool-session")
        val chatAgent = ChatAgent(
            promptExecutor = chatExecutor,
            model = testModel,
            provider = LlmProvider.OPENAI,
            plannerLifecycleManager = lifecycleManager,
            enableMemory = false,
            autoSaveMemory = false,
        )

        val chatDeferred = async {
            chatAgent.chat(userMessage = "帮我点一下按钮", session = session)
        }

        // 启动工具只负责提交；设备执行期间，上层明确等待各自的任务 ID。
        try {
            withTimeout(3_000L) { enteredTapGate.await() }
            val expectedActive = setOf("waitPlannerTask", "waitDeviceTask", "tap（点击屏幕）")
            withTimeout(3_000L) {
                while (session.activeToolCalls.toSet() != expectedActive) delay(5)
            }
            assertEquals(session.activeToolCalls.last(), session.activeToolName)
            val runningTools = session.items.filterIsInstance<ChatItem.ToolStatusItem>()
            assertEquals(5, runningTools.size)
            assertTrue(runningTools.single { it.title == "调用工具：planAndExecuteTask（委托复杂任务规划）" }.isCompleted)
            assertTrue(runningTools.single { it.title == "[规划] 调用工具：executeDeviceTask（执行设备操作任务）" }.isCompleted)
            val tap = runningTools.single { it.title == "[设备执行] 调用工具：tap（点击屏幕）" }
            assertTrue(tap.detail.contains("x=320, y=640"))
            assertFalse(tap.isCompleted)
            assertFalse(runningTools.single { it.title == "调用工具：waitPlannerTask" }.isCompleted)
            assertFalse(runningTools.single { it.title == "[规划] 调用工具：waitDeviceTask" }.isCompleted)
        } finally {
            releaseTapGate.complete(Unit)
        }
        val finalReply = withTimeout(3_000L) { chatDeferred.await() }
        assertEquals("已经帮你点击屏幕按钮啦！", finalReply)

        assertNull(session.activeToolName)
        assertTrue(session.activeToolCalls.isEmpty())

        val completedTools = session.items.filterIsInstance<ChatItem.ToolStatusItem>()
        assertEquals(6, completedTools.size)
        assertTrue(completedTools.all { it.isCompleted })
        assertEquals(1, completedTools.count { it.title == "[规划] 调用工具：finish（推进/完成规划检查点）" })
    }
}
