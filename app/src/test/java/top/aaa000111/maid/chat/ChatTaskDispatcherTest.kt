package top.aaa000111.maid.chat

import ai.koog.agents.core.tools.ToolDescriptor
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.learning.TaskEvidenceRepository
import top.aaa000111.maid.learning.TaskExecutionStatus
import top.aaa000111.maid.learning.TaskRecorder
import top.aaa000111.maid.learning.VerificationStatus
import top.aaa000111.maid.skill.SkillRepository
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.memory.MaidMemoryManager
import top.aaa000111.maid.provider.LlmProvider
import java.util.concurrent.CopyOnWriteArrayList

class ChatTaskDispatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var evidence: TaskEvidenceRepository
    private lateinit var recorder: TaskRecorder

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gpt-4o-mini",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
        ),
        contextLength = 128_000L,
    )

    private class SuspendingPromptExecutor(
        private val onExecute: suspend (prompt: Prompt, tools: List<ToolDescriptor>) -> List<Message.Response>,
    ) : PromptExecutor {
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> = onExecute(prompt, tools)

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

    @Before
    fun setUp() {
        MaidMemoryManager.init(rootPath = tempFolder.root.toPath())
        evidence = TaskEvidenceRepository(tempFolder.root.toPath().resolve("evidence"))
        recorder = TaskRecorder(evidence, SkillRepository(tempFolder.root.toPath().resolve("skills")))
    }

    @After
    fun tearDown() {
        MaidMemoryManager.resetForTest()
    }

    @Test
    fun firstReplyGeneratesTitleForTheOriginalSessionInTheBackground() = runBlocking {
        val titleStarted = CompletableDeferred<String>()
        val generatedTitle = CompletableDeferred<String>()
        val executor = SuspendingPromptExecutor { _, _ ->
            listOf(Message.Assistant(content = "回复", metaInfo = ResponseMetaInfo.Empty))
        }
        val dispatcher = ChatTaskDispatcher(
            chatAgent = ChatAgent(promptExecutor = executor, model = testModel, enableMemory = false),
            availabilityChecker = { ChatModelAvailability.Ready },
            taskRecorder = recorder,
            sessionTitleGenerator = {
                titleStarted.complete(it)
                generatedTitle.await()
            },
        )
        try {
            val session = dispatcher.ensureInitialSession("新对话")
            val firstMessage = "帮我分析一段日志"
            assertTrue(dispatcher.submitInput(session, UserInput(text = firstMessage)))

            withTimeout(5_000L) {
                assertEquals(firstMessage, titleStarted.await())
                dispatcher.awaitActiveTurn(session.id)
                assertEquals(firstMessage, session.title)
                val other = dispatcher.createNewSession("新对话")
                dispatcher.markActiveSession(other.id)

                generatedTitle.complete("“日志分析”\n多余说明")
                session.state.first { it.title == "日志分析" }
                assertEquals("新对话 2", other.title)
            }
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun unavailableModelRecordsRejectedExecutionWithoutRunningAgent() = runBlocking {
        val executor = SuspendingPromptExecutor { _, _ -> error("Model must not run") }
        val dispatcher = ChatTaskDispatcher(
            chatAgent = ChatAgent(promptExecutor = executor, model = testModel, enableMemory = false),
            availabilityChecker = { ChatModelAvailability.Unavailable("not configured") },
            taskRecorder = recorder,
        )
        try {
            val session = dispatcher.ensureInitialSession("test")
            assertTrue(dispatcher.submitInput(session, UserInput(text = "request")))
            withTimeout(5_000L) {
                while (evidence.list().none { it.status == TaskExecutionStatus.REJECTED }) delay(10)
            }
            val task = evidence.list().single()
            assertEquals("model_unavailable", task.reason)
            assertEquals(VerificationStatus.UNKNOWN, task.verification)
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun submitInput_runsOnDedicatedWorkerThreadAndSurvivesUiScopeCancellation() = runBlocking {
        val observedThreads = CopyOnWriteArrayList<String>()
        val startedGate = CompletableDeferred<Unit>()
        val allowFinishGate = CompletableDeferred<Unit>()
        val completedSignal = CompletableDeferred<Unit>()

        val executor = SuspendingPromptExecutor { prompt, _ ->
            observedThreads += Thread.currentThread().name
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            if (!lastUser.contains("Extract facts") && !lastUser.contains("autoDetectFacts")) {
                startedGate.complete(Unit)
                allowFinishGate.await()
            }
            listOf(Message.Assistant(content = "后台独立线程完成回复", metaInfo = ResponseMetaInfo.Empty))
        }

        val dispatcher = ChatTaskDispatcher(
            chatAgent = ChatAgent(
                promptExecutor = executor,
                model = testModel,
                provider = LlmProvider.OPENAI,
                enableMemory = false,
            ),
            availabilityChecker = { ChatModelAvailability.Ready },
            taskRecorder = recorder,
        )

        try {
            val session = dispatcher.ensureInitialSession("新对话")
            val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            uiScope.launch {
                dispatcher.submitInput(session, UserInput(text = "帮我分析一段日志"))
            }

            withTimeout(5_000L) {
                startedGate.await()
            }
            assertTrue(dispatcher.isSessionGenerating(session.id))

            // 模拟 Activity / ViewModel 被销毁导致 UI 协程作用域取消
            uiScope.cancel()
            allowFinishGate.complete(Unit)

            withTimeout(5_000L) {
                while (dispatcher.isSessionGenerating(session.id)) {
                    delay(20L)
                }
                completedSignal.complete(Unit)
                completedSignal.await()
            }

            assertTrue("Observed executor threads: $observedThreads", observedThreads.any { it.startsWith("maid-chat-worker-") })
            assertEquals(TaskExecutionStatus.COMPLETED, evidence.list().single().status)
            assertEquals(VerificationStatus.UNKNOWN, evidence.list().single().verification)
            val assistantItems = session.items.filterIsInstance<ChatItem.AssistantMessage>()
            assertTrue(assistantItems.any { it.markdown == "后台独立线程完成回复" })
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun submitInput_multipleSessionsRunIndependentlyAndAnswerPendingQuestionWhileGenerating() = runBlocking {
        val sessionAGate = CompletableDeferred<Unit>()
        val sessionARelease = CompletableDeferred<Unit>()
        val sessionBReplied = CompletableDeferred<Unit>()

        val executor = SuspendingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            if (lastUser.contains("Session A")) {
                sessionAGate.complete(Unit)
                sessionARelease.await()
                listOf(Message.Assistant(content = "A 完成", metaInfo = ResponseMetaInfo.Empty))
            } else {
                sessionBReplied.complete(Unit)
                listOf(Message.Assistant(content = "B 完成", metaInfo = ResponseMetaInfo.Empty))
            }
        }

        val dispatcher = ChatTaskDispatcher(
            chatAgent = ChatAgent(
                promptExecutor = executor,
                model = testModel,
                provider = LlmProvider.OPENAI,
                enableMemory = false,
            ),
            availabilityChecker = { ChatModelAvailability.Ready },
            taskRecorder = recorder,
        )

        try {
            val sessionA = dispatcher.ensureInitialSession("会话 A")
            val sessionB = dispatcher.createNewSession("会话 B")

            assertTrue(dispatcher.submitInput(sessionA, UserInput(text = "来自 Session A")))
            withTimeout(5_000L) { sessionAGate.await() }
            assertTrue(dispatcher.isSessionGenerating(sessionA.id))

            // 在 A 生成中向 A 发起一个待回答 Question，提交回答不应被 isSessionGenerating 阻塞
            val questionDeferred = CompletableDeferred<UserInput>()
            sessionA.askQuestion(
                Question(
                    content = "请确认是否继续？",
                    onInputAnswer = { questionDeferred.complete(it) },
                ),
            )
            assertTrue(sessionA.hasPendingQuestion())
            assertTrue(dispatcher.submitInput(sessionA, UserInput(text = "确认继续")))
            val answer = withTimeout(5_000L) { questionDeferred.await() }
            assertEquals("确认继续", answer.text)

            // 切换到会话 B 并发送消息，不应影响会话 A
            assertTrue(dispatcher.submitInput(sessionB, UserInput(text = "来自 Session B")))
            withTimeout(5_000L) { sessionBReplied.await() }
            assertTrue(dispatcher.isSessionGenerating(sessionA.id))

            sessionARelease.complete(Unit)
            withTimeout(5_000L) {
                while (dispatcher.isAnyGenerating()) {
                    delay(20L)
                }
            }
            assertFalse(dispatcher.isAnyGenerating())
            assertTrue(sessionA.items.filterIsInstance<ChatItem.AssistantMessage>().any { it.markdown == "A 完成" })
            assertTrue(sessionB.items.filterIsInstance<ChatItem.AssistantMessage>().any { it.markdown == "B 完成" })
            val tasks = evidence.list()
            assertEquals(2, tasks.size)
            val taskA = tasks.single { it.sessionId == sessionA.id }
            assertEquals(TaskExecutionStatus.COMPLETED, taskA.status)
            assertEquals(listOf("USER_REQUEST", "QUESTION_ANSWERED"), taskA.inputs.map { it.kind })
            val visibleMessageIds = sessionA.items.map { it.id }.toSet()
            assertTrue(taskA.inputs.all { it.messageId in visibleMessageIds })
            assertEquals(listOf(sessionA.items.filterIsInstance<ChatItem.AssistantMessage>().last().id),
                taskA.outputMessageIds)
            assertEquals(taskA, evidence.get(taskA.taskId))
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun stopSession_onlyCancelsTargetSession() = runBlocking {
        val sessionAGate = CompletableDeferred<Unit>()
        val sessionBGate = CompletableDeferred<Unit>()
        val sessionBRelease = CompletableDeferred<Unit>()

        val executor = SuspendingPromptExecutor { prompt, _ ->
            val lastUser = prompt.messages.lastOrNull { it is Message.User }?.content.orEmpty()
            if (lastUser.contains("Session A")) {
                sessionAGate.complete(Unit)
                CompletableDeferred<Unit>().await()
                listOf(Message.Assistant(content = "unreachable", metaInfo = ResponseMetaInfo.Empty))
            } else {
                sessionBGate.complete(Unit)
                sessionBRelease.await()
                listOf(Message.Assistant(content = "B 正常完成", metaInfo = ResponseMetaInfo.Empty))
            }
        }

        val dispatcher = ChatTaskDispatcher(
            chatAgent = ChatAgent(
                promptExecutor = executor,
                model = testModel,
                provider = LlmProvider.OPENAI,
                enableMemory = false,
            ),
            availabilityChecker = { ChatModelAvailability.Ready },
            taskRecorder = recorder,
        )

        try {
            val sessionA = dispatcher.ensureInitialSession("会话 A")
            val sessionB = dispatcher.createNewSession("会话 B")

            assertTrue(dispatcher.submitInput(sessionA, UserInput(text = "Session A")))
            assertTrue(dispatcher.submitInput(sessionB, UserInput(text = "Session B")))
            withTimeout(5_000L) {
                sessionAGate.await()
                sessionBGate.await()
            }

            dispatcher.stopSession(sessionA.id)
            withTimeout(5_000L) {
                while (dispatcher.isSessionGenerating(sessionA.id)) {
                    delay(20L)
                }
            }

            assertFalse(dispatcher.isSessionGenerating(sessionA.id))
            assertTrue(dispatcher.isSessionGenerating(sessionB.id))

            sessionBRelease.complete(Unit)
            withTimeout(5_000L) {
                while (dispatcher.isSessionGenerating(sessionB.id)) {
                    delay(20L)
                }
            }
            assertTrue(sessionB.items.filterIsInstance<ChatItem.AssistantMessage>().any { it.markdown == "B 正常完成" })
            withTimeout(5_000L) {
                while (evidence.list().any { it.status == TaskExecutionStatus.RUNNING }) delay(10)
            }
            assertEquals(TaskExecutionStatus.CANCELLED, evidence.list().single { it.sessionId == sessionA.id }.status)
            assertEquals(TaskExecutionStatus.COMPLETED, evidence.list().single { it.sessionId == sessionB.id }.status)
        } finally {
            dispatcher.shutdown()
        }
    }
}
