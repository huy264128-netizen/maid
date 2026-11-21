package top.aaa000111.maid.chat

import ai.koog.agents.core.tools.ToolDescriptor
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.provider.LlmProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ChatSessionRepositoryTest {

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
        private val replyProvider: (Prompt) -> String,
    ) : PromptExecutor {
        val capturedPrompts = mutableListOf<Prompt>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> {
            capturedPrompts += prompt
            return listOf(
                Message.Assistant(
                    content = replyProvider(prompt),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
            )
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

    @After
    fun tearDown() {
        ChatSessionRepository.resetForTest()
    }

    @Test
    fun roundTrip_preservesMetadataAllChatItemsAndKoogHistoryWithAttachments() {
        val storageRoot = tempFolder.root.toPath().resolve("maid_sessions")
        ChatSessionRepository.init(storageRoot)

        val userItem = ChatItem.UserMessage(
            id = "u-1",
            content = "帮我看看这张图",
            fileUris = listOf("content://docs/spec.pdf"),
            imageUris = listOf("content://media/shot.png"),
            createdAtMillis = 1_000L,
        )
        val toolItem = ChatItem.ToolStatusItem(
            id = "t-1",
            title = "屏幕分析",
            detail = "已提取关键元素",
            isCompleted = true,
            createdAtMillis = 1_100L,
        )
        val assistantItem = ChatItem.AssistantMessage(
            id = "a-1",
            markdown = "**识别完成**：顶部是状态栏。",
            provider = LlmProvider.DEEPSEEK,
            modelId = "deepseek-chat",
            senderLabel = null,
            fileUris = listOf("file:///tmp/out.txt"),
            imageUris = listOf("file:///tmp/annotated.png"),
            isStreaming = true,
            createdAtMillis = 1_200L,
        )
        val noticeItem = ChatItem.SystemNoticeItem(
            id = "n-1",
            message = "连接已恢复",
            isError = false,
            createdAtMillis = 1_300L,
        )
        val permissionItem = ChatItem.PermissionRequestItem(
            id = "p-1",
            toolsetId = "screen",
            toolsetTitle = "屏幕观察与元素交互工具集",
            toolsetDescription = "提供屏幕截图与 UI 树导出能力",
            requiredPrivileges = listOf(
                AndroidPrivilege.ACCESSIBILITY,
                AndroidPrivilege.SHIZUKU,
                AndroidPrivilege.ROOT,
            ),
            isCompleted = true,
            isGranted = true,
            matchedPrivileges = listOf(AndroidPrivilege.SHIZUKU),
            availablePrivileges = listOf(AndroidPrivilege.SHIZUKU),
            resultSummary = "已满足所需权限",
            createdAtMillis = 1_400L,
        )
        val history = listOf<Message>(
            Message.User(
                content = "帮我看看这张图",
                metaInfo = RequestMetaInfo.Empty,
                attachments = listOf(
                    Attachment.Image(
                        content = AttachmentContent.Binary.Bytes(byteArrayOf(1, 2, 3, 4)),
                        format = "png",
                        mimeType = "image/png",
                        fileName = "shot.png",
                    ),
                    Attachment.Image(
                        content = AttachmentContent.URL("https://example.com/ref.jpg"),
                        format = "jpg",
                        mimeType = "image/jpeg",
                        fileName = "ref.jpg",
                    ),
                ),
            ),
            Message.Assistant(
                content = "**识别完成**：顶部是状态栏。",
                metaInfo = ResponseMetaInfo.Empty,
            ),
        )

        ChatSessionRepository.enqueueSave(
            PersistedChatSession(
                id = "session/alpha",
                title = "图片分析会话",
                defaultTitlePrefix = "新对话",
                createdAtMillis = 1_000L,
                updatedAtMillis = 2_000L,
                items = listOf(userItem, toolItem, assistantItem, noticeItem, permissionItem),
                history = history,
                fileUriPool = listOf("content://docs/spec.pdf", "content://docs/extra.txt"),
                imageUriPool = listOf("content://media/shot.png"),
            ),
        )
        ChatSessionRepository.setActiveSessionId("session/alpha")
        ChatSessionRepository.flush()

        // 模拟进程重启后重新初始化仓库
        ChatSessionRepository.resetForTest()
        ChatSessionRepository.init(storageRoot)

        val loaded = ChatSessionRepository.loadAllSessions()
        assertEquals(1, loaded.size)
        val restored = loaded.single()
        assertEquals("session/alpha", restored.id)
        assertEquals("图片分析会话", restored.title)
        assertEquals("新对话", restored.defaultTitlePrefix)
        assertEquals(1_000L, restored.createdAtMillis)
        assertEquals(2_000L, restored.updatedAtMillis)
        assertEquals("session/alpha", ChatSessionRepository.getActiveSessionId())
        assertEquals(
            listOf("content://docs/spec.pdf", "content://docs/extra.txt"),
            restored.fileUriPool,
        )
        assertEquals(listOf("content://media/shot.png"), restored.imageUriPool)
        assertEquals(
            listOf("content://docs/spec.pdf", "content://docs/extra.txt", "content://media/shot.png"),
            restored.uriPool,
        )

        assertEquals(5, restored.items.size)
        assertEquals(userItem, restored.items[0])
        assertEquals(toolItem, restored.items[1])
        // 流式标记重启后应统一恢复为 false
        assertEquals(assistantItem.copy(isStreaming = false), restored.items[2])
        assertEquals(noticeItem, restored.items[3])
        assertEquals(permissionItem, restored.items[4])

        assertEquals(2, restored.history.size)
        val restoredUserMsg = restored.history[0] as Message.User
        assertEquals("帮我看看这张图", restoredUserMsg.content)
        assertEquals(2, restoredUserMsg.attachments.size)
        val firstAttachment = restoredUserMsg.attachments[0] as Attachment.Image
        assertEquals("shot.png", firstAttachment.fileName)
        assertTrue(firstAttachment.content is AttachmentContent.Binary.Bytes)
        val secondAttachment = restoredUserMsg.attachments[1] as Attachment.Image
        assertTrue(secondAttachment.content is AttachmentContent.URL)
        assertEquals("https://example.com/ref.jpg", (secondAttachment.content as AttachmentContent.URL).url)
        assertEquals("**识别完成**：顶部是状态栏。", (restored.history[1] as Message.Assistant).content)
    }

    @Test
    fun loadAllSessions_ordersByUpdatedAtDescendingAndIgnoresCorruptFiles() {
        val storageRoot = tempFolder.root.toPath().resolve("maid_sessions")
        val store = FileChatSessionStore(storageRoot)
        try {
            store.enqueueSave(
                PersistedChatSession(
                    id = "older",
                    title = "旧会话",
                    createdAtMillis = 100L,
                    updatedAtMillis = 200L,
                    items = emptyList(),
                    history = emptyList(),
                ),
            )
            store.enqueueSave(
                PersistedChatSession(
                    id = "newer",
                    title = "新会话",
                    createdAtMillis = 150L,
                    updatedAtMillis = 500L,
                    items = emptyList(),
                    history = emptyList(),
                ),
            )
            store.flush()

            val corruptFile = storageRoot.resolve("sessions").resolve("corrupt.json")
            Files.write(corruptFile, "{not valid json".toByteArray(StandardCharsets.UTF_8))

            val loaded = store.loadAllSessions()
            assertEquals(listOf("newer", "older"), loaded.map { it.id })
        } finally {
            store.close()
        }
    }

    @Test
    fun dispatcherIntegration_persistsTurnsActiveSessionAndSurvivesRestart() = runBlocking {
        val storageRoot = tempFolder.root.toPath().resolve("dispatcher_sessions")
        val executor1 = RecordingPromptExecutor { prompt ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            "回复：$lastUser"
        }

        val store1 = FileChatSessionStore(storageRoot)
        val dispatcher1 = ChatTaskDispatcher(
            chatAgent = ChatAgent(
                promptExecutor = executor1,
                model = testModel,
                provider = LlmProvider.OPENAI,
                enableMemory = false,
                autoSaveMemory = false,
            ),
            availabilityChecker = { ChatModelAvailability.Ready },
            sessionStore = store1,
        )

        val session1Id: String
        val session2Id: String
        try {
            val s1 = dispatcher1.preferredInitialSession("新对话")
            session1Id = s1.id
            assertTrue(dispatcher1.submitInput(s1, UserInput(text = "第一轮你好")))
            withTimeout(5_000L) {
                while (dispatcher1.isAnyGenerating()) delay(15L)
            }

            val s2 = dispatcher1.createNewSession("新对话")
            session2Id = s2.id
            assertTrue(dispatcher1.submitInput(s2, UserInput(text = "第二个会话的问题")))
            withTimeout(5_000L) {
                while (dispatcher1.isAnyGenerating()) delay(15L)
            }

            // 切回 s1 作为当前活跃会话，再创建并删除一个临时会话
            dispatcher1.markActiveSession(session1Id)
            val tempSession = dispatcher1.createNewSession("新对话")
            dispatcher1.markActiveSession(session1Id)
            dispatcher1.deleteSession(tempSession.id, "新对话")
            assertEquals(session1Id, store1.getActiveSessionId())

            store1.flush()
        } finally {
            dispatcher1.shutdown()
            store1.close()
        }

        // 重启：创建新的 store 与 dispatcher，验证会话、消息、Koog 历史与活跃会话恢复
        val executor2 = RecordingPromptExecutor { "重启后继续回复" }
        val store2 = FileChatSessionStore(storageRoot)
        val dispatcher2 = ChatTaskDispatcher(
            chatAgent = ChatAgent(
                promptExecutor = executor2,
                model = testModel,
                provider = LlmProvider.OPENAI,
                enableMemory = false,
                autoSaveMemory = false,
            ),
            availabilityChecker = { ChatModelAvailability.Ready },
            sessionStore = store2,
        )

        try {
            val restoredSessions = dispatcher2.sessions.value
            assertEquals(2, restoredSessions.size)
            val preferred = dispatcher2.preferredInitialSession("新对话")
            assertEquals(session1Id, preferred.id)
            assertEquals("第一轮你好", preferred.title)
            assertEquals(2, preferred.items.size)
            assertEquals(2, preferred.historySize)

            val restoredS2 = dispatcher2.findSession(session2Id)!!
            assertEquals("第二个会话的问题", restoredS2.title)
            assertEquals(2, restoredS2.historySize)

            // 在恢复后的 session1 中继续对话，确认旧短期历史被带入新 Prompt
            assertTrue(dispatcher2.submitInput(preferred, UserInput(text = "重启后追问")))
            withTimeout(5_000L) {
                while (dispatcher2.isAnyGenerating()) delay(15L)
            }

            val promptAfterRestart = executor2.capturedPrompts.single()
            assertTrue(promptAfterRestart.messages.any { it.content == "第一轮你好" })
            assertTrue(promptAfterRestart.messages.any { it.content == "回复：第一轮你好" })
            assertTrue(promptAfterRestart.messages.any { it.content == "重启后追问" })
            assertEquals(4, preferred.historySize)
        } finally {
            dispatcher2.shutdown()
            store2.close()
        }
    }

    @Test
    fun deleteSession_removesFileAndCreatesFallbackWhenLastSessionDeleted() {
        val storageRoot = tempFolder.root.toPath().resolve("delete_sessions")
        val store1 = FileChatSessionStore(storageRoot)
        val dispatcher1 = ChatTaskDispatcher(
            availabilityChecker = { ChatModelAvailability.Ready },
            sessionStore = store1,
        )

        val originalId: String
        val fallbackId: String
        try {
            val onlySession = dispatcher1.preferredInitialSession("新对话")
            originalId = onlySession.id
            onlySession.appendUserItem("会被删除的消息")

            val fallback = dispatcher1.deleteSession(originalId, "新对话")
            fallbackId = fallback.id
            assertNotEquals(originalId, fallbackId)
            assertEquals(1, dispatcher1.sessions.value.size)
            assertEquals(fallbackId, store1.getActiveSessionId())
            store1.flush()
        } finally {
            dispatcher1.shutdown()
            store1.close()
        }

        val store2 = FileChatSessionStore(storageRoot)
        try {
            val loaded = store2.loadAllSessions()
            assertEquals(1, loaded.size)
            assertEquals(fallbackId, loaded.single().id)
            assertTrue(loaded.single().items.isEmpty())
            assertFalse(loaded.any { it.id == originalId })
        } finally {
            store2.close()
        }
    }

    @Test
    fun clearSessionAndTransientStates_persistsCleanIdleSession() = runBlocking {
        val storageRoot = tempFolder.root.toPath().resolve("clear_sessions")
        val store = FileChatSessionStore(storageRoot)
        try {
            val session = ChatSession(
                id = "s-clear",
                title = "待清理会话",
                onPersist = { store.enqueueSave(it) },
            )
            session.appendUserItem("保留一条消息")
            session.enterPlanning(checkpointIndex = 1, totalCheckpoints = 2, currentCheckpointTitle = "测试")
            session.input("补充输入")
            session.exitPlanning()
            store.flush()

            val loadedBeforeClear = store.loadAllSessions().single()
            assertEquals(2, loadedBeforeClear.items.size)

            session.clear()
            store.flush()
            val loadedAfterClear = store.loadAllSessions().single()
            assertTrue(loadedAfterClear.items.isEmpty())
            assertTrue(loadedAfterClear.history.isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun persistedAttachments_surviveRoundTripAndClearAllSessionsDeletesInternalAttachmentFiles() {
        val storageRoot = tempFolder.root.toPath().resolve("att_repo_sessions")
        val internalRoot = tempFolder.root.toPath().resolve("internal_files").toFile().apply { mkdirs() }
        SessionAttachmentStorage.setCustomInternalRootDirForTest(internalRoot)
        ChatSessionRepository.init(storageRoot)
        try {
            val sessionDir = java.io.File(internalRoot, "session_att_1").apply { mkdirs() }
            val internalFile = java.io.File(sessionDir, "file_1").apply {
                writeText("persisted content", StandardCharsets.UTF_8)
            }
            val attachment = SessionAttachment(
                id = "file_1",
                description = "用户发送的测试报告",
                uri = internalFile.toURI().toString(),
                originalUri = "content://docs/report.txt",
                isImage = false,
                createdAtMillis = 1234L,
            )

            ChatSessionRepository.enqueueSave(
                PersistedChatSession(
                    id = "session_att_1",
                    title = "带附件的会话",
                    createdAtMillis = 1000L,
                    updatedAtMillis = 2000L,
                    items = listOf(
                        ChatItem.UserMessage(
                            id = "u-att-1",
                            content = "请看附件",
                            fileUris = listOf("content://docs/report.txt"),
                            attachmentIds = listOf("file_1"),
                            createdAtMillis = 1000L,
                        ),
                    ),
                    history = emptyList(),
                    attachments = listOf(attachment),
                    fileUriPool = listOf(internalFile.toURI().toString()),
                ),
            )
            ChatSessionRepository.flush()

            val loaded = ChatSessionRepository.loadAllSessions().single()
            assertEquals(listOf(attachment), loaded.attachments)
            assertEquals("report.txt", loaded.attachments.single().fileName)
            assertEquals(listOf("file_1"), (loaded.items.single() as ChatItem.UserMessage).attachmentIds)

            assertTrue(internalFile.exists())
            ChatSessionRepository.clearAllSessions()
            assertTrue(ChatSessionRepository.loadAllSessions().isEmpty())
            assertFalse(sessionDir.exists())
        } finally {
            SessionAttachmentStorage.setCustomInternalRootDirForTest(null)
        }
    }

    @Test
    fun fileCardItemAndExplicitFileName_survivePersistenceRoundTrip() {
        val storageRoot = tempFolder.root.toPath().resolve("file_card_sessions")
        ChatSessionRepository.init(storageRoot)

        val attachment = SessionAttachment(
            id = "file_1",
            fileName = "项目计划书.pdf",
            description = "导出的项目计划书",
            uri = "file:///data/user/0/pkg/files/s_card/file_1",
            originalUri = "file:///sdcard/Download/项目计划书.pdf",
            isImage = false,
            createdAtMillis = 5000L,
        )
        val fileCard = ChatItem.FileCardItem(
            id = "fc-1",
            attachmentId = "file_1",
            fileName = "项目计划书.pdf",
            description = "点击打开查看项目计划书",
            uri = "file:///data/user/0/pkg/files/s_card/file_1",
            isImage = false,
            createdAtMillis = 5100L,
        )

        ChatSessionRepository.enqueueSave(
            PersistedChatSession(
                id = "s_card",
                title = "文件卡片持久化测试",
                createdAtMillis = 5000L,
                updatedAtMillis = 5200L,
                items = listOf(fileCard),
                history = emptyList(),
                attachments = listOf(attachment),
                fileUriPool = listOf(attachment.uri),
            ),
        )
        ChatSessionRepository.flush()

        ChatSessionRepository.resetForTest()
        ChatSessionRepository.init(storageRoot)

        val restored = ChatSessionRepository.loadAllSessions().single()
        assertEquals(listOf(attachment), restored.attachments)
        assertEquals("项目计划书.pdf", restored.attachments.single().fileName)
        assertEquals(listOf(fileCard), restored.items)
    }
}
