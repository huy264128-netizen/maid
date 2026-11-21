package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.AndroidToolSet
import top.aaa000111.maid.androidtool.AndroidToolsetDispatcher
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.settings.ModelSafetyConfig

class DeviceActionAgentTest {

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

    private class RecordingPromptExecutor(
        private val responder: suspend (prompt: Prompt, tools: List<ToolDescriptor>) -> List<Message.Response>,
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
            return responder(prompt, tools)
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

    internal class FakeAndroidUiToolSet : AndroidToolSet() {
        var tappedCount = 0

        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(AndroidPrivilege.SHIZUKU)

        @Tool
        @LLMDescription("Tap screen at coordinates")
        fun tap(
            @LLMDescription("X coordinate") x: Int,
            @LLMDescription("Y coordinate") y: Int,
        ): String {
            tappedCount += 1
            return "tapped($x,$y)"
        }
    }

    internal class FakeAndroidShellToolSet : AndroidToolSet() {
        val executedCommands = mutableListOf<String>()

        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(AndroidPrivilege.SHIZUKU)

        @Tool
        @LLMDescription("Execute shell command")
        fun executeShell(
            @LLMDescription("Shell command") command: String,
        ): String {
            executedCommands += command
            return "ran:$command"
        }
    }

    internal class FakeRootToolSet : AndroidToolSet() {
        var called = false

        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(AndroidPrivilege.ROOT)

        @Tool
        @LLMDescription("Execute root shell command")
        fun executeRootShell(
            @LLMDescription("Root shell command") command: String,
        ): String {
            called = true
            return "root:$command"
        }
    }

    @Test
    fun managersForDifferentAgentsSerializeAccessToTheSameDevice() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val executor = RecordingPromptExecutor { _, _ ->
            if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                secondStarted.complete(Unit)
            }
            listOf(Message.Assistant("done", ResponseMetaInfo.Empty))
        }
        fun createManager() = DeviceActionAgent(
            promptExecutor = executor,
            model = planningModel,
            dispatcher = AndroidToolsetDispatcher(
                toolSets = listOf(FakeAndroidUiToolSet()),
                privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU) },
            ),
            visionToolSet = null,
            directImageInputProvider = { true },
            safetyConfigProvider = { ModelSafetyConfig.BALANCED },
        ).manager
        val firstManager = createManager()
        val secondManager = createManager()
        try {
            val first = firstManager.executeDeviceTask("first device task")
            withTimeout(5_000) { firstStarted.await() }
            val second = secondManager.executeDeviceTask("second device task")
            assertNull(withTimeoutOrNull(150) { secondStarted.await() })
            releaseFirst.complete(Unit)
            assertTrue(withTimeout(5_000) { firstManager.waitTask(first) }.contains("success=true"))
            assertTrue(withTimeout(5_000) { secondManager.waitTask(second) }.contains("success=true"))
            assertTrue(secondStarted.isCompleted)
        } finally {
            releaseFirst.complete(Unit)
        }
    }

    @Test
    fun execute_usesAndroidToolsetDispatcherAndExecutesTools() = runBlocking {
        val uiTools = FakeAndroidUiToolSet()
        val dispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(uiTools),
            privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU) },
        )

        var step = 0
        val executor = RecordingPromptExecutor { prompt, tools ->
            step += 1
            if (step == 1) {
                assertTrue(tools.any { it.name == "tap" })
                assertTrue(tools.any { it.name == "analyzeImage" })
                listOf(
                    Message.Tool.Call(
                        id = "call-1",
                        tool = "tap",
                        content = """{"x":100,"y":200}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                val toolResults = prompt.messages.filterIsInstance<Message.Tool.Result>()
                assertTrue(toolResults.any { it.content.contains("tapped(100,200)") })
                listOf(
                    Message.Assistant(
                        content = "已完成点击操作",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val agent = DeviceActionAgent(
            promptExecutor = executor,
            model = planningModel,
            provider = LlmProvider.OPENAI,
            dispatcher = dispatcher,
            visionToolSet = VisionToolSet(
                visionBindingProvider = { ChatModelBinding(executor, planningModel, LlmProvider.OPENAI) },
            ),
            directImageInputProvider = { false },
            safetyConfigProvider = { ModelSafetyConfig.BALANCED },
        )

        val result = agent.execute(task = "点击坐标 (100, 200)")
        assertTrue(result.success)
        assertEquals("已完成点击操作", result.summary)
        assertEquals(1, uiTools.tappedCount)
        assertEquals(listOf("tap"), result.executedTools)
    }

    @Test
    fun execute_canBeRegisteredAndInvokedAsKoogTool() = runBlocking {
        val uiTools = FakeAndroidUiToolSet()
        val dispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(uiTools),
            privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU) },
        )
        val executor = RecordingPromptExecutor { _, _ ->
            listOf(
                Message.Assistant(
                    content = "已通过子 Agent 完成任务",
                    metaInfo = ResponseMetaInfo.Empty,
                ),
            )
        }

        val deviceAgent = DeviceActionAgent(
            promptExecutor = executor,
            model = planningModel,
            provider = LlmProvider.OPENAI,
            dispatcher = dispatcher,
            directImageInputProvider = { true },
            safetyConfigProvider = { ModelSafetyConfig.BALANCED },
        )

        val registry = deviceAgent.asToolRegistry()
        assertEquals(setOf("executeDeviceTask", "waitDeviceTask"), registry.tools.map { it.name }.toSet())

        val toolOutput = deviceAgent.executeDeviceTask(
            task = "检查当前界面",
            context = "测试模式",
        )
        assertTrue(toolOutput.contains("success=true"))
        assertTrue(toolOutput.contains("已通过子 Agent 完成任务"))
    }

    @Test
    fun execute_skipsVisionToolAndAttachesDirectImages_whenComplexModelsMatch() = runBlocking {
        val uiTools = FakeAndroidUiToolSet()
        val dispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(uiTools),
            privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU) },
        )

        val executor = RecordingPromptExecutor { _, _ ->
            listOf(
                Message.Assistant(
                    content = "已直接识别图片并确认目标",
                    metaInfo = ResponseMetaInfo.Empty,
                ),
            )
        }

        val fakeImage = Attachment.Image(
            content = AttachmentContent.Binary.Bytes(byteArrayOf(1, 2, 3, 4)),
            format = "png",
            mimeType = "image/png",
            fileName = "screen.png",
        )

        val agent = DeviceActionAgent(
            promptExecutor = executor,
            model = planningModel,
            provider = LlmProvider.OPENAI,
            dispatcher = dispatcher,
            visionToolSet = VisionToolSet(
                visionBindingProvider = { ChatModelBinding(executor, planningModel, LlmProvider.OPENAI) },
            ),
            directImageInputProvider = { true },
            imageAttachmentResolver = { fakeImage },
            safetyConfigProvider = { ModelSafetyConfig.BALANCED },
        )

        val result = agent.execute(
            task = "查看截图中的按钮",
            imageUris = listOf("content://media/external/images/media/1"),
        )

        assertTrue(result.success)
        assertFalse(executor.capturedTools.first().any { it.name == "analyzeImage" })
        val userMessage = executor.capturedPrompts.first().messages.last { it is Message.User } as Message.User
        assertEquals(1, userMessage.attachments.size)
        assertTrue(userMessage.attachments.first() is Attachment.Image)
    }

    @Test
    fun execute_enforcesModelSafetyConfig_filteringAndBlockingDangerousShellCommands() = runBlocking {
        val uiTools = FakeAndroidUiToolSet()
        val shellTools = FakeAndroidShellToolSet()
        val rootTools = FakeRootToolSet()
        val dispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(uiTools, shellTools, rootTools),
            privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT) },
        )

        var step = 0
        val executor = RecordingPromptExecutor { prompt, tools ->
            step += 1
            when (step) {
                1 -> {
                    // BALANCED 模式：允许 executeShell，禁用 executeRootShell
                    assertTrue(tools.any { it.name == "executeShell" })
                    assertFalse(tools.any { it.name == "executeRootShell" })
                    listOf(
                        Message.Tool.Call(
                            id = "call-danger",
                            tool = "executeShell",
                            content = """{"command":"pm uninstall com.example.target"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                }
                2 -> {
                    // 安全门应已拦截高危命令并返回拦截提示，随后模型改用安全命令
                    val toolResults = prompt.messages.filterIsInstance<Message.Tool.Result>()
                    assertTrue(toolResults.any { it.content.contains("Blocked by Model Safety Policy") })
                    listOf(
                        Message.Tool.Call(
                            id = "call-safe",
                            tool = "executeShell",
                            content = """{"command":"dumpsys activity top"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                }
                else -> {
                    listOf(
                        Message.Assistant(
                            content = "高危操作已被策略拦截，已改为执行安全查询。",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                }
            }
        }

        val agent = DeviceActionAgent(
            promptExecutor = executor,
            model = planningModel,
            provider = LlmProvider.OPENAI,
            dispatcher = dispatcher,
            directImageInputProvider = { true },
            safetyConfigProvider = { ModelSafetyConfig.BALANCED },
        )

        val result = agent.execute(task = "处理应用并查询前台状态")
        assertTrue(result.success)
        assertFalse(rootTools.called)
        assertEquals(listOf("dumpsys activity top"), shellTools.executedCommands)
        assertEquals(1, result.blockedActions.size)
        assertTrue(result.blockedActions.first().contains("executeShell"))
        assertEquals(listOf("executeShell"), result.executedTools)
    }

    @Test
    fun execute_returnsGuidanceImmediately_whenNoAndroidPrivilegesAreGranted() = runBlocking {
        val uiTools = FakeAndroidUiToolSet()
        val dispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(uiTools),
            privilegeChecker = { emptySet() },
        )
        val executor = RecordingPromptExecutor { _, _ ->
            error("LLM should not be called when no tools/privileges are available")
        }

        val agent = DeviceActionAgent(
            promptExecutor = executor,
            model = planningModel,
            dispatcher = dispatcher,
        )

        val result = agent.execute(task = "打开设置")
        assertFalse(result.success)
        assertTrue(result.summary.contains("权限管理与提权"))
        assertTrue(executor.capturedPrompts.isEmpty())
    }

    @Test
    fun resolveEffectiveSelection_prioritizesComplexRolesAndMatchesSameModels() {
        val openaiComplex = RoleModelSelection(LlmProvider.OPENAI, "gpt-5")
        val openaiFast = RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini")
        val geminiVision = RoleModelSelection(LlmProvider.GEMINI, "gemini-2.5-pro")

        val configsWithComplex = mapOf(
            ModelRole.COMPLEX_PLANNING to openaiComplex,
            ModelRole.FAST_CHEAP to openaiFast,
            ModelRole.COMPLEX_VISION to openaiComplex,
            ModelRole.FAST_VISION to geminiVision,
        )
        assertEquals(
            openaiComplex,
            DeviceActionAgent.resolveEffectiveSelection(
                configs = configsWithComplex,
                preferredRoles = DeviceActionAgent.DEFAULT_MODEL_ROLES,
            ),
        )
        assertEquals(
            openaiComplex,
            DeviceActionAgent.resolveEffectiveSelection(
                configs = configsWithComplex,
                preferredRoles = DeviceActionAgent.DEFAULT_VISION_ROLES,
            ),
        )
        assertTrue(
            ChatAgent.isSameModelSelection(
                configsWithComplex[ModelRole.COMPLEX_PLANNING],
                configsWithComplex[ModelRole.COMPLEX_VISION],
            ),
        )

        val configsFallbackToFast = mapOf(
            ModelRole.FAST_CHEAP to openaiFast,
            ModelRole.FAST_VISION to geminiVision,
        )
        assertEquals(
            openaiFast,
            DeviceActionAgent.resolveEffectiveSelection(
                configs = configsFallbackToFast,
                preferredRoles = DeviceActionAgent.DEFAULT_MODEL_ROLES,
            ),
        )
        assertFalse(
            ChatAgent.isSameModelSelection(
                configsFallbackToFast[ModelRole.COMPLEX_PLANNING],
                configsFallbackToFast[ModelRole.COMPLEX_VISION],
            ),
        )
    }
}
