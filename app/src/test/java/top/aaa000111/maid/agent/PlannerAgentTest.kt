package top.aaa000111.maid.agent

import top.aaa000111.maid.learning.TaskEvidenceRepository
import top.aaa000111.maid.learning.TaskRecorder
import top.aaa000111.maid.learning.VerificationStatus
import top.aaa000111.maid.skill.SkillRepository

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.memory.model.MemoryScope
import ai.koog.agents.memory.model.SingleFact
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.AndroidToolSet
import top.aaa000111.maid.androidtool.AndroidToolsetDispatcher
import top.aaa000111.maid.chat.ChatItem
import top.aaa000111.maid.chat.ChatModelAvailability
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.ChatSessionState
import top.aaa000111.maid.chat.ChatTaskDispatcher
import top.aaa000111.maid.chat.UserInput
import top.aaa000111.maid.memory.MaidMemoryManager
import top.aaa000111.maid.memory.MaidMemorySubjects
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.settings.ModelSafetyConfig

class PlannerAgentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val planningModel = LLModel(
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
        private val onExecute: suspend (prompt: Prompt, tools: List<ToolDescriptor>) -> List<Message.Response>,
    ) : PromptExecutor {
        val capturedPrompts = mutableListOf<Prompt>()
        val capturedTools = mutableListOf<List<ToolDescriptor>>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> {
            capturedPrompts.add(prompt)
            capturedTools.add(tools)
            return onExecute(prompt, tools)
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

    internal class FakeDeviceUiToolSet : AndroidToolSet() {
        var openedSettingsCount = 0

        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(AndroidPrivilege.SHIZUKU)

        @Tool
        @LLMDescription("Open system settings page")
        fun openSystemSettings(): String {
            openedSettingsCount += 1
            return "settings_opened"
        }
    }

    @Before
    fun setUp() {
        MaidMemoryManager.init(rootPath = tempFolder.root.toPath())
    }

    @After
    fun tearDown() {
        MaidMemoryManager.resetForTest()
    }

    @Test
    fun plannerAgent_isNotAToolSet_whileLifecycleManagerExposesPlanningTool() {
        val planner = PlannerAgent(
            promptExecutor = SuspendingPromptExecutor { _, _ -> emptyList() },
            model = planningModel,
        )
        assertFalse(ToolSet::class.java.isAssignableFrom(planner::class.java))

        val manager = PlannerLifecycleManager(plannerAgent = planner)
        val bridgeToolSet = PlannerAgentManager(lifecycle = manager)
        assertTrue(bridgeToolSet is ToolSet)
    }

    @Test
    fun lifecycleManager_runsCheckpoints_callsDeviceActionAgent_andPreservesLifecycleMemory() = runBlocking {
        val uiToolSet = FakeDeviceUiToolSet()
        val androidDispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(uiToolSet),
            privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU) },
        )

        var deviceCallCount = 0
        val deviceExecutor = SuspendingPromptExecutor { _, _ ->
            deviceCallCount += 1
            if (deviceCallCount == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "dev_call_1",
                        tool = "openSystemSettings",
                        content = "{}",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Assistant(
                        content = "已打开系统设置页面。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }
        val deviceActionAgent = DeviceActionAgent(
            promptExecutor = deviceExecutor,
            model = planningModel,
            dispatcher = androidDispatcher,
            safetyConfigProvider = { ModelSafetyConfig.DEFAULT },
            directImageInputProvider = { true },
        )

        var plannerStep = 0
        val plannerExecutor = SuspendingPromptExecutor { prompt, tools ->
            plannerStep += 1
            when (plannerStep) {
                1 -> {
                    val toolNames = tools.map { it.name }
                    assertTrue(toolNames.contains("setCheckpoints"))
                    assertTrue(toolNames.contains("updateLifecycleNote"))
                    assertTrue(toolNames.contains("finish"))
                    assertTrue(toolNames.contains("executeDeviceTask"))
                    listOf(
                        Message.Tool.Call(
                            id = "p_cp_1",
                            tool = "setCheckpoints",
                            content = """{"checkpoints":["打开系统设置","汇报结果"],"notes":"优先安全操作"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                }

                2 -> listOf(
                    Message.Tool.Call(
                        id = "p_note_1",
                        tool = "updateLifecycleNote",
                        content = """{"key":"target_page","value":"system_settings"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )

                3 -> listOf(
                    Message.Tool.Call(
                        id = "p_dev_1",
                        tool = "executeDeviceTask",
                        content = """{"task":"打开手机系统设置"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )

                4 -> waitForAgentTask(prompt, "waitDeviceTask")

                5 -> listOf(
                    Message.Tool.Call(
                        id = "p_finish_1",
                        tool = "finish",
                        content = """{"action":"next_checkpoint","summary":"已完成第1个检查点：系统设置已打开"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )

                else -> listOf(
                    Message.Tool.Call(
                        id = "p_finish_2",
                        tool = "finish",
                        content = """{"action":"final_exit","summary":"全部任务完成：系统设置已打开并已确认。"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val plannerAgent = PlannerAgent(
            promptExecutor = plannerExecutor,
            model = planningModel,
            deviceActionAgent = deviceActionAgent,
            directImageInputProvider = { true },
        )
        val manager = PlannerLifecycleManager(plannerAgent = plannerAgent)

        val evidence = TaskEvidenceRepository(tempFolder.root.toPath().resolve("evidence"))
        val skills = SkillRepository(tempFolder.root.toPath().resolve("skills"))
        skills.install(mapOf("SKILL.md" to """
            ---
            name: settings-check
            description: 核对系统设置的执行结果
            ---

            Skill body only available through readSkill.
        """.trimIndent()))
        val recorder = TaskRecorder(evidence, skills)
        val result = recorder.run("planner-session", agentType = "PLANNER") {
            manager.runPlan(goal = "打开手机系统设置并确认")
        }
        val tasks = evidence.list()
        assertEquals(2, tasks.size)
        val planEvidence = tasks.single { it.agentType == "PLANNER" }
        assertEquals(result.planId, planEvidence.planId)
        assertEquals(listOf("CHECKPOINT_STARTED", "CHECKPOINT_ADVANCED", "CHECKPOINT_STARTED", "PLANNER_FINAL_EXIT"),
            planEvidence.milestones.map { it.kind })
        assertEquals(planEvidence.taskId, tasks.single { it.agentType == "DEVICE" }.parentTaskId)
        assertTrue(tasks.all { it.verification == VerificationStatus.UNKNOWN })

        assertTrue(result.success)
        assertEquals(2, result.checkpointsCompleted)
        assertEquals("全部任务完成：系统设置已打开并已确认。", result.finalContent)
        assertEquals(1, uiToolSet.openedSettingsCount)
        assertEquals(PlannerLifecycleStatus.COMPLETED, result.memory.status)
        assertEquals("system_settings", result.memory.notes["target_page"])
        assertEquals("优先安全操作", result.memory.notes["plan_notes"])
        assertEquals(0, manager.activePlanCount())
        assertNotNull(manager.lastCompletedMemory)

        // 验证第 2 个检查点的 Prompt 中携带了生命周期记忆和第 1 个检查点摘要
        val checkpoint2Prompt = plannerExecutor.capturedPrompts.last().messages
            .joinToString("\n") { it.content }
        assertTrue(checkpoint2Prompt.contains("已完成第1个检查点：系统设置已打开"))
        assertTrue(checkpoint2Prompt.contains("target_page: system_settings"))
        assertTrue(checkpoint2Prompt.contains("settings-check"))
        assertTrue(checkpoint2Prompt.contains("核对系统设置的执行结果"))
        assertTrue(!checkpoint2Prompt.contains("Skill body only available through readSkill."))
    }

    @Test
    fun plannerAgent_handlesDirectVisionVsVisionToolConsistentWithDeviceActionAgent() = runBlocking {
        val imageFile = tempFolder.newFile("plan_ref.png")
        imageFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val imageUri = imageFile.toURI().toString()

        // 1. COMPLEX_PLANNING == COMPLEX_VISION -> 直接传入图片附件，跳过 VisionToolSet
        val directExecutor = SuspendingPromptExecutor { _, _ ->
            listOf(
                Message.Tool.Call(
                    id = "f_direct",
                    tool = "finish",
                    content = """{"action":"final_exit","summary":"直接识图完成"}""",
                    metaInfo = ResponseMetaInfo.Empty,
                ),
            )
        }
        val directPlanner = PlannerAgent(
            promptExecutor = directExecutor,
            model = planningModel,
            directImageInputProvider = { true },
        )
        val directManager = PlannerLifecycleManager(plannerAgent = directPlanner)
        val directRes = directManager.runPlan(goal = "看图规划", imageUris = listOf(imageUri))
        assertEquals("直接识图完成", directRes.finalContent)
        assertFalse(directExecutor.capturedTools.single().any { it.name == "analyzeImage" })
        val directUserMsg = directExecutor.capturedPrompts.single().messages.filterIsInstance<Message.User>().single()
        assertTrue(directUserMsg.attachments.single() is Attachment.Image)

        // 2. 模型不同 -> 挂载 VisionToolSet
        val visionExecutor = SuspendingPromptExecutor { _, _ ->
            listOf(Message.Assistant(content = "界面显示设置页", metaInfo = ResponseMetaInfo.Empty))
        }
        val visionTool = VisionToolSet(
            visionBindingProvider = {
                ChatModelBinding(
                    executor = visionExecutor,
                    model = planningModel,
                    provider = LlmProvider.OPENAI,
                )
            },
        )
        var step = 0
        val indirectExecutor = SuspendingPromptExecutor { _, _ ->
            step += 1
            if (step == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "v_call",
                        tool = "analyzeImage",
                        content = """{"imageUri":"$imageUri","question":"图片里是什么？"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Tool.Call(
                        id = "f_indirect",
                        tool = "finish",
                        content = """{"action":"final_exit","summary":"通过视觉工具识图完成"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }
        val indirectPlanner = PlannerAgent(
            promptExecutor = indirectExecutor,
            model = planningModel,
            visionToolSet = visionTool,
            directImageInputProvider = { false },
        )
        val indirectRes = PlannerLifecycleManager(plannerAgent = indirectPlanner)
            .runPlan(goal = "调用视觉工具规划", imageUris = listOf(imageUri))
        assertEquals("通过视觉工具识图完成", indirectRes.finalContent)
        assertTrue(indirectExecutor.capturedTools.first().any { it.name == "analyzeImage" })
        assertEquals(1, visionExecutor.capturedPrompts.size)
    }

    @Test
    fun modeA_endToEnd_externalInputDuringPlanningQueuedForNextCheckpoint_andChatSummarizesWithMemory() = runBlocking {
        val checkpoint1Gate = CompletableDeferred<Unit>()
        val allowCheckpoint1Finish = CompletableDeferred<Unit>()

        var plannerStep = 0
        val plannerExecutor = SuspendingPromptExecutor { prompt, _ ->
            plannerStep += 1
            if (plannerStep == 1) {
                checkpoint1Gate.complete(Unit)
                allowCheckpoint1Finish.await()
                listOf(
                    Message.Tool.Call(
                        id = "cp1_next",
                        tool = "finish",
                        content = """{"action":"next_checkpoint","summary":"阶段1完成：已分析主要路径","nextCheckpointGoal":"结合用户新补充完成最终执行"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                val systemContent = prompt.messages.filterIsInstance<Message.System>().joinToString("\n") { it.content }
                assertTrue(systemContent.contains("真实人类用户本人"))
                assertTrue(systemContent.contains("[真实用户中途直接发送的新输入]"))
                val userContent = prompt.messages.filterIsInstance<Message.User>().joinToString("\n") { it.content }
                assertTrue(userContent.contains("[真实用户中途直接发送的新输入]"))
                assertTrue(userContent.contains("[用户本人直接输入] 顺便帮我记住我喜欢安静模式"))
                listOf(
                    Message.Tool.Call(
                        id = "cp2_exit",
                        tool = "finish",
                        content = """{"action":"final_exit","summary":"已完成主任务，并已接收补充要求（安静模式）。"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val plannerAgent = PlannerAgent(
            promptExecutor = plannerExecutor,
            model = planningModel,
            directImageInputProvider = { true },
        )
        val lifecycleManager = PlannerLifecycleManager(plannerAgent = plannerAgent)

        var chatStep = 0
        val chatExecutor = SuspendingPromptExecutor { prompt, tools ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            if (lastUser.contains("Analyze the conversation history and identify important facts", ignoreCase = true)) {
                return@SuspendingPromptExecutor listOf(
                    Message.Assistant(
                        content = """
                        [
                          {
                            "subject": "user",
                            "keyword": "user-preference",
                            "description": "User preferences",
                            "value": "Prefers quiet mode"
                          }
                        ]
                        """.trimIndent(),
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }

            chatStep += 1
            if (chatStep == 1) {
                assertTrue(tools.any { it.name == "planAndExecuteTask" })
                listOf(
                    Message.Tool.Call(
                        id = "chat_plan_call",
                        tool = "planAndExecuteTask",
                        content = """{"goal":"帮我规划并执行系统优化"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else if (chatStep == 2) {
                waitForAgentTask(prompt, "waitPlannerTask")
            } else {
                val toolResultMsg = prompt.messages.filterIsInstance<Message.Tool.Result>().lastOrNull()?.content.orEmpty()
                assertTrue(toolResultMsg.contains("已完成主任务，并已接收补充要求（安静模式）"))
                listOf(
                    Message.Assistant(
                        content = "主人，规划与优化已经全部完成，也记住你喜欢安静模式啦！",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val chatAgent = ChatAgent(
            promptExecutor = chatExecutor,
            model = planningModel,
            provider = LlmProvider.OPENAI,
            plannerLifecycleManager = lifecycleManager,
            enableMemory = true,
            autoSaveMemory = true,
        )
        val dispatcher = ChatTaskDispatcher(
            chatAgent = chatAgent,
            availabilityChecker = { ChatModelAvailability.Ready },
        )

        try {
            val session = dispatcher.ensureInitialSession("新对话")

            // 1. 用户发起对话，ChatAgent 调用 planAndExecuteTask，会话进入 Planning 状态
            assertTrue(dispatcher.submitInput(session, UserInput(text = "帮我规划并执行系统优化")))
            withTimeout(5_000L) { checkpoint1Gate.await() }

            assertTrue(session.isPlanning())
            assertTrue(session.sessionState is ChatSessionState.Planning)
            assertTrue(dispatcher.isSessionGenerating(session.id))

            // 2. 规划者思考期间，用户发送外部补充输入 -> 归属规划者，不触发新的 ChatAgent turn，立刻进入 GUI 与队列
            assertTrue(dispatcher.submitInput(session, UserInput(text = "顺便帮我记住我喜欢安静模式")))
            withTimeout(5_000L) {
                while (session.pendingPlannerInputs.isEmpty()) {
                    delay(15L)
                }
            }
            assertEquals(1, session.pendingPlannerInputs.size)
            assertTrue(
                session.items.filterIsInstance<ChatItem.UserMessage>()
                    .any { it.content == "顺便帮我记住我喜欢安静模式" },
            )

            // 3. 放行 Checkpoint 1 -> finish(next_checkpoint) -> Checkpoint 2 排空并消费该外部输入 -> finish(final_exit)
            allowCheckpoint1Finish.complete(Unit)
            withTimeout(5_000L) {
                while (dispatcher.isSessionGenerating(session.id)) {
                    delay(20L)
                }
            }

            // 4. 规划结束，ChatAgent 总结回复并抽取长期记忆，会话回到 Idle
            assertEquals(ChatSessionState.Idle, session.sessionState)
            assertFalse(session.isPlanning())
            assertTrue(session.pendingPlannerInputs.isEmpty())

            val assistantReplies = session.items.filterIsInstance<ChatItem.AssistantMessage>()
            assertTrue(
                assistantReplies.any { it.markdown == "主人，规划与优化已经全部完成，也记住你喜欢安静模式啦！" },
            )

            // 验证长期记忆由 ChatAgent 保存（而不是 PlannerAgent）
            val savedFacts = MaidMemoryManager.loadAllFacts(
                subject = MaidMemorySubjects.User,
                scope = MemoryScope.Agent(ChatAgent.AGENT_NAME),
            )
            assertEquals(1, savedFacts.size)
            assertEquals("Prefers quiet mode", (savedFacts.single() as SingleFact).value)

            // 验证生命周期记忆保留了两个检查点及收到的外部输入历史
            val completedPlan = lifecycleManager.lastCompletedMemory
            assertNotNull(completedPlan)
            assertEquals(1, completedPlan!!.externalInputsHistory.size)
            assertEquals("顺便帮我记住我喜欢安静模式", completedPlan.externalInputsHistory.single().text)
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun checkpointPrompt_explicitlyLabelsMidPlanningInputsAsDirectUserMessages() {
        val memory = PlannerLifecycleMemory(goal = "整理相册")
        val step1Input = UserInput(text = "只整理最近一个月的照片")
        memory.recordExternalInputs(listOf(step1Input))
        val afterStep1 = memory.completeCheckpointAndAdvance(
            summary = "已扫描最近照片列表",
            nextCheckpointGoal = "按用户新条件筛选照片",
            inputsForStep = listOf(step1Input),
        )

        val step2Input = UserInput(text = "顺便把截图也保留下来")
        val prompt = PlannerLifecycleManager.buildCheckpointPrompt(
            memory = afterStep1,
            stepNumber = 2,
            maxCheckpoints = 5,
            newExternalInputs = listOf(step2Input),
        )

        assertTrue(prompt.contains("期间收到的真实用户中途直接输入: 只整理最近一个月的照片"))
        assertTrue(prompt.contains("[真实用户中途直接发送的新输入]"))
        assertTrue(prompt.contains("真实人类用户本人在上一检查点执行/思考期间直接于聊天界面发送的新消息"))
        assertTrue(prompt.contains("[用户本人直接输入] 顺便把截图也保留下来"))
    }

    @Test
    fun buildEffectiveSystemPrompt_tellsPlannerConfiguredModelsAndProhibitsRequestingOwnedCapabilities() {
        val configuredModels = mapOf(
            ModelRole.FAST_CHEAP to RoleModelSelection(
                provider = LlmProvider.OPENAI,
                modelId = "gpt-4o-mini",
            ),
            ModelRole.COMPLEX_PLANNING to RoleModelSelection(
                provider = LlmProvider.ANTHROPIC,
                modelId = "claude-3-7-sonnet",
            ),
            ModelRole.FAST_VISION to RoleModelSelection(
                provider = LlmProvider.GEMINI,
                modelId = "gemini-2.0-flash",
            ),
            ModelRole.COMPLEX_VISION to RoleModelSelection(
                provider = LlmProvider.OPENROUTER,
                modelId = "google/gemini-2.5-pro",
            ),
        )

        val binding = ChatModelBinding(
            executor = SuspendingPromptExecutor { _, _ -> emptyList() },
            model = planningModel,
            provider = LlmProvider.ANTHROPIC,
        )

        val heldPrivileges = setOf(
            AndroidPrivilege.ACCESSIBILITY,
            AndroidPrivilege.FILE_MANAGEMENT,
        )

        val systemPrompt = PlannerAgent.buildEffectiveSystemPrompt(
            baseSystemPrompt = PlannerAgent.DEFAULT_SYSTEM_PROMPT,
            hasDeviceActionTool = true,
            hasVisionTool = true,
            configuredModels = configuredModels,
            currentBinding = binding,
            availablePrivileges = heldPrivileges,
        )

        // 1. 验证模型角色与当前执行模型声明
        assertTrue(systemPrompt.contains("[当前已配置的模型与运行时角色能力说明]"))
        assertTrue(systemPrompt.contains("当前规划者执行模型：gpt-5（服务商：ANTHROPIC）"))
        assertTrue(systemPrompt.contains("快速廉价应答模型 (FAST_CHEAP)：OPENAI -> gpt-4o-mini"))
        assertTrue(systemPrompt.contains("复杂规划模型 (COMPLEX_PLANNING)：ANTHROPIC -> claude-3-7-sonnet"))
        assertTrue(systemPrompt.contains("快速视觉模型 (FAST_VISION)：GEMINI -> gemini-2.0-flash"))
        assertTrue(systemPrompt.contains("复杂视觉模型 (COMPLEX_VISION)：OPENROUTER -> google/gemini-2.5-pro"))

        // 2. 验证严禁申请已拥有能力的指引
        assertTrue(systemPrompt.contains("【特别重要原则：严禁重复申请已拥有的能力】"))
        assertTrue(systemPrompt.contains("凡是已经被标记为【已就绪/无需申请】的工具集，系统与设备执行器已经具备对应执行能力，你绝对不要再次调用 `requestToolsetPermission` 向用户申请！"))

        // 3. 验证已拥有能力被标记为已就绪，而未持有的能力被标记为可按需申请
        assertTrue(systemPrompt.contains("`input`（输入与手势交互工具集）——【已就绪/无需申请】"))
        assertTrue(systemPrompt.contains("`screen`（屏幕观察与元素交互工具集）——【已就绪/无需申请】"))
        assertTrue(systemPrompt.contains("`file_management`（文件管理与存储工具集）——【已就绪/无需申请】"))
        assertTrue(systemPrompt.contains("`shell`（通用 Shell 命令工具集）"))
        assertTrue(systemPrompt.contains("需开启其一：Shizuku / Root (su)"))
    }
}
