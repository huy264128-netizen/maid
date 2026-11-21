package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.memory.model.MemoryScope
import ai.koog.agents.memory.model.SingleFact
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.learning.TaskEvidenceRepository
import top.aaa000111.maid.learning.TaskRecorder
import top.aaa000111.maid.learning.ToolExecutionStatus
import top.aaa000111.maid.skill.SkillRepository
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.UserInput
import top.aaa000111.maid.memory.MaidMemoryConcepts
import top.aaa000111.maid.memory.MaidMemoryManager
import top.aaa000111.maid.memory.MaidMemorySubjects
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.RoleModelSelection

class ChatAgentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gpt-4o-mini",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
        ),
        contextLength = 128_000L,
    )

    private class RecordingPromptExecutor(
        private val responder: (prompt: Prompt, tools: List<ToolDescriptor>) -> List<Message.Response>,
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

    internal class EchoToolSet : ToolSet {
        var callCount = 0

        @Tool
        @LLMDescription("Lookup current device battery percentage")
        fun getBatteryLevel(): String {
            callCount += 1
            return "88%"
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
    fun chat_discoversAndReadsSkillsThroughRealToolLoopAndRecordsVersions() = runBlocking {
        val skills = SkillRepository(tempFolder.root.toPath().resolve("skills"))
        val version = skills.install(mapOf(
            "SKILL.md" to "---\nname: example\ndescription: Example verification procedure\n---\nUNIQUE_PROCEDURE_BODY",
            "references/check.md" to "UNIQUE_REFERENCE_BODY",
        ))
        val evidence = TaskEvidenceRepository(tempFolder.root.toPath().resolve("evidence"))
        val recorder = TaskRecorder(evidence, skills)
        var step = 0
        val executor = RecordingPromptExecutor { _, available ->
            assertTrue(available.any { it.name == "listSkills" })
            assertTrue(available.any { it.name == "readSkill" })
            assertFalse(available.any { it.name == "installSkill" })
            step += 1
            val call = when (step) {
                1 -> "listSkills" to """{"query":"example"}"""
                2 -> "readSkill" to """{"skillId":"example","filePath":"SKILL.md"}"""
                3 -> "readSkill" to """{"skillId":"example","filePath":"references/check.md"}"""
                else -> null
            }
            if (call == null) listOf(Message.Assistant("done", ResponseMetaInfo.Empty))
            else listOf(Message.Tool.Call(
                id = "skill-$step", tool = call.first, content = call.second, metaInfo = ResponseMetaInfo.Empty,
            ))
        }
        val agent = ChatAgent(promptExecutor = executor, model = testModel, enableMemory = false)
        val session = ChatSession(title = "skills")
        val result = recorder.run(session.id) { agent.chat("Use example", session = session) }
        assertEquals("done", result)
        val task = evidence.list().single()
        assertEquals(2, task.skills.size)
        assertTrue(task.skills.all { it.versionHash == version.versionHash })
        assertEquals(listOf("listSkills", "readSkill", "readSkill"), task.tools.map { it.toolName })
        assertTrue(task.tools.all { it.status == ToolExecutionStatus.RETURNED })
        assertEquals(testModel.id, task.modelId)
        assertFalse(executor.capturedPrompts.first().messages.any { it.content.contains("UNIQUE_PROCEDURE_BODY") })
        assertTrue(executor.capturedPrompts.last().messages.any { it.content.contains("UNIQUE_REFERENCE_BODY") })
    }

    @Test
    fun chat_loadsPreSavedMemoryFactsIntoPromptViaKoogAgentMemory() = runBlocking {
        val fakeExecutor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            val replyText = if (isMemoryExtractionPrompt(lastUser)) {
                "[]"
            } else {
                "你好！我会用简洁中文回答。"
            }
            listOf(Message.Assistant(content = replyText, metaInfo = ResponseMetaInfo.Empty))
        }

        val agent = ChatAgent(
            promptExecutor = fakeExecutor,
            model = testModel,
            provider = LlmProvider.OPENAI,
        )
        agent.rememberFact(
            concept = MaidMemoryConcepts.userPreference,
            value = "Prefers concise Chinese replies",
            subject = MaidMemorySubjects.User,
        )

        val reply = agent.chatWithDetails("你好，请做个自我介绍")

        assertEquals("你好！我会用简洁中文回答。", reply.content)
        assertEquals(LlmProvider.OPENAI, reply.provider)
        assertEquals("gpt-4o-mini", reply.modelId)
        assertEquals(2, reply.historySize)

        val chatPrompt = fakeExecutor.capturedPrompts.first()
        val combinedPromptText = chatPrompt.messages.joinToString("\n") { it.content }
        assertTrue(combinedPromptText.contains(ChatAgent.DEFAULT_SYSTEM_PROMPT))
        assertTrue(combinedPromptText.contains("Prefers concise Chinese replies"))
        assertTrue(combinedPromptText.contains("你好，请做个自我介绍"))
    }

    @Test
    fun chat_autoExtractsAndPersistsMemoryAcrossNewChatAgentSessions() = runBlocking {
        val firstSessionExecutor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            val content = if (isMemoryExtractionPrompt(lastUser)) {
                """
                ```json
                [
                  {
                    "subject": "user",
                    "keyword": "user-preference",
                    "description": "User preferences",
                    "value": "Likes dark mode"
                  }
                ]
                ```
                """.trimIndent()
            } else {
                "好的，已记住你喜欢深色模式。"
            }
            listOf(Message.Assistant(content = content, metaInfo = ResponseMetaInfo.Empty))
        }

        val firstAgent = ChatAgent(
            promptExecutor = firstSessionExecutor,
            model = testModel,
            provider = LlmProvider.DEEPSEEK,
        )
        val firstReply = firstAgent.chat("以后请记住我喜欢深色模式")
        assertEquals("好的，已记住你喜欢深色模式。", firstReply)

        // 验证记忆抽取阶段收到了完整的本轮用户与助手对话内容
        assertEquals(2, firstSessionExecutor.capturedPrompts.size)
        val extractionPromptText = firstSessionExecutor.capturedPrompts[1].messages
            .joinToString("\n") { it.content }
        assertTrue(extractionPromptText.contains("以后请记住我喜欢深色模式"))
        assertTrue(extractionPromptText.contains("好的，已记住你喜欢深色模式。"))

        // 验证长期记忆已写入 Agent 作用域
        val savedFacts = firstAgent.loadRememberedFacts(subject = MaidMemorySubjects.User)
        assertEquals(1, savedFacts.size)
        assertEquals("Likes dark mode", (savedFacts.single() as SingleFact).value)

        // 模拟重新初始化并开启全新 ChatAgent 实例，验证跨会话记忆自动注入 Prompt
        MaidMemoryManager.resetForTest()
        MaidMemoryManager.init(rootPath = tempFolder.root.toPath())

        val secondSessionExecutor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            val content = if (isMemoryExtractionPrompt(lastUser)) "[]" else "你喜欢深色模式。"
            listOf(Message.Assistant(content = content, metaInfo = ResponseMetaInfo.Empty))
        }

        val secondAgent = ChatAgent(
            promptExecutor = secondSessionExecutor,
            model = testModel,
            provider = LlmProvider.DEEPSEEK,
        )
        val secondReply = secondAgent.chat("我喜欢什么主题？")
        assertEquals("你喜欢深色模式。", secondReply)

        val secondPromptText = secondSessionExecutor.capturedPrompts.first().messages
            .joinToString("\n") { it.content }
        assertTrue(secondPromptText.contains("Likes dark mode"))
        assertTrue(
            MaidMemoryManager.loadAllFacts(
                subject = MaidMemorySubjects.User,
                scope = MemoryScope.Agent(ChatAgent.AGENT_NAME),
            ).isNotEmpty(),
        )
    }

    @Test
    fun chat_handlesNonJsonMemoryExtractionGracefullyWithoutFailingReply() = runBlocking {
        val executor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            val content = if (isMemoryExtractionPrompt(lastUser)) {
                "暂时没有需要长期记忆的新信息。"
            } else {
                "这是正常的对话回复。"
            }
            listOf(Message.Assistant(content = content, metaInfo = ResponseMetaInfo.Empty))
        }

        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
        )

        val reply = agent.chat("今天天气怎么样？")

        assertEquals("这是正常的对话回复。", reply)
        assertTrue(agent.loadRememberedFacts(subject = MaidMemorySubjects.User).isEmpty())
    }

    @Test
    fun chat_preservesMultiTurnHistoryAndSupportsSeedAndClear() = runBlocking {
        val executor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            val reply = when {
                isMemoryExtractionPrompt(lastUser) -> "[]"
                lastUser.contains("第一轮") -> "收到第一轮"
                else -> "收到第二轮"
            }
            listOf(Message.Assistant(content = reply, metaInfo = ResponseMetaInfo.Empty))
        }

        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            autoSaveMemory = false,
        )

        assertEquals("收到第一轮", agent.chat("第一轮问题"))
        assertEquals("收到第二轮", agent.chat("第二轮追问"))

        val secondCallMessages = executor.capturedPrompts[1].messages
        assertTrue(secondCallMessages.any { it is Message.User && it.content == "第一轮问题" })
        assertTrue(secondCallMessages.any { it is Message.Assistant && it.content == "收到第一轮" })
        assertTrue(secondCallMessages.any { it is Message.User && it.content == "第二轮追问" })
        assertEquals(4, agent.historySnapshot().size)

        agent.clearHistory()
        assertTrue(agent.historySnapshot().isEmpty())

        agent.seedHistory(
            listOf(
                Message.User(content = "历史问题", metaInfo = RequestMetaInfo.Empty),
                Message.Assistant(content = "历史回答", metaInfo = ResponseMetaInfo.Empty),
            ),
        )
        assertEquals(2, agent.historySnapshot().size)
    }

    @Test
    fun chat_executesToolCallsWhenToolRegistryIsProvided() = runBlocking {
        val echoTools = EchoToolSet()
        val registry = ToolRegistry {
            tools(echoTools)
        }
        var chatTurnStep = 0
        val executor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            if (isMemoryExtractionPrompt(lastUser)) {
                return@RecordingPromptExecutor listOf(
                    Message.Assistant(content = "[]", metaInfo = ResponseMetaInfo.Empty),
                )
            }
            chatTurnStep += 1
            if (chatTurnStep == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "call_battery_1",
                        tool = "getBatteryLevel",
                        content = "{}",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Assistant(
                        content = "当前设备电量为 88%。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            toolRegistry = registry,
        )

        val reply = agent.chat("帮我看看现在电量多少")

        assertEquals(1, echoTools.callCount)
        assertEquals("当前设备电量为 88%。", reply)
        val postToolPrompt = executor.capturedPrompts[1].messages
        assertTrue(postToolPrompt.any { it is Message.Tool.Result && it.content.contains("88%") })
    }

    @Test
    fun chat_failsFastWhenMemoryUninitializedOrMessageBlank() = runBlocking {
        val executor = RecordingPromptExecutor { _, _ ->
            listOf(Message.Assistant(content = "unused", metaInfo = ResponseMetaInfo.Empty))
        }
        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
        )

        val blankResult = runCatching { agent.chat("   ") }
        assertTrue(blankResult.isFailure)
        assertTrue(blankResult.exceptionOrNull() is IllegalArgumentException)

        MaidMemoryManager.resetForTest()
        val uninitializedResult = runCatching { agent.chat("你好") }
        assertTrue(uninitializedResult.isFailure)
        assertTrue(uninitializedResult.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun chat_isolatesShortTermHistoryAcrossDifferentChatSessions() = runBlocking {
        val executor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            val reply = when {
                isMemoryExtractionPrompt(lastUser) -> "[]"
                lastUser.contains("A-") -> "回复A：$lastUser"
                else -> "回复B：$lastUser"
            }
            listOf(Message.Assistant(content = reply, metaInfo = ResponseMetaInfo.Empty))
        }

        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            autoSaveMemory = false,
        )
        val sessionA = ChatSession(id = "session-a", title = "会话 A")
        val sessionB = ChatSession(id = "session-b", title = "会话 B")

        val replyA1 = agent.chatWithDetails("A-第一句", session = sessionA)
        val replyB1 = agent.chatWithDetails("B-第一句", session = sessionB)
        val replyA2 = agent.chatWithDetails("A-第二句", session = sessionA)

        assertEquals("session-a", replyA1.sessionId)
        assertEquals("session-b", replyB1.sessionId)
        assertEquals("session-a", replyA2.sessionId)
        assertEquals(4, sessionA.historySize)
        assertEquals(2, sessionB.historySize)
        assertTrue(agent.historySnapshot().isEmpty())

        val secondAPromptText = executor.capturedPrompts[2].messages.joinToString("\n") { it.content }
        assertTrue(secondAPromptText.contains("A-第一句"))
        assertTrue(secondAPromptText.contains("A-第二句"))
        assertTrue(!secondAPromptText.contains("B-第一句"))
    }

    @Test
    fun chat_exposesSessionOutputToolForMidTurnGuiOutput() = runBlocking {
        var step = 0
        val executor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            if (isMemoryExtractionPrompt(lastUser)) {
                return@RecordingPromptExecutor listOf(
                    Message.Assistant(content = "[]", metaInfo = ResponseMetaInfo.Empty),
                )
            }
            step += 1
            if (step == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "call_output_mid",
                        tool = "output",
                        content = """{"markdown":"**中途进展**：已读取系统信息"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Assistant(
                        content = "最终完成回复。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            provider = LlmProvider.OPENAI,
            autoSaveMemory = false,
        )
        val session = ChatSession(id = "session-mid-output")

        val reply = agent.chatWithDetails("开始检查", session = session)

        assertEquals("最终完成回复。", reply.content)
        assertTrue(executor.capturedTools.first().any { it.name == "output" })
        val guiOutputs = session.items.filterIsInstance<top.aaa000111.maid.chat.ChatItem.AssistantMessage>()
        assertEquals(1, guiOutputs.size)
        assertEquals("**中途进展**：已读取系统信息", guiOutputs.single().markdown)
        assertEquals(LlmProvider.OPENAI, guiOutputs.single().provider)
        assertEquals("gpt-4o-mini", guiOutputs.single().modelId)
    }

    @Test
    fun chat_injectsConfiguredPersonaIntoSystemPrompt() = runBlocking {
        val executor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            val reply = if (isMemoryExtractionPrompt(lastUser)) "[]" else "遵命，主人！"
            listOf(Message.Assistant(content = reply, metaInfo = ResponseMetaInfo.Empty))
        }

        val personaText = "你是一位温柔礼貌且略带俏皮的女仆助手，性格细心体贴。"
        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            personaProvider = { personaText },
            enableMemory = false,
            autoSaveMemory = false,
        )

        val reply = agent.chat("帮我整理日程")
        assertEquals("遵命，主人！", reply)

        val systemMessage = executor.capturedPrompts.first().messages
            .first { it is Message.System }
            .content
        assertTrue(systemMessage.contains(ChatAgent.DEFAULT_SYSTEM_PROMPT))
        assertTrue(systemMessage.contains("Persona（语气与性格设定）"))
        assertTrue(systemMessage.contains(personaText))
    }

    @Test
    fun chat_usesVisionToolWhenFastChatDiffersFromFastVision() = runBlocking {
        val imageFile = tempFolder.newFile("ui_state.png")
        imageFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val imageUri = imageFile.toURI().toString()

        val visionExecutor = RecordingPromptExecutor { _, _ ->
            listOf(Message.Assistant(content = "画面顶部显示“设置”标题。", metaInfo = ResponseMetaInfo.Empty))
        }
        val visionTool = VisionToolSet(
            visionBindingProvider = {
                ChatModelBinding(
                    executor = visionExecutor,
                    model = testModel,
                    provider = LlmProvider.GEMINI,
                )
            },
        )

        var chatStep = 0
        val chatExecutor = RecordingPromptExecutor { _, _ ->
            chatStep += 1
            if (chatStep == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "call_vision_1",
                        tool = "analyzeImage",
                        content = """{"imageUri":"$imageUri","question":"顶部标题是什么？"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Assistant(
                        content = "通过视觉模型确认，画面顶部标题为“设置”。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val agent = ChatAgent(
            promptExecutor = chatExecutor,
            model = testModel,
            provider = LlmProvider.DEEPSEEK,
            visionToolSet = visionTool,
            directImageInputProvider = { false },
            enableMemory = false,
            autoSaveMemory = false,
        )

        val reply = agent.chat(
            UserInput(
                text = "帮我看看截图和日志",
                fileUris = listOf("content://docs/app.log"),
                imageUris = listOf(imageUri),
            ),
        )

        assertEquals("通过视觉模型确认，画面顶部标题为“设置”。", reply)
        assertTrue(chatExecutor.capturedTools.first().any { it.name == "analyzeImage" })

        val chatUserMessage = chatExecutor.capturedPrompts.first().messages
            .filterIsInstance<Message.User>()
            .single()
        assertTrue(chatUserMessage.attachments.isEmpty())
        assertTrue(chatUserMessage.content.contains(imageUri))
        assertTrue(chatUserMessage.content.contains("来自用户的图片"))
        assertTrue(chatUserMessage.content.contains("content://docs/app.log"))

        assertEquals(1, visionExecutor.capturedPrompts.size)
        val visionUserMessage = visionExecutor.capturedPrompts.single().messages
            .filterIsInstance<Message.User>()
            .single()
        assertTrue(visionUserMessage.attachments.single() is Attachment.Image)
    }

    @Test
    fun chat_acceptsDirectImageInputAndSkipsVisionToolWhenSameAsFastVision() = runBlocking {
        val imageFile = tempFolder.newFile("direct_photo.jpg")
        imageFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        val imageUri = imageFile.toURI().toString()

        val chatExecutor = RecordingPromptExecutor { _, _ ->
            listOf(
                Message.Assistant(
                    content = "我已直接识别该图片。",
                    metaInfo = ResponseMetaInfo.Empty,
                ),
            )
        }

        val agent = ChatAgent(
            promptExecutor = chatExecutor,
            model = testModel,
            provider = LlmProvider.OPENAI,
            directImageInputProvider = { true },
            enableMemory = false,
            autoSaveMemory = false,
        )

        val reply = agent.chat(
            UserInput(
                text = "直接看这张图",
                fileUris = listOf("content://docs/note.txt"),
                imageUris = listOf(imageUri),
            ),
        )

        assertEquals("我已直接识别该图片。", reply)
        assertFalse(chatExecutor.capturedTools.first().any { it.name == "analyzeImage" })

        val chatUserMessage = chatExecutor.capturedPrompts.single().messages
            .filterIsInstance<Message.User>()
            .single()
        assertTrue(chatUserMessage.content.contains("直接看这张图"))
        assertTrue(chatUserMessage.content.contains("content://docs/note.txt"))
        // 即使直接向多模态模型注入图片附件，提示词中也必须包含本次发送的图片 URI 并明确告知这是来自用户的图片
        assertTrue(chatUserMessage.content.contains(imageUri))
        assertTrue(chatUserMessage.content.contains("来自用户的图片"))
        val directAttachment = chatUserMessage.attachments.single() as Attachment.Image
        assertEquals("image/jpeg", directAttachment.mimeType)
        assertTrue(directAttachment.content is AttachmentContent.Binary.Bytes)
    }

    @Test
    fun isSameModelSelection_matchesOnlyCompleteIdenticalProviderAndModelId() {
        assertTrue(
            ChatAgent.isSameModelSelection(
                RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini"),
                RoleModelSelection(LlmProvider.OPENAI, " gpt-4o-mini "),
            ),
        )
        assertFalse(
            ChatAgent.isSameModelSelection(
                RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini"),
                RoleModelSelection(LlmProvider.OPENAI, "gpt-4o"),
            ),
        )
        assertFalse(
            ChatAgent.isSameModelSelection(
                RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini"),
                RoleModelSelection(LlmProvider.OPENROUTER, "gpt-4o-mini"),
            ),
        )
        assertFalse(
            ChatAgent.isSameModelSelection(
                RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini"),
                RoleModelSelection(LlmProvider.OPENAI, null),
            ),
        )
    }

    @Test
    fun chat_crossChatMemoryToggleAndInformationSourceDeclaration_workAsExpected() = runBlocking {
        var crossChatMemoryEnabled = true
        val executor = RecordingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            val reply = if (isMemoryExtractionPrompt(lastUser)) "[]" else "收到。"
            listOf(Message.Assistant(content = reply, metaInfo = ResponseMetaInfo.Empty))
        }

        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            provider = LlmProvider.OPENAI,
            crossChatMemoryEnabledProvider = { crossChatMemoryEnabled },
            autoSaveMemory = false,
        )
        agent.rememberFact(
            concept = MaidMemoryConcepts.userPreference,
            value = "Always show file extensions",
            subject = MaidMemorySubjects.User,
        )

        // 1. 开启跨聊天记忆时：注入跨会话记忆并显式声明信息来源
        val session1 = ChatSession(id = "s-mem-on")
        agent.chat("查看设置", session = session1)
        val promptWhenEnabled = executor.capturedPrompts.last().messages.joinToString("\n") { it.content }
        assertTrue(promptWhenEnabled.contains("[信息来源声明与优先级说明]"))
        assertTrue(promptWhenEnabled.contains("跨聊天长期记忆（当前已开启）"))
        assertTrue(promptWhenEnabled.contains(ChatAgent.CROSS_CHAT_MEMORY_SOURCE_HEADER))
        assertTrue(promptWhenEnabled.contains("Always show file extensions"))

        // 2. 关闭跨聊天记忆时：不再加载跨会话长期记忆，并在系统提示词中声明已关闭
        crossChatMemoryEnabled = false
        executor.capturedPrompts.clear()
        val session2 = ChatSession(id = "s-mem-off")
        agent.chat("查看设置", session = session2)
        val promptWhenDisabled = executor.capturedPrompts.single().messages.joinToString("\n") { it.content }
        assertTrue(promptWhenDisabled.contains("跨聊天长期记忆（当前已关闭）"))
        assertFalse(promptWhenDisabled.contains(ChatAgent.CROSS_CHAT_MEMORY_SOURCE_HEADER))
        assertFalse(promptWhenDisabled.contains("Always show file extensions"))
    }

    @Test
    fun chat_stashesInputUrisIntoSessionSharedPoolAndExposesPoolAcrossTurns() = runBlocking {
        val executor = RecordingPromptExecutor { _, _ ->
            listOf(Message.Assistant(content = "已处理。", metaInfo = ResponseMetaInfo.Empty))
        }
        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            provider = LlmProvider.OPENAI,
            directImageInputProvider = { false },
            enableMemory = false,
            autoSaveMemory = false,
        )
        val session = ChatSession(id = "s-uri-pool")

        // 第 1 轮输入文件 URI 与图片 URI
        agent.chat(
            userInput = UserInput(
                text = "第一轮上传",
                fileUris = listOf("content://docs/report_v1.pdf"),
                imageUris = listOf("content://media/diagram_1.png"),
            ),
            session = session,
        )

        assertEquals(listOf("content://docs/report_v1.pdf"), session.fileUriPool)
        assertEquals(listOf("content://media/diagram_1.png"), session.imageUriPool)
        assertEquals(
            listOf("content://docs/report_v1.pdf", "content://media/diagram_1.png"),
            session.uriPool,
        )

        // 第 2 轮仅发送纯文本 + 新文件，验证会话公用 URI 池同时保留第 1 轮与第 2 轮的所有 URI 并暴露给模型
        agent.chat(
            userInput = UserInput(
                text = "对比上一份报告与新报告",
                fileUris = listOf("content://docs/report_v2.pdf"),
            ),
            session = session,
        )

        assertEquals(
            listOf("content://docs/report_v1.pdf", "content://docs/report_v2.pdf"),
            session.fileUriPool,
        )
        assertEquals(
            listOf(
                "content://docs/report_v1.pdf",
                "content://docs/report_v2.pdf",
                "content://media/diagram_1.png",
            ),
            session.uriPool,
        )

        val secondTurnUserMsg = executor.capturedPrompts.last().messages
            .filterIsInstance<Message.User>()
            .last()
            .content
        assertTrue(secondTurnUserMsg.contains("[当前会话公用 URI 池（信息来源：当前会话历次输入累计暂存的文件与图片 URI）]"))
        assertTrue(secondTurnUserMsg.contains("- [文件 URI] content://docs/report_v1.pdf"))
        assertTrue(secondTurnUserMsg.contains("- [文件 URI] content://docs/report_v2.pdf"))
        assertTrue(secondTurnUserMsg.contains("- [图片 URI] content://media/diagram_1.png"))
    }

    private fun isMemoryExtractionPrompt(content: String): Boolean {
        return content.contains("Analyze the conversation history and identify important facts", ignoreCase = true)
    }
}
