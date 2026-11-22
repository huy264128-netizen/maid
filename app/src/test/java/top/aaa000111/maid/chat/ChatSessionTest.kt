package top.aaa000111.maid.chat

import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.settings.ModelOutputDisplayMode
import java.util.concurrent.atomic.AtomicInteger

class ChatSessionTest {

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gpt-4o-mini",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
        ),
        contextLength = 128_000L,
    )

    private class FakePromptExecutor(
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

    @Test
    fun itemOperations_appendUpdateClearAndExposeReactiveSnapshot() {
        val session = ChatSession(
            id = "session-1",
            createdAtMillis = 1_000L,
        )

        assertEquals("session-1", session.id)
        assertEquals(ChatSession.DEFAULT_TITLE, session.title)
        assertEquals(0, session.itemCount)
        assertEquals(0, session.historySize)

        val user = session.appendUserItem("你好")
        val tool = session.appendToolStatus(
            title = "屏幕识别",
            detail = "准备读取界面",
            isCompleted = false,
        )
        val assistant = session.appendAssistantItem(
            markdown = "你好，我在。",
            provider = LlmProvider.OPENAI,
            modelId = "gpt-4o-mini",
        )
        val notice = session.appendSystemNotice("连接正常", isError = false)

        assertEquals(4, session.itemCount)
        assertEquals(4, session.items.size)
        assertEquals(listOf(user, tool, assistant, notice), session.state.value.items)
        assertTrue(session.updatedAtMillis >= 1_000L)

        session.updateItem(tool.id) { existing ->
            if (existing is ChatItem.ToolStatusItem) {
                existing.copy(detail = "已完成界面读取", isCompleted = true)
            } else {
                existing
            }
        }

        val updatedTool = session.items[1] as ChatItem.ToolStatusItem
        assertEquals("已完成界面读取", updatedTool.detail)
        assertTrue(updatedTool.isCompleted)

        session.clearItems()
        assertEquals(0, session.itemCount)
        assertTrue(session.state.value.items.isEmpty())
    }

    @Test
    fun titleOperations_autoTitleFromDefaultAndRespectCustomTitle() {
        val session = ChatSession(title = "新对话 1")

        assertFalse(session.updateTitleIfDefault("   "))
        assertTrue(
            session.updateTitleIfDefault(
                prompt = "第一行问题\n第二行补充非常长的一段话用来测试截断效果",
            ),
        )
        assertEquals("第一行问题 第二行补充非常长的一段话…", session.title)

        assertFalse(session.updateTitleIfDefault("另一个问题"))
        assertEquals("第一行问题 第二行补充非常长的一段话…", session.title)

        session.rename("  自定义会话标题  ")
        assertEquals("自定义会话标题", session.title)

        session.rename("   ")
        assertEquals("自定义会话标题", session.title)

        session.touch(99_999L)
        assertEquals(99_999L, session.updatedAtMillis)
    }

    @Test
    fun generatedTitleDoesNotOverwriteExplicitRename() {
        val session = ChatSession()
        assertTrue(session.canGenerateTitle())
        session.updateTitleIfDefault("日志排查请求")

        session.rename("新对话 2")
        session.applyGeneratedTitle("日志分析")

        assertFalse(session.updateTitleIfDefault("另一个问题"))
        assertEquals("新对话 2", session.title)
    }

    @Test
    fun historyAndPrompt_seedAppendRecordBuildPromptAndClear() {
        val session = ChatSession()

        session.seedHistory(
            listOf(
                Message.User(content = "历史问题", metaInfo = RequestMetaInfo.Empty),
                Message.Assistant(content = "历史回答", metaInfo = ResponseMetaInfo.Empty),
            ),
        )
        assertEquals(2, session.historySize)

        session.appendHistory(
            listOf(Message.User(content = "补充上下文", metaInfo = RequestMetaInfo.Empty)),
        )
        assertEquals(3, session.historySize)

        session.recordExchange(
            userMessage = "新问题",
            assistantReply = "新回复",
        )
        assertEquals(5, session.historySnapshot().size)
        assertEquals(5, session.state.value.historySize)

        val prompt = session.buildPrompt(
            systemPrompt = "你是 Maid",
            temperature = 0.25,
            promptId = "custom_prompt",
        )
        assertEquals("custom_prompt", prompt.id)
        assertEquals(0.25, prompt.params.temperature)
        assertEquals(6, prompt.messages.size)
        assertTrue(prompt.messages.first() is Message.System)
        assertEquals("你是 Maid", prompt.messages.first().content)
        assertEquals("新回复", prompt.messages.last().content)

        session.clearHistory()
        assertEquals(0, session.historySize)

        session.appendUserItem("UI 消息")
        session.recordExchange("问题", "回答")
        assertEquals(1, session.itemCount)
        assertEquals(2, session.historySize)

        session.clear()
        assertEquals(0, session.itemCount)
        assertEquals(0, session.historySize)
    }

    @Test
    fun withTurnLock_serializesConcurrentTurnsInSameSession() = runBlocking {
        val session = ChatSession()
        val activeTurns = AtomicInteger(0)
        val maxConcurrentTurns = AtomicInteger(0)

        (1..4).map { index ->
            async {
                session.withTurnLock {
                    val running = activeTurns.incrementAndGet()
                    maxConcurrentTurns.accumulateAndGet(running, ::maxOf)
                    delay(20)
                    session.recordExchange("user-$index", "assistant-$index")
                    activeTurns.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maxConcurrentTurns.get())
        assertEquals(8, session.historySize)
    }

    @Test
    fun questionAndInput_answersPendingQuestionsInFifoOrderBeforeCallingChatModel() = runBlocking {
        val executor = FakePromptExecutor { _, _ ->
            listOf(Message.Assistant(content = "模型回复", metaInfo = ResponseMetaInfo.Empty))
        }
        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            provider = LlmProvider.OPENAI,
            enableMemory = false,
            autoSaveMemory = false,
        )
        val session = ChatSession(chatAgent = agent)

        val answers = mutableListOf<String>()
        session.askQuestion(Question("是否允许**覆盖**目标文件？") { answers += "Q1:$it" })
        session.askQuestion(Question("请输入新的文件名：") { answers += "Q2:$it" })

        assertTrue(session.hasPendingQuestion())
        assertEquals(2, session.pendingQuestions.size)
        assertEquals("是否允许**覆盖**目标文件？", session.pendingQuestion?.content)
        // 仅展示队首待回答问题，避免多条提问刷屏
        assertEquals(1, session.itemCount)
        assertTrue(session.items.single() is ChatItem.AssistantMessage)

        val r1 = session.input("允许")
        assertTrue(r1 is ChatInputResult.QuestionAnswered)
        assertEquals(listOf("Q1:允许"), answers)
        assertEquals(0, executor.capturedPrompts.size)
        // 回答第 1 个后自动把第 2 个待回答问题输出到 Markdown GUI
        assertEquals("请输入新的文件名：", session.pendingQuestion?.content)
        assertEquals(3, session.itemCount)

        val r2 = session.input("report.md")
        assertTrue(r2 is ChatInputResult.QuestionAnswered)
        assertEquals(listOf("Q1:允许", "Q2:report.md"), answers)
        assertFalse(session.hasPendingQuestion())
        assertNull(session.pendingQuestion)
        assertEquals(0, executor.capturedPrompts.size)

        // 队列清空后再输入，转发给 ChatAgent 并输出 Markdown 回复到 GUI
        val r3 = session.input("现在开始整理")
        assertTrue(r3 is ChatInputResult.ModelReplied)
        assertEquals(1, executor.capturedPrompts.size)
        val lastItem = session.items.last() as ChatItem.AssistantMessage
        assertEquals("模型回复", lastItem.markdown)
        assertEquals(LlmProvider.OPENAI, lastItem.provider)
        assertEquals("gpt-4o-mini", lastItem.modelId)

        session.askQuestion(Question("待清空问题") {})
        session.clearPendingQuestions()
        assertFalse(session.hasPendingQuestion())
    }

    @Test
    fun askQuestionAndWait_suspendsUntilSessionReceivesInput() = runBlocking {
        val session = ChatSession()
        val deferredAnswer = async {
            session.askQuestionAndWait("请确认验证码")
        }

        delay(20)
        assertTrue(session.hasPendingQuestion())
        session.input("884210")

        assertEquals("884210", deferredAnswer.await())
        assertFalse(session.hasPendingQuestion())
    }

    @Test
    fun toolSet_registersOnlyOutputToolAndStreamsMidTurnMarkdownToGui() = runBlocking {
        val session = ChatSession()
        val registry = session.asToolRegistry()
        assertEquals(listOf("output", "sendFileCard"), registry.tools.map { it.name })

        var step = 0
        val executor = FakePromptExecutor { _, tools ->
            assertTrue(tools.any { it.name == "output" })
            step += 1
            if (step == 1) {
                listOf(
                    Message.Tool.Call(
                        id = "call_output_1",
                        tool = "output",
                        content = """{"markdown":"### 阶段 1\n正在整理上下文"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Assistant(
                        content = "### 总结\n已完成全部处理。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }
        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            provider = LlmProvider.DEEPSEEK,
            enableMemory = false,
            autoSaveMemory = false,
        )

        val result = session.input("帮我分阶段处理任务", chatAgent = agent)
        assertTrue(result is ChatInputResult.ModelReplied)
        assertEquals(2, executor.capturedPrompts.size)

        val assistantMessages = session.items.filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(2, assistantMessages.size)
        assertEquals("### 阶段 1\n正在整理上下文", assistantMessages[0].markdown)
        assertEquals(LlmProvider.DEEPSEEK, assistantMessages[0].provider)
        assertEquals("### 总结\n已完成全部处理。", assistantMessages[1].markdown)
        assertEquals(LlmProvider.DEEPSEEK, assistantMessages[1].provider)
    }

    @Test
    fun questionAndInput_supportsFileUrisAndImageUris() = runBlocking {
        val session = ChatSession()
        val capturedAnswers = mutableListOf<UserInput>()

        session.askQuestion(
            Question(
                content = "请核对附带的日志与截图，并上传修正后的文件。",
                fileUris = listOf("content://docs/error.log"),
                imageUris = listOf("content://media/screenshot.png"),
                onInputAnswer = { input -> capturedAnswers += input },
            ),
        )

        val questionItem = session.items.single() as ChatItem.AssistantMessage
        assertEquals(listOf("content://docs/error.log"), questionItem.fileUris)
        assertEquals(listOf("content://media/screenshot.png"), questionItem.imageUris)

        val answerResult = session.input(
            UserInput(
                text = "已上传新文件与参考截图",
                fileUris = listOf(" content://docs/fixed.json ", "content://docs/fixed.json"),
                imageUris = listOf("content://media/confirm.jpg"),
            ),
        )
        assertTrue(answerResult is ChatInputResult.QuestionAnswered)
        val answered = answerResult as ChatInputResult.QuestionAnswered
        assertEquals("已上传新文件与参考截图", answered.answer)
        assertEquals(listOf("content://docs/fixed.json"), answered.input.fileUris)
        assertEquals(listOf("content://media/confirm.jpg"), answered.input.imageUris)
        assertEquals(1, capturedAnswers.size)
        assertEquals(listOf("content://docs/fixed.json"), capturedAnswers.single().fileUris)
        assertEquals(listOf("content://media/confirm.jpg"), capturedAnswers.single().imageUris)

        val userItem = session.items[1] as ChatItem.UserMessage
        assertEquals(listOf("content://docs/fixed.json"), userItem.fileUris)
        assertEquals(listOf("content://media/confirm.jpg"), userItem.imageUris)

        val deferredAnswer = async {
            session.askQuestionAndWaitInput("请直接上传一张截图")
        }
        delay(20)
        session.input(
            UserInput(
                text = "",
                imageUris = listOf("content://media/only_image.png"),
            ),
        )
        val received = deferredAnswer.await()
        assertEquals("", received.text)
        assertEquals(listOf("content://media/only_image.png"), received.imageUris)
    }

    @Test
    fun stateMachine_transitionsBetweenIdleChattingPlanningWaitingAnswerAndQueuesExternalInputs() = runBlocking {
        val session = ChatSession()
        assertEquals(ChatSessionState.Idle, session.sessionState)
        assertFalse(session.isPlanning())

        session.withTurnLock {
            assertEquals(ChatSessionState.Chatting, session.sessionState)

            session.enterPlanning(
                checkpointIndex = 1,
                totalCheckpoints = 3,
                currentCheckpointTitle = "分析环境",
            )
            assertTrue(session.isPlanning())
            assertEquals(
                ChatSessionState.Planning(
                    checkpointIndex = 1,
                    totalCheckpoints = 3,
                    currentCheckpointTitle = "分析环境",
                    queuedExternalInputCount = 0,
                ),
                session.sessionState,
            )

            // 规划期间发送外部输入：直接进入 GUI 并暂存，不阻塞在 turnMutex 上
            val queuedResult = session.input("补充条件：仅查看设置页")
            assertTrue(queuedResult is ChatInputResult.QueuedForPlanner)
            val queued = queuedResult as ChatInputResult.QueuedForPlanner
            assertEquals(1, queued.checkpointIndex)
            assertEquals(1, queued.queuedCount)
            assertEquals(1, session.pendingPlannerInputs.size)
            assertEquals(
                1,
                (session.sessionState as ChatSessionState.Planning).queuedExternalInputCount,
            )

            // 规划期间弹出 Question 时优先进入 WaitingAnswer，回答后恢复 Planning
            val deferredAnswer = async { session.askQuestionAndWait("是否继续高风险检查？") }
            delay(20)
            assertTrue(session.sessionState is ChatSessionState.WaitingAnswer)
            assertTrue(session.isPlanning())
            val answerResult = session.input("允许")
            assertTrue(answerResult is ChatInputResult.QuestionAnswered)
            assertEquals("允许", deferredAnswer.await())
            assertTrue(session.sessionState is ChatSessionState.Planning)

            // 下个检查点排空外部输入并推进进度
            val drained = session.drainPendingPlannerInputs()
            assertEquals(listOf("补充条件：仅查看设置页"), drained.map { it.text })
            assertTrue(session.pendingPlannerInputs.isEmpty())
            session.updatePlanningCheckpoint(
                checkpointIndex = 2,
                totalCheckpoints = 3,
                currentCheckpointTitle = "执行验证",
            )
            assertEquals(2, (session.sessionState as ChatSessionState.Planning).checkpointIndex)

            session.exitPlanning()
            assertFalse(session.isPlanning())
            assertEquals(ChatSessionState.Chatting, session.sessionState)
        }

        assertEquals(ChatSessionState.Idle, session.sessionState)
    }

    @Test
    fun uriPool_stashesAllInputFileAndImageUrisAcrossTurnsQuestionsAndPlanning() = runBlocking {
        val session = ChatSession()
        assertTrue(session.uriPool.isEmpty())

        // 1. 提问回答期间输入的文件与图片 URI 自动暂存进会话公用 URI 池
        session.askQuestion(Question("请提供配置文件") {})
        session.input(
            UserInput(
                text = "这是配置",
                fileUris = listOf(" content://docs/config.yaml ", "content://docs/config.yaml"),
                imageUris = listOf("content://media/preview.png"),
            ),
        )
        assertEquals(listOf("content://docs/config.yaml"), session.fileUriPool)
        assertEquals(listOf("content://media/preview.png"), session.imageUriPool)

        // 2. 规划期间中途输入的文件 URI 也自动暂存进会话公用 URI 池
        session.enterPlanning(checkpointIndex = 1, totalCheckpoints = 2, currentCheckpointTitle = "检查")
        session.input(
            UserInput(
                text = "补充日志",
                fileUris = listOf("content://docs/trace.log"),
            ),
        )
        session.exitPlanning()
        assertEquals(
            listOf("content://docs/config.yaml", "content://docs/trace.log"),
            session.fileUriPool,
        )

        // 3. 手动暂存与去重验证
        session.stashFileUris(listOf("content://docs/trace.log", "file:///sdcard/Download/patch.diff"))
        assertEquals(
            listOf(
                "content://docs/config.yaml",
                "content://docs/trace.log",
                "file:///sdcard/Download/patch.diff",
            ),
            session.fileUriPool,
        )
        assertEquals(
            listOf(
                "content://docs/config.yaml",
                "content://docs/trace.log",
                "file:///sdcard/Download/patch.diff",
                "content://media/preview.png",
            ),
            session.uriPool,
        )

        // 4. clearUriPool 与 clear 清理验证
        session.clearUriPool()
        assertTrue(session.uriPool.isEmpty())
    }

    @Test
    fun attachments_haveIdDescriptionAndUri_andCopyToInternalSessionDirOnFirstAccess() {
        val tempRoot = java.nio.file.Files.createTempDirectory("maid_session_att_test").toFile()
        val internalRoot = java.io.File(tempRoot, "internal_files").apply { mkdirs() }
        val externalDir = java.io.File(tempRoot, "external_files").apply { mkdirs() }
        SessionAttachmentStorage.setCustomInternalRootDirForTest(internalRoot)
        try {
            val externalFile = java.io.File(externalDir, "report.txt").apply {
                writeText("hello from external file", Charsets.UTF_8)
            }
            val externalImage = java.io.File(externalDir, "photo.png").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            }

            val session = ChatSession(id = "session_101")
            val userItem = session.appendUserItem(
                content = "请分析文件和图片",
                fileUris = listOf(externalFile.toURI().toString()),
                imageUris = listOf(externalImage.toURI().toString()),
                fileDescriptions = mapOf(externalFile.toURI().toString() to "用户上传的报告文档"),
                imageDescriptions = mapOf(externalImage.toURI().toString() to "来自用户的图片：测试照片"),
            )

            assertEquals(listOf("file_1", "img_1"), userItem.attachmentIds)
            assertEquals(2, session.attachments.size)

            val fileAttachment = session.findAttachment("file_1")!!
            assertEquals("file_1", fileAttachment.id)
            assertEquals("用户上传的报告文档", fileAttachment.description)
            assertEquals(externalFile.toURI().toString(), fileAttachment.uri)
            assertFalse(fileAttachment.isImage)

            val imgAttachment = session.findAttachment("img_1")!!
            assertEquals("img_1", imgAttachment.id)
            assertEquals("来自用户的图片：测试照片", imgAttachment.description)
            assertEquals(externalImage.toURI().toString(), imgAttachment.uri)
            assertTrue(imgAttachment.isImage)

            // 第一次通过 ID 访问文件时：自动复制到 <internalRoot>/<session_id>/<file_id> 并更新 uri
            val expectedInternalFile = java.io.File(java.io.File(internalRoot, "session_101"), "file_1")
            assertFalse(expectedInternalFile.exists())

            val accessedFile = session.accessAttachment("file_1")!!
            assertTrue(expectedInternalFile.exists())
            assertEquals("hello from external file", expectedInternalFile.readText(Charsets.UTF_8))
            assertEquals(expectedInternalFile.toURI().toString(), accessedFile.uri)
            assertEquals(externalFile.toURI().toString(), accessedFile.originalUri)
            assertEquals(expectedInternalFile.toURI().toString(), session.findAttachment("file_1")!!.uri)

            // 第一次通过 ID 访问图片时：同样复制到 <internalRoot>/<session_id>/<img_1>
            val expectedInternalImg = java.io.File(java.io.File(internalRoot, "session_101"), "img_1")
            val resolvedImgUri = session.accessAttachmentUri("img_1")
            assertTrue(expectedInternalImg.exists())
            assertEquals(expectedInternalImg.toURI().toString(), resolvedImgUri)
        } finally {
            SessionAttachmentStorage.setCustomInternalRootDirForTest(null)
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun filterChatItemsForDisplay_respectsShowAllLastOnlyAndHideModesPerTurn() {
        val u1 = ChatItem.UserMessage(id = "u1", content = "第一轮任务")
        val t1a = ChatItem.ToolStatusItem(id = "t1a", title = "工具1", detail = "d1")
        val seg1 = ChatItem.AssistantMessage(id = "seg1", markdown = "分段 output 消息")
        val perm1 = ChatItem.PermissionRequestItem(
            id = "perm1",
            toolsetId = "screen",
            toolsetTitle = "屏幕工具集",
            requiredPrivileges = listOf(AndroidPrivilege.SHIZUKU),
        )
        val t1b = ChatItem.ToolStatusItem(id = "t1b", title = "工具2", detail = "d2")
        val q1 = ChatItem.AssistantMessage(
            id = "q1",
            markdown = "是否继续？",
            senderLabel = ChatSession.DEFAULT_QUESTION_SENDER_LABEL,
        )
        val final1 = ChatItem.AssistantMessage(id = "final1", markdown = "第一轮最终回复")
        val notice1 = ChatItem.SystemNoticeItem(id = "n1", message = "系统提示")

        val u2 = ChatItem.UserMessage(id = "u2", content = "第二轮任务")
        val t2a = ChatItem.ToolStatusItem(id = "t2a", title = "工具3", detail = "d3")
        val t2b = ChatItem.ToolStatusItem(id = "t2b", title = "工具4", detail = "d4")
        val seg2 = ChatItem.AssistantMessage(id = "seg2", markdown = "第二轮分段 output")
        val final2 = ChatItem.AssistantMessage(id = "final2", markdown = "第二轮最终回复")

        val allItems = listOf(u1, t1a, seg1, perm1, t1b, q1, final1, notice1, u2, t2a, t2b, seg2, final2)

        // 1. SHOW_ALL：完整展示全部消息项
        assertEquals(
            allItems.map { it.id },
            filterChatItemsForDisplay(allItems, ModelOutputDisplayMode.SHOW_ALL).map { it.id },
        )

        // 2. LAST_ONLY：每轮仅保留最后一条 ToolStatusItem（第 1 轮为 t1b，第 2 轮为 t2b），分段 output 与最终回复均完整保留
        assertEquals(
            listOf("u1", "seg1", "perm1", "t1b", "q1", "final1", "n1", "u2", "t2b", "seg2", "final2"),
            filterChatItemsForDisplay(allItems, ModelOutputDisplayMode.LAST_ONLY).map { it.id },
        )

        // 3. HIDE：隐藏全部 ToolStatusItem，完整保留用户消息、分段 output、权限卡片、提问、最终回复与系统提示
        assertEquals(
            listOf("u1", "seg1", "perm1", "q1", "final1", "n1", "u2", "seg2", "final2"),
            filterChatItemsForDisplay(allItems, ModelOutputDisplayMode.HIDE).map { it.id },
        )

        // 4. 存储键解析与兜底校验
        assertEquals(ModelOutputDisplayMode.SHOW_ALL, ModelOutputDisplayMode.fromStorageKey("show_all"))
        assertEquals(ModelOutputDisplayMode.LAST_ONLY, ModelOutputDisplayMode.fromStorageKey("last_only"))
        assertEquals(ModelOutputDisplayMode.HIDE, ModelOutputDisplayMode.fromStorageKey("hide"))
        assertEquals(ModelOutputDisplayMode.SHOW_ALL, ModelOutputDisplayMode.fromStorageKey("unknown"))
        assertEquals(ModelOutputDisplayMode.SHOW_ALL, ModelOutputDisplayMode.fromStorageKey(null))
    }

    @Test
    fun attachmentFileName_moveIntoSession_exportById_andFileCardWorkflow() = runBlocking {
        val tempRoot = java.nio.file.Files.createTempDirectory("maid_session_io_test").toFile()
        val internalRoot = java.io.File(tempRoot, "internal_files").apply { mkdirs() }
        val externalDir = java.io.File(tempRoot, "external_files").apply { mkdirs() }
        val exportDir = java.io.File(tempRoot, "exported_files").apply { mkdirs() }
        SessionAttachmentStorage.setCustomInternalRootDirForTest(internalRoot)
        try {
            val sourceDoc = java.io.File(externalDir, "季度报告.txt").apply {
                writeText("Q4 营收与成本分析正文", Charsets.UTF_8)
            }
            val sourceImg = java.io.File(externalDir, "chart_preview.png").apply {
                writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A))
            }

            val session = ChatSession(id = "session_io_1")

            // 1. 文件输入：提供 URI 与描述，将文件移动到 session 内部
            val movedDoc = session.moveFileToSession(
                uri = sourceDoc.toURI().toString(),
                description = "第四季度财务报告",
            )
            assertEquals("file_1", movedDoc.id)
            assertEquals("季度报告.txt", movedDoc.fileName)
            assertEquals("第四季度财务报告", movedDoc.description)
            assertFalse(movedDoc.isImage)
            assertFalse("源文件移入 session 后应被移走", sourceDoc.exists())

            val expectedInternalDoc = java.io.File(java.io.File(internalRoot, "session_io_1"), "file_1")
            assertTrue(expectedInternalDoc.exists())
            assertEquals("Q4 营收与成本分析正文", expectedInternalDoc.readText(Charsets.UTF_8))
            assertEquals(expectedInternalDoc.toURI().toString(), movedDoc.uri)

            // 移入图片时自动识别 isImage = true 与文件名
            val movedImg = session.moveFileToSession(
                uri = sourceImg.toURI().toString(),
                description = "折线图预览",
            )
            assertEquals("img_1", movedImg.id)
            assertEquals("chart_preview.png", movedImg.fileName)
            assertTrue(movedImg.isImage)
            assertFalse(sourceImg.exists())

            // 更新文件名
            val renamedDoc = session.updateAttachmentFileName("file_1", "2026_Q4_报告.txt")!!
            assertEquals("2026_Q4_报告.txt", renamedDoc.fileName)

            // 2. 消息卡片：发送文件打开卡片并确认不受“隐藏中间过程”设置过滤
            val toolReply = session.sendFileCard(attachmentIdOrUri = "file_1")
            assertTrue(toolReply.contains("file_1"))
            assertTrue(toolReply.contains("2026_Q4_报告.txt"))
            val card = session.items.last() as ChatItem.FileCardItem
            assertEquals("file_1", card.attachmentId)
            assertEquals("2026_Q4_报告.txt", card.fileName)
            assertEquals("第四季度财务报告", card.description)
            assertEquals(expectedInternalDoc.toURI().toString(), card.uri)
            assertFalse(card.isImage)
            assertFalse(card.isIntermediateProcessOutput())
            assertEquals(listOf(card), filterChatItemsForDisplay(listOf(card), ModelOutputDisplayMode.HIDE))

            // 3. 文件输出：提供 id 与目标目录 URI，自动按 fileName 导出到目录
            val dirExport = session.exportFileFromSession(
                attachmentId = "file_1",
                targetUri = exportDir.absolutePath,
            )
            assertTrue(dirExport.success)
            val exportedInDir = java.io.File(exportDir, "2026_Q4_报告.txt")
            assertTrue(exportedInDir.exists())
            assertEquals("Q4 营收与成本分析正文", exportedInDir.readText(Charsets.UTF_8))
            assertTrue("导出后 session 内部副本仍应保留", expectedInternalDoc.exists())

            // 4. 文件输出：提供 id 与具体文件路径，且验证 overwrite=false 保护
            val explicitExportFile = java.io.File(exportDir, "custom_export.txt")
            val firstExport = session.exportFileFromSession(
                attachmentId = "file_1",
                targetUri = explicitExportFile.toURI().toString(),
                overwrite = false,
            )
            assertTrue(firstExport.success)
            assertEquals("Q4 营收与成本分析正文", explicitExportFile.readText(Charsets.UTF_8))

            val blockedOverwrite = session.exportFileFromSession(
                attachmentId = "file_1",
                targetUri = explicitExportFile.toURI().toString(),
                overwrite = false,
            )
            assertFalse(blockedOverwrite.success)
            assertTrue(blockedOverwrite.message.contains("Target already exists"))

            // 5. MIME 类型推断校验
            assertEquals("application/pdf", SessionFileOpener.resolveMimeType(fileName = "spec.pdf"))
            assertEquals("text/plain", SessionFileOpener.resolveMimeType(fileName = "2026_Q4_报告.txt"))
            assertEquals("image/png", SessionFileOpener.resolveMimeType(fileName = "chart_preview.png"))
        } finally {
            SessionAttachmentStorage.setCustomInternalRootDirForTest(null)
            tempRoot.deleteRecursively()
        }
    }
}
