package top.aaa000111.maid.androidtool

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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aaa000111.maid.agent.ChatAgent
import top.aaa000111.maid.chat.ChatItem
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.provider.LlmProvider
import java.util.concurrent.atomic.AtomicReference

class ToolsetPermissionToolSetTest {

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

    @Test
    fun catalog_resolvesAllBuiltInToolsetsByIdClassNameAndAliases() {
        assertEquals("input", ToolsetPermissionCatalog.resolve("input")?.toolsetId)
        assertEquals("input", ToolsetPermissionCatalog.resolve("InputToolSet")?.toolsetId)
        assertEquals(
            setOf(AndroidPrivilege.SHIZUKU, AndroidPrivilege.ACCESSIBILITY, AndroidPrivilege.ROOT),
            ToolsetPermissionCatalog.resolve("input")?.requiredPrivileges?.toSet(),
        )

        assertEquals("screen", ToolsetPermissionCatalog.resolve("screen")?.toolsetId)
        assertEquals("screen", ToolsetPermissionCatalog.resolve("ScreenToolSet")?.toolsetId)
        assertEquals(
            setOf(AndroidPrivilege.ACCESSIBILITY, AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT),
            ToolsetPermissionCatalog.resolve("screen")?.requiredPrivileges?.toSet(),
        )

        assertEquals("shell", ToolsetPermissionCatalog.resolve("shell")?.toolsetId)
        assertEquals(
            setOf(AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT),
            ToolsetPermissionCatalog.resolve("shell")?.requiredPrivileges?.toSet(),
        )

        assertEquals("app_control", ToolsetPermissionCatalog.resolve("app_control")?.toolsetId)
        assertEquals("app_control", ToolsetPermissionCatalog.resolve("AppControlToolSet")?.toolsetId)

        assertEquals("window", ToolsetPermissionCatalog.resolve("window")?.toolsetId)
        assertEquals("window", ToolsetPermissionCatalog.resolve("WindowToolSet")?.toolsetId)

        assertEquals("root_system", ToolsetPermissionCatalog.resolve("root_system")?.toolsetId)
        assertEquals(
            setOf(AndroidPrivilege.ROOT_SHIZUKU, AndroidPrivilege.ROOT),
            ToolsetPermissionCatalog.resolve("root_system")?.requiredPrivileges?.toSet(),
        )

        assertEquals("device_policy", ToolsetPermissionCatalog.resolve("device_policy")?.toolsetId)
        assertEquals(
            listOf(AndroidPrivilege.DHIZUKU),
            ToolsetPermissionCatalog.resolve("device_policy")?.requiredPrivileges,
        )

        assertEquals("file_management", ToolsetPermissionCatalog.resolve("file_management")?.toolsetId)
        assertEquals("file_management", ToolsetPermissionCatalog.resolve("FileManagementToolSet")?.toolsetId)
        assertEquals("file_management", ToolsetPermissionCatalog.resolve("文件管理")?.toolsetId)
        assertEquals(
            listOf(AndroidPrivilege.FILE_MANAGEMENT, AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT),
            ToolsetPermissionCatalog.resolve("file_management")?.requiredPrivileges,
        )

        assertEquals("usage_stats", ToolsetPermissionCatalog.resolve("usage_stats")?.toolsetId)
        assertEquals("usage_stats", ToolsetPermissionCatalog.resolve("UsageStatsToolSet")?.toolsetId)
        assertEquals(
            listOf(AndroidPrivilege.USAGE_STATS, AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT),
            ToolsetPermissionCatalog.resolve("usage_stats")?.requiredPrivileges,
        )

        assertEquals("system_settings", ToolsetPermissionCatalog.resolve("system_settings")?.toolsetId)
        assertEquals("system_settings", ToolsetPermissionCatalog.resolve("SystemSettingsToolSet")?.toolsetId)
        assertEquals(
            listOf(AndroidPrivilege.WRITE_SETTINGS, AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT),
            ToolsetPermissionCatalog.resolve("system_settings")?.requiredPrivileges,
        )
    }

    @Test
    fun requestToolsetPermission_sendsCardWithRequiredPrivilegeList_waitsForConfirm_rechecksAndReturnsResult() =
        runBlocking {
            val currentPrivileges = AtomicReference<Set<AndroidPrivilege>>(emptySet())
            val session = ChatSession(
                id = "perm-session-1",
                privilegeChecker = { currentPrivileges.get() },
            )
            val toolSet = ToolsetPermissionToolSet(session = session)

            val toolCallDeferred = async {
                toolSet.requestToolsetPermission(toolsetName = "screen")
            }

            // 等待权限请求卡片发送到会话消息列表中
            val pendingCard = withTimeout(3_000L) {
                var found: ChatItem.PermissionRequestItem? = null
                while (found == null) {
                    found = session.items
                        .filterIsInstance<ChatItem.PermissionRequestItem>()
                        .firstOrNull()
                    if (found == null) delay(15L)
                }
                found
            }

            assertEquals("screen", pendingCard.toolsetId)
            assertEquals("屏幕观察与元素交互工具集", pendingCard.toolsetTitle)
            assertEquals(
                listOf(
                    AndroidPrivilege.ACCESSIBILITY,
                    AndroidPrivilege.SHIZUKU,
                    AndroidPrivilege.ROOT,
                ),
                pendingCard.requiredPrivileges,
            )
            assertFalse(pendingCard.isCompleted)
            assertFalse(pendingCard.isGranted)
            assertFalse(toolCallDeferred.isCompleted)

            // 模拟用户在系统/管理应用中开启了列表中的其中一项权限（ACCESSIBILITY），然后点击卡片上的“确定”
            currentPrivileges.set(setOf(AndroidPrivilege.ACCESSIBILITY))
            val confirmResult = session.confirmPermissionRequest(pendingCard.id)
            assertNotNull(confirmResult)
            assertTrue(confirmResult!!.isGranted)
            assertEquals(listOf(AndroidPrivilege.ACCESSIBILITY), confirmResult.matchedPrivileges)

            val toolOutput = withTimeout(3_000L) { toolCallDeferred.await() }
            assertTrue(toolOutput.contains("toolset=screen"))
            assertTrue(toolOutput.contains("granted=true"))
            assertTrue(toolOutput.contains("requiredAnyOf=ACCESSIBILITY,SHIZUKU,ROOT"))
            assertTrue(toolOutput.contains("matchedPrivileges=ACCESSIBILITY"))

            // 卡片状态已同步更新为已完成且已满足
            val updatedCard = session.items
                .filterIsInstance<ChatItem.PermissionRequestItem>()
                .single()
            assertTrue(updatedCard.isCompleted)
            assertTrue(updatedCard.isGranted)
            assertEquals(listOf(AndroidPrivilege.ACCESSIBILITY), updatedCard.matchedPrivileges)
            assertTrue(updatedCard.resultSummary.contains("已解锁可用"))
        }

    @Test
    fun requestToolsetPermission_reportsNotGrantedWhenUserConfirmsWithoutEnablingAnyRequiredPrivilege() =
        runBlocking {
            val session = ChatSession(
                id = "perm-session-denied",
                privilegeChecker = { setOf(AndroidPrivilege.ACCESSIBILITY) },
            )
            val toolSet = ToolsetPermissionToolSet(session = session)

            // 请求需要 DHIZUKU 的 device_policy 工具集，而当前只有 ACCESSIBILITY
            val deferred = async {
                toolSet.requestToolsetPermission(toolsetName = "device_policy")
            }

            val card = withTimeout(3_000L) {
                var found: ChatItem.PermissionRequestItem? = null
                while (found == null) {
                    found = session.items
                        .filterIsInstance<ChatItem.PermissionRequestItem>()
                        .firstOrNull()
                    if (found == null) delay(15L)
                }
                found
            }
            assertEquals(listOf(AndroidPrivilege.DHIZUKU), card.requiredPrivileges)

            // 用户直接点击“确定”
            val confirmResult = session.confirmPermissionRequest(card.id)
            assertNotNull(confirmResult)
            assertFalse(confirmResult!!.isGranted)

            val output = withTimeout(3_000L) { deferred.await() }
            assertTrue(output.contains("toolset=device_policy"))
            assertTrue(output.contains("granted=false"))
            assertTrue(output.contains("matchedPrivileges=NONE"))
            assertTrue(output.contains("availablePrivileges=ACCESSIBILITY"))

            val updatedCard = session.items
                .filterIsInstance<ChatItem.PermissionRequestItem>()
                .single()
            assertTrue(updatedCard.isCompleted)
            assertFalse(updatedCard.isGranted)
            assertTrue(updatedCard.resultSummary.contains("权限检查未通过"))
        }

    @Test
    fun chatAgent_exposesRequestToolsetPermissionTool_andResumesAfterUserConfirmsCard() = runBlocking {
        val currentPrivileges = AtomicReference<Set<AndroidPrivilege>>(emptySet())
        val session = ChatSession(
            id = "chat-agent-perm-session",
            privilegeChecker = { currentPrivileges.get() },
        )

        var step = 0
        val executor = RecordingPromptExecutor { prompt, tools ->
            step += 1
            if (step == 1) {
                assertTrue(tools.any { it.name == "requestToolsetPermission" })
                val systemPrompt = prompt.messages
                    .filterIsInstance<Message.System>()
                    .joinToString("\n") { it.content }
                assertTrue(systemPrompt.contains("requestToolsetPermission"))
                assertTrue(systemPrompt.contains("`input`"))
                assertTrue(systemPrompt.contains("`screen`"))
                assertTrue(systemPrompt.contains("`shell`"))
                listOf(
                    Message.Tool.Call(
                        id = "call_perm_1",
                        tool = "requestToolsetPermission",
                        content = """{"toolsetName":"shell"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                val toolResult = prompt.messages
                    .filterIsInstance<Message.Tool.Result>()
                    .last()
                    .content
                assertTrue(toolResult.contains("toolset=shell"))
                assertTrue(toolResult.contains("granted=true"))
                assertTrue(toolResult.contains("matchedPrivileges=SHIZUKU"))
                listOf(
                    Message.Assistant(
                        content = "Shizuku 权限已就绪，shell 工具集已解锁。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val agent = ChatAgent(
            promptExecutor = executor,
            model = testModel,
            provider = LlmProvider.OPENAI,
            enableMemory = false,
            autoSaveMemory = false,
        )

        val chatDeferred = async {
            agent.chat(
                userMessage = "帮我执行一条 shell 命令查看当前状态",
                session = session,
            )
        }

        val card = withTimeout(3_000L) {
            var found: ChatItem.PermissionRequestItem? = null
            while (found == null) {
                found = session.items
                    .filterIsInstance<ChatItem.PermissionRequestItem>()
                    .firstOrNull()
                if (found == null) delay(15L)
            }
            found
        }
        assertEquals("shell", card.toolsetId)
        assertEquals(listOf(AndroidPrivilege.SHIZUKU, AndroidPrivilege.ROOT), card.requiredPrivileges)

        // 用户开启 Shizuku 并点击卡片上的“确定”
        currentPrivileges.set(setOf(AndroidPrivilege.SHIZUKU))
        session.confirmPermissionRequest(card.id)

        val reply = withTimeout(3_000L) { chatDeferred.await() }
        assertEquals("Shizuku 权限已就绪，shell 工具集已解锁。", reply)
    }

    @Test
    fun requestToolsetPermission_shortCircuitsWithoutSendingCardWhenPrivilegeAlreadySatisfied_andChatAgentSystemPromptListsOwnedToolsets() =
        runBlocking {
            val session = ChatSession(
                id = "perm-already-granted-session",
                privilegeChecker = { setOf(AndroidPrivilege.SHIZUKU) },
            )
            val toolSet = ToolsetPermissionToolSet(session = session)

            // 1. 当已持有 SHIZUKU 时，模型即便误调 requestToolsetPermission("shell")，也会立即短路返回已满足，且绝不向聊天界面发送多余卡片
            val output = withTimeout(1_000L) {
                toolSet.requestToolsetPermission("shell")
            }
            assertTrue(output.contains("toolset=shell"))
            assertTrue(output.contains("granted=true"))
            assertTrue(output.contains("alreadySatisfied=true"))
            assertTrue(output.contains("matchedPrivileges=SHIZUKU"))
            assertTrue(output.contains("无需重复申请"))
            assertTrue(
                "Expected no PermissionRequestItem card when toolset is already satisfied",
                session.items.filterIsInstance<ChatItem.PermissionRequestItem>().isEmpty(),
            )

            // 2. 验证 ChatAgent 的 System Prompt 也实时注入了已持有的权限与已就绪工具集列表
            val executor = RecordingPromptExecutor { prompt, _ ->
                val systemPrompt = prompt.messages
                    .filterIsInstance<Message.System>()
                    .joinToString("\n") { it.content }
                assertTrue(systemPrompt.contains("`shell`（通用 Shell 命令工具集）——【已就绪/无需申请】"))
                assertTrue(systemPrompt.contains("`input`（输入与手势交互工具集）——【已就绪/无需申请】"))
                assertTrue(systemPrompt.contains("`screen`（屏幕观察与元素交互工具集）——【已就绪/无需申请】"))
                listOf(
                    Message.Assistant(
                        content = "所有所需工具已就绪，无需重复申请。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
            val agent = ChatAgent(
                promptExecutor = executor,
                model = testModel,
                provider = LlmProvider.OPENAI,
                enableMemory = false,
                autoSaveMemory = false,
            )
            val reply = agent.chat(userMessage = "检查当前状态", session = session)
            assertEquals("所有所需工具已就绪，无需重复申请。", reply)
        }
}
