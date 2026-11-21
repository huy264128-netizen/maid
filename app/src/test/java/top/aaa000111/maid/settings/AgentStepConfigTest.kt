package top.aaa000111.maid.settings

import ai.koog.agents.core.agent.AIAgentException
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.agent.DeviceActionAgent
import top.aaa000111.maid.agent.PlannerAgent
import top.aaa000111.maid.agent.PlannerLifecycleManager
import top.aaa000111.maid.agent.ProotLinuxAgent
import top.aaa000111.maid.agent.ResearchAgent
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.AndroidToolSet
import top.aaa000111.maid.androidtool.AndroidToolsetDispatcher

class AgentStepConfigTest {

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gpt-5",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
        ),
        contextLength = 128_000L,
    )

    private class ScriptedExecutor(
        private val onExecute: suspend (step: Int) -> List<Message.Response>,
    ) : PromptExecutor {
        var step = 0
        val capturedPrompts = mutableListOf<Prompt>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> {
            capturedPrompts += prompt
            step += 1
            return onExecute(step)
        }

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

    private class PingToolSet : ToolSet {
        override val name: String = "ping_tools"

        @Tool
        @LLMDescription("Ping tool")
        fun ping(value: String): String = "pong:$value"
    }

    private class FakeDeviceToolSet : AndroidToolSet() {
        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(AndroidPrivilege.SHIZUKU)

        @Tool
        @LLMDescription("Inspect screen")
        fun inspect(): String = "ok"
    }

    @Test
    fun defaultsAndNormalization_clampLowerBoundAndAllowArbitrarilyLargeValues() {
        assertEquals(64, AgentStepConfig.DEFAULT.chatMaxIterations)
        assertEquals(64, AgentStepConfig.DEFAULT.plannerMaxIterationsPerCheckpoint)
        assertEquals(16, AgentStepConfig.DEFAULT.plannerMaxCheckpoints)
        assertEquals(96, AgentStepConfig.DEFAULT.deviceActionMaxIterations)
        assertTrue(AgentStepConfig.DEFAULT.enableCrossChatMemory)

        val clamped = AgentStepConfig(
            chatMaxIterations = 0,
            plannerMaxIterationsPerCheckpoint = 9999,
            plannerMaxCheckpoints = 500,
            deviceActionMaxIterations = -5,
            enableCrossChatMemory = false,
        ).normalized()

        assertEquals(AgentStepConfig.MIN_AGENT_ITERATIONS, clamped.chatMaxIterations)
        assertEquals(9999, clamped.plannerMaxIterationsPerCheckpoint)
        assertEquals(500, clamped.plannerMaxCheckpoints)
        assertEquals(AgentStepConfig.MIN_AGENT_ITERATIONS, clamped.deviceActionMaxIterations)
        assertEquals(false, clamped.enableCrossChatMemory)

        assertEquals(29, AgentStepConfig.estimateChatToolCalls(64))
        assertEquals(30, AgentStepConfig.estimatePlannerToolCallsPerCheckpoint(64))
        assertEquals(31, AgentStepConfig.estimateDeviceActionToolCalls(96))
    }

    @Test
    fun allAgents_respectDynamicStepProviders() = runBlocking {
        // 1. ChatAgent: 3 次工具调用需要至少 5 + 2*3 = 11 步（默认 memory=false 时为 4 + 2*3 = 10 步）
        var chatLimit = 8
        val chatExecutor = ScriptedExecutor { step ->
            if (step <= 3) {
                listOf(Message.Tool.Call("c$step", "ping", """{"value":"$step"}""", ResponseMetaInfo.Empty))
            } else {
                listOf(Message.Assistant("chat done", ResponseMetaInfo.Empty))
            }
        }
        val chatAgent = ChatAgent(
            promptExecutor = chatExecutor,
            model = testModel,
            toolRegistry = ToolRegistry { tools(PingToolSet()) },
            plannerLifecycleManager = null,
            enableMemory = false,
            maxIterationsProvider = { chatLimit },
        )
        try {
            chatAgent.chat("run")
            fail("Expected iteration limit exception when chatLimit=8")
        } catch (e: AIAgentException) {
            assertTrue(e.message.orEmpty().contains("iterations", ignoreCase = true))
        }
        chatLimit = 20
        chatExecutor.step = 0
        assertEquals("chat done", chatAgent.chat("run again"))

        // 2. DeviceActionAgent: 3 次工具调用需要 3 + 3*3 = 12 步
        var deviceLimit = 10
        val deviceDispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(FakeDeviceToolSet()),
            privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU) },
        )
        val deviceExecutor = ScriptedExecutor { step ->
            if (step <= 3) {
                listOf(Message.Tool.Call("d$step", "inspect", "{}", ResponseMetaInfo.Empty))
            } else {
                listOf(Message.Assistant("device done", ResponseMetaInfo.Empty))
            }
        }
        val deviceAgent = DeviceActionAgent(
            promptExecutor = deviceExecutor,
            model = testModel,
            dispatcher = deviceDispatcher,
            safetyConfigProvider = { ModelSafetyConfig.DEFAULT },
            directImageInputProvider = { true },
            maxIterationsProvider = { deviceLimit },
        )
        try {
            deviceAgent.execute("run device")
            fail("Expected iteration limit exception when deviceLimit=10")
        } catch (e: AIAgentException) {
            assertTrue(e.message.orEmpty().contains("iterations", ignoreCase = true))
        }
        deviceLimit = 20
        deviceExecutor.step = 0
        val deviceResult = deviceAgent.execute("run device again")
        assertTrue(deviceResult.success)
        assertEquals("device done", deviceResult.summary)

        // 3. PlannerAgent + PlannerLifecycleManager
        var plannerStepLimit = 4
        var maxCheckpointsLimit = 2
        val plannerExecutor = ScriptedExecutor { step ->
            if (step % 2 == 1) {
                listOf(
                    Message.Tool.Call(
                        "n$step",
                        "updateLifecycleNote",
                        """{"key":"k$step","value":"v$step"}""",
                        ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Tool.Call(
                        "f$step",
                        "finish",
                        """{"action":"next_checkpoint","summary":"cp $step"}""",
                        ResponseMetaInfo.Empty,
                    ),
                )
            }
        }
        val plannerAgent = PlannerAgent(
            promptExecutor = plannerExecutor,
            model = testModel,
            directImageInputProvider = { true },
            maxIterationsProvider = { plannerStepLimit },
        )
        val manager = PlannerLifecycleManager(
            plannerAgent = plannerAgent,
            maxCheckpointsProvider = { maxCheckpointsLimit },
        )
        try {
            manager.runPlan(goal = "步数过小触发超限")
            fail("Expected iteration limit exception when plannerStepLimit=4")
        } catch (e: AIAgentException) {
            assertTrue(e.message.orEmpty().contains("iterations", ignoreCase = true))
        }

        plannerStepLimit = 16
        plannerExecutor.step = 0
        val planRes = manager.runPlan(goal = "多检查点任务")
        assertTrue(planRes.success)
        assertEquals(2, planRes.checkpointsCompleted)

        // 若调大检查点上限为 3，则执行 3 轮检查点
        maxCheckpointsLimit = 3
        plannerStepLimit = 16
        plannerExecutor.step = 0
        val planRes3 = manager.runPlan(goal = "三检查点任务")
        assertEquals(3, planRes3.checkpointsCompleted)
    }

    @Test
    fun allAgentsIncludeConfiguredStepLimitsInEffectiveSystemPrompt() = runBlocking {
        // 1. 静态构建验证：所有 5 个 Agent 的 system prompt 均包含当前步数限制与预估工具调用次数
        val chatPrompt = ChatAgent.buildEffectiveSystemPrompt(
            baseSystemPrompt = ChatAgent.DEFAULT_SYSTEM_PROMPT,
            personaText = "",
            maxIterations = 45,
        )
        assertTrue(chatPrompt.contains("[当前执行步数限制]"))
        assertTrue(chatPrompt.contains("45 步"))
        assertTrue(chatPrompt.contains("${AgentStepConfig.estimateChatToolCalls(45)} 次工具调用"))

        val plannerPrompt = PlannerAgent.buildEffectiveSystemPrompt(
            baseSystemPrompt = PlannerAgent.DEFAULT_SYSTEM_PROMPT,
            hasDeviceActionTool = true,
            hasVisionTool = false,
            maxIterationsPerCheckpoint = 50,
            maxCheckpoints = 12,
        )
        assertTrue(plannerPrompt.contains("[当前执行步数限制]"))
        assertTrue(plannerPrompt.contains("50 步"))
        assertTrue(plannerPrompt.contains("${AgentStepConfig.estimatePlannerToolCallsPerCheckpoint(50)} 次工具调用"))
        assertTrue(plannerPrompt.contains("12 个"))

        val devicePrompt = DeviceActionAgent.buildEffectiveSystemPrompt(
            baseSystemPrompt = DeviceActionAgent.DEFAULT_SYSTEM_PROMPT,
            safetyConfig = ModelSafetyConfig.DEFAULT,
            hasVisionTool = false,
            maxIterations = 72,
        )
        assertTrue(devicePrompt.contains("[当前执行步数限制]"))
        assertTrue(devicePrompt.contains("72 步"))
        assertTrue(devicePrompt.contains("${AgentStepConfig.estimateDeviceActionToolCalls(72)} 次工具调用"))

        val prootPrompt = ProotLinuxAgent.buildEffectiveSystemPrompt(
            baseSystemPrompt = ProotLinuxAgent.DEFAULT_SYSTEM_PROMPT,
            sessionId = "s1",
            suggestedWorkingDirDisplay = "~/s1/",
            suggestedWorkingDirPosix = "/root/s1/",
            hasVisionTool = false,
            maxIterations = 84,
        )
        assertTrue(prootPrompt.contains("[当前执行步数限制]"))
        assertTrue(prootPrompt.contains("84 步"))
        assertTrue(prootPrompt.contains("${AgentStepConfig.estimateDeviceActionToolCalls(84)} 次工具调用"))

        val researchPrompt = ResearchAgent.buildEffectiveSystemPrompt(
            baseSystemPrompt = ResearchAgent.DEFAULT_SYSTEM_PROMPT,
            maxIterations = 30,
        )
        assertTrue(researchPrompt.contains("[当前执行步数限制]"))
        assertTrue(researchPrompt.contains("30 步"))
        assertTrue(researchPrompt.contains("${AgentStepConfig.estimateResearchToolCalls(30)} 次工具调用"))

        // 2. 运行时动态生效验证：ChatAgent & PlannerAgent 实际发给 LLM 的 System 消息会随配置实时变化
        var dynamicChatSteps = 33
        val chatExecutor = ScriptedExecutor {
            listOf(Message.Assistant("ok", ResponseMetaInfo.Empty))
        }
        val chatAgent = ChatAgent(
            promptExecutor = chatExecutor,
            model = testModel,
            enableMemory = false,
            autoSaveMemory = false,
            plannerLifecycleManager = null,
            visionToolSet = null,
            personaProvider = { "" },
            maxIterationsProvider = { dynamicChatSteps },
        )
        chatAgent.chat("first")
        val firstChatSystem = chatExecutor.capturedPrompts.last().messages
            .filterIsInstance<Message.System>()
            .first()
            .content
        assertTrue(firstChatSystem.contains("33 步"))

        dynamicChatSteps = 77
        chatAgent.chat("second")
        val secondChatSystem = chatExecutor.capturedPrompts.last().messages
            .filterIsInstance<Message.System>()
            .first()
            .content
        assertTrue(secondChatSystem.contains("77 步"))

        val plannerExecutor = ScriptedExecutor {
            listOf(
                Message.Tool.Call(
                    "f1",
                    "finish",
                    """{"action":"final_exit","summary":"done"}""",
                    ResponseMetaInfo.Empty,
                ),
            )
        }
        val plannerAgent = PlannerAgent(
            promptExecutor = plannerExecutor,
            model = testModel,
            directImageInputProvider = { true },
            maxIterationsProvider = { 42 },
        )
        val manager = PlannerLifecycleManager(
            plannerAgent = plannerAgent,
            maxCheckpointsProvider = { 9 },
        )
        manager.runPlan(goal = "验证规划步数提示")
        val plannerSystem = plannerExecutor.capturedPrompts.last().messages
            .filterIsInstance<Message.System>()
            .first()
            .content
        assertTrue(plannerSystem.contains("42 步"))
        assertTrue(plannerSystem.contains("9 个"))
    }
}
