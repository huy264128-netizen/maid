package top.aaa000111.maid.agent

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.SessionAttachmentStorage
import top.aaa000111.maid.proot.ProotConnectionStatus
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.settings.ModelSafetyConfig
import top.aaa000111.maid.shizuku.ShellCommandResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ProotLinuxAgentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    private lateinit var sessionStorageRoot: File
    private lateinit var rootfsDir: File

    @Before
    fun setUp() {
        sessionStorageRoot = tempFolder.newFolder("session_storage")
        rootfsDir = tempFolder.newFolder("rootfs").apply {
            File(this, "root").mkdirs()
            File(this, "etc").mkdirs()
            File(this, "tmp").mkdirs()
        }
        SessionAttachmentStorage.setCustomInternalRootDirForTest(sessionStorageRoot)
    }

    @After
    fun tearDown() {
        SessionAttachmentStorage.resetForTest()
        ProotLinuxAgentLifecycleManager.resetForTest()
    }

    private class ScriptedPromptExecutor(
        private val responses: MutableList<suspend (prompt: Prompt, tools: List<ToolDescriptor>) -> List<Message.Response>>,
    ) : PromptExecutor {
        val capturedPrompts = CopyOnWriteArrayList<Prompt>()
        val capturedToolNames = CopyOnWriteArrayList<List<String>>()
        private var index = 0

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> {
            capturedPrompts += prompt
            capturedToolNames += tools.map { it.name }
            val handler = responses.getOrElse(index) {
                error("Unexpected extra LLM call #$index")
            }
            index++
            return handler(prompt, tools)
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

    private class FakeProotTerminalHandle(
        private val generation: Long,
        private val rootfsPath: String,
        private val initialWorkingDir: String,
        private val onExecute: (command: String) -> ShellCommandResult = { cmd ->
            ShellCommandResult(exitCode = 0, stdout = "ran: $cmd\n", stderr = "", durationMs = 5L)
        },
    ) : ProotLinuxTerminalHandle {

        private val alive = AtomicBoolean(false)
        private val cmdCount = AtomicLong(0L)
        val executedCommands = CopyOnWriteArrayList<String>()
        var closeCount: Int = 0
            private set

        override val isAlive: Boolean
            get() = alive.get()

        override fun connect(): Boolean {
            alive.set(true)
            return true
        }

        override fun execute(command: String, timeoutMillis: Long): ShellCommandResult {
            check(alive.get()) { "Fake terminal not connected" }
            executedCommands += command
            cmdCount.incrementAndGet()
            return onExecute(command)
        }

        override fun snapshotStatus(): ProotConnectionStatus = ProotConnectionStatus(
            isConnected = alive.get(),
            sessionGeneration = generation,
            rootfsPath = rootfsPath,
            shellExecutable = "/bin/bash",
            workingDir = initialWorkingDir,
            executedCommandCount = cmdCount.get(),
        )

        override fun close() {
            alive.set(false)
            closeCount++
        }
    }

    @Test
    fun lifecycleManager_managesDedicatedTerminal_suggestsSessionWorkspace_andAllowsContainerModification() = runBlocking {
        val createdTerminals = mutableListOf<FakeProotTerminalHandle>()
        val genCounter = AtomicLong(0L)
        val session = ChatSession(id = "sess_alpha", title = "Linux Test")

        // 预先在会话中准备一个文件附件
        val sourceHostFile = tempFolder.newFile("data.csv").apply {
            writeText("name,score\nalice,95\nbob,88\n")
        }
        val importedAtt = session.moveFileToSession(
            uri = sourceHostFile.toURI().toString(),
            description = "待统计成绩表",
            fileName = "data.csv",
        )

        val executor = ScriptedPromptExecutor(
            mutableListOf(
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "call_1",
                            tool = "importSessionFileToProot",
                            content = """{"attachmentIdOrUri":"${importedAtt.id}"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "call_2",
                            tool = "executeProotCommand",
                            content = """{"command":"mkdir -p ~/sess_alpha && echo 'custom_tool=1' > /etc/maid_custom.conf && echo 'done' > ~/sess_alpha/report.txt","workingDir":"~"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "call_3",
                            tool = "writeProotTextFile",
                            content = """{"prootPath":"~/sess_alpha/summary.txt","content":"avg=91.5\n"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "call_4",
                            tool = "exportProotFileToSession",
                            content = """{"prootSourcePath":"~/sess_alpha/summary.txt","description":"成绩统计摘要"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Assistant(
                            content = "已在 ~/sess_alpha/ 完成统计并导出 summary.txt，同时写入了 /etc/maid_custom.conf。",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
            ),
        )

        val agent = ProotLinuxAgent(
            promptExecutor = executor,
            model = testModel,
            visionToolSet = null,
        )
        val manager = ProotLinuxAgentLifecycleManager(
            agent = agent,
            rootfsDirProvider = { rootfsDir },
            terminalFactory = { workDir ->
                FakeProotTerminalHandle(
                    generation = genCounter.incrementAndGet(),
                    rootfsPath = rootfsDir.absolutePath,
                    initialWorkingDir = workDir,
                ).also { createdTerminals += it }
            },
        )

        try {
            val result = manager.runTask(
                task = "分析会话文件并输出汇总报告，同时更新容器配置",
                session = session,
                fileAttachmentIds = listOf(importedAtt.id),
            )

            assertTrue(result.success)
            assertEquals("/root/sess_alpha", result.suggestedWorkingDir)
            assertEquals(1, result.terminalCommandCount)
            assertEquals(
                listOf(
                    "importSessionFileToProot",
                    "executeProotCommand",
                    "writeProotTextFile",
                    "exportProotFileToSession",
                ),
                result.executedTools,
            )

            // 验证系统提示词明确建议 ~/<session id>/ 但不强制，且允许改造容器、无强制隔离
            val firstSystemPrompt = executor.capturedPrompts.first().messages
                .filterIsInstance<Message.System>()
                .joinToString("\n") { it.content }
            assertTrue(firstSystemPrompt.contains("~/sess_alpha"))
            assertTrue(firstSystemPrompt.contains("非强制"))
            assertTrue(firstSystemPrompt.contains("无强制隔离"))
            assertTrue(firstSystemPrompt.contains("改造"))

            // 验证文件已导入到建议目录 /root/sess_alpha/data.csv
            val importedInProot = File(rootfsDir, "root/sess_alpha/data.csv")
            assertTrue(importedInProot.exists())
            assertTrue(importedInProot.readText().contains("alice,95"))

            // 验证 summary.txt 已写入 PRoot 并导出回会话附件池（保留 PRoot 原副本）
            val summaryInProot = File(rootfsDir, "root/sess_alpha/summary.txt")
            assertTrue(summaryInProot.exists())
            val exportedAttachment = session.attachments.firstOrNull { it.normalizedFileName == "summary.txt" }
            assertNotNull(exportedAttachment)
            assertEquals("成绩统计摘要", exportedAttachment!!.description)

            // 验证专用终端被创建、保持连接并记录命令数
            assertEquals(1, createdTerminals.size)
            assertTrue(manager.isTerminalConnected)
            assertEquals(1L, manager.state.value.completedTaskCount)
            assertEquals(1L, manager.state.value.executedCommandCount)

            // 模拟重置专用终端：旧句柄被关闭，新句柄被创建且 generation 递增
            manager.resetDedicatedTerminal()
            assertEquals(2, createdTerminals.size)
            assertEquals(1, createdTerminals[0].closeCount)
            assertTrue(createdTerminals[1].isAlive)
        } finally {
            manager.close()
        }

        // 关闭管理器后，专用终端必须同步关闭且状态变为 CLOSED
        assertTrue(manager.isClosed)
        assertFalse(manager.isTerminalConnected)
        assertEquals(ProotLinuxLifecycleStatus.CLOSED, manager.state.value.status)
        assertEquals(1, createdTerminals.last().closeCount)
    }

    @Test
    fun fileIoToolSet_resolvesPathsSafelyWithinRootfs_andTransfersHostFilesBidirectionally() = runBlocking {
        val (posixEscaped, hostEscaped) = ProotLinuxFileIoToolSet.resolveProotPath(
            prootPath = "/root/../../../../etc/hosts",
            rootfsDir = rootfsDir,
            defaultBaseDirPosix = "/root/s1",
        )
        assertEquals("/etc/hosts", posixEscaped)
        assertEquals(File(rootfsDir, "etc/hosts").canonicalPath, hostEscaped.canonicalPath)

        val (posixTilde, hostTilde) = ProotLinuxFileIoToolSet.resolveProotPath(
            prootPath = "~/s1/sub/file.txt",
            rootfsDir = rootfsDir,
            defaultBaseDirPosix = "/root/s1",
        )
        assertEquals("/root/s1/sub/file.txt", posixTilde)
        assertEquals(File(rootfsDir, "root/s1/sub/file.txt").canonicalPath, hostTilde.canonicalPath)

        val ioTools = ProotLinuxFileIoToolSet(
            rootfsDirProvider = { rootfsDir },
            session = null,
            defaultBaseDirPosix = "/root/s1",
        )

        val hostSrc = tempFolder.newFile("from_host.txt").apply { writeText("hello-proot") }
        val toProotMsg = ioTools.transferFileBetweenHostAndProot(
            direction = "to_proot",
            hostUriOrPath = hostSrc.absolutePath,
            prootPath = "~/s1/in.txt",
            move = false,
        )
        assertTrue(toProotMsg.contains("/root/s1/in.txt"))
        assertEquals("hello-proot", File(rootfsDir, "root/s1/in.txt").readText())

        val readBack = ioTools.readProotTextFile("~/s1/in.txt")
        assertTrue(readBack.contains("hello-proot"))

        val hostDst = File(tempFolder.root, "out_from_proot.txt")
        val fromProotMsg = ioTools.transferFileBetweenHostAndProot(
            direction = "from_proot",
            hostUriOrPath = hostDst.absolutePath,
            prootPath = "~/s1/in.txt",
            move = true,
        )
        assertTrue(fromProotMsg.contains("sourceDeleted=true"))
        assertEquals("hello-proot", hostDst.readText())
        assertFalse(File(rootfsDir, "root/s1/in.txt").exists())
    }

    @Test
    fun plannerAgent_mountsProotLinuxTool_andDelegatesTaskWithActiveSession() = runBlocking {
        val session = ChatSession(id = "sess_plan_42", title = "Planner Linux Delegation")
        val terminal = FakeProotTerminalHandle(
            generation = 1L,
            rootfsPath = rootfsDir.absolutePath,
            initialWorkingDir = "/root",
            onExecute = { cmd ->
                ShellCommandResult(exitCode = 0, stdout = "python3.11 ($cmd)\n", stderr = "", durationMs = 3L)
            },
        )

        val prootExecutor = ScriptedPromptExecutor(
            mutableListOf(
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "p_call_1",
                            tool = "executeProotCommand",
                            content = """{"command":"python3 --version"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Assistant(
                            content = "容器内 Python 版本为 3.11。",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
            ),
        )
        val prootManager = ProotLinuxAgentLifecycleManager(
            agent = ProotLinuxAgent(
                promptExecutor = prootExecutor,
                model = testModel,
                visionToolSet = null,
            ),
            rootfsDirProvider = { rootfsDir },
            terminalFactory = { terminal },
        )

        val plannerExecutor = ScriptedPromptExecutor(
            mutableListOf(
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "plan_call_1",
                            tool = "executeProotLinuxTask",
                            content = """{"task":"检查 PRoot Linux 容器里的 Python 版本"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { prompt, _ -> waitForAgentTask(prompt, "waitLinuxTask") },
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "plan_finish",
                            tool = "finish",
                            content = """{"action":"final_exit","summary":"已通过 ProotLinuxAgent 确认容器内 Python 版本为 3.11。"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
            ),
        )

        val planner = PlannerAgent(
            promptExecutor = plannerExecutor,
            model = testModel,
            deviceActionAgent = null,
            prootLinuxLifecycleManager = prootManager,
            visionToolSet = null,
        )
        val plannerLifecycle = PlannerLifecycleManager(plannerAgent = planner)

        try {
            val planResult = plannerLifecycle.runPlan(
                goal = "检查 Linux 环境 Python 版本",
                session = session,
            )

            assertTrue(planResult.success)
            assertTrue(planResult.finalContent.contains("Python"))
            // 验证 Planner 拿到了 executeProotLinuxTask 与 executeProotLinuxTaskWithLlmApi 两个工具
            assertTrue(plannerExecutor.capturedToolNames.first().contains("executeProotLinuxTask"))
            assertTrue(plannerExecutor.capturedToolNames.first().contains("executeProotLinuxTaskWithLlmApi"))
            // 验证 Planner 的系统提示中包含 ProotLinuxAgent 使用指引
            val plannerSystem = plannerExecutor.capturedPrompts.first().messages
                .filterIsInstance<Message.System>()
                .joinToString("\n") { it.content }
            assertTrue(plannerSystem.contains("executeProotLinuxTask"))
            // 默认权限下应提示带 API Key 工具已关闭
            assertTrue(plannerSystem.contains("executeProotLinuxTaskWithLlmApi"))
            assertTrue(plannerSystem.contains("允许使用本机 LLM API Key"))
            // 验证会话 ID 透传到 ProotLinuxAgent 并生成建议工作目录 ~/sess_plan_42
            val prootUserPrompt = prootExecutor.capturedPrompts.first().messages
                .filterIsInstance<Message.User>()
                .joinToString("\n") { it.content }
            assertTrue(prootUserPrompt.contains("~/sess_plan_42"))
            assertEquals(listOf("python3 --version"), terminal.executedCommands)
        } finally {
            prootManager.close()
        }
    }

    @Test
    fun llmApiEnvResolver_buildsProviderRoleDefaults_sanitizesPrompt_andRedactsSecrets() = runBlocking {
        val openaiKey = "sk-openai-secret-998877"
        val deepseekKey = "sk-deepseek-secret-665544"
        val customKey = "sk-custom-secret-112233"

        val bundle = ProotLlmApiEnvResolver.resolve(
            requestedFilters = emptyList(),
            apiKeyLoader = { provider ->
                when (provider) {
                    LlmProvider.OPENAI -> openaiKey
                    LlmProvider.DEEPSEEK -> deepseekKey
                    LlmProvider.CUSTOM -> customKey
                    else -> null
                }
            },
            modelConfigsLoader = {
                mapOf(
                    ModelRole.COMPLEX_PLANNING to RoleModelSelection(LlmProvider.DEEPSEEK, "deepseek-reasoner"),
                    ModelRole.FAST_CHEAP to RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini"),
                )
            },
            customBaseUrlLoader = { "https://proxy.example.com/v1/models" },
        )

        assertFalse(bundle.isEmpty)
        assertEquals(openaiKey, bundle.envVars["OPENAI_API_KEY"])
        assertEquals("https://api.openai.com/v1", bundle.envVars["OPENAI_BASE_URL"])
        assertEquals(deepseekKey, bundle.envVars["DEEPSEEK_API_KEY"])
        assertEquals("https://api.deepseek.com/v1", bundle.envVars["DEEPSEEK_BASE_URL"])
        assertEquals(customKey, bundle.envVars["CUSTOM_LLM_API_KEY"])
        assertEquals("https://proxy.example.com/v1", bundle.envVars["CUSTOM_LLM_BASE_URL"])

        // 默认通用变量优先取 COMPLEX_PLANNING
        assertEquals("deepseek", bundle.envVars["MAID_LLM_PROVIDER"])
        assertEquals("deepseek-reasoner", bundle.envVars["MAID_LLM_MODEL"])
        assertEquals(deepseekKey, bundle.envVars["MAID_LLM_API_KEY"])
        assertEquals("https://api.deepseek.com/v1", bundle.envVars["MAID_LLM_BASE_URL"])
        assertEquals("gpt-4o-mini", bundle.envVars["MAID_LLM_FAST_CHEAP_MODEL"])

        // 安全提示词中绝不能包含任何明文 API Key
        val promptSection = bundle.toSafePromptSection()
        assertTrue(promptSection.contains("DEEPSEEK_API_KEY"))
        assertTrue(promptSection.contains("MAID_LLM_API_KEY"))
        assertFalse(promptSection.contains(openaiKey))
        assertFalse(promptSection.contains(deepseekKey))
        assertFalse(promptSection.contains(customKey))

        // 输出脱敏应把明文密钥替换为占位符
        val leakedOutput = "debug key=$deepseekKey and $openaiKey"
        val redacted = ProotLlmApiEnvResolver.redactSecrets(leakedOutput, bundle.secretValues)
        assertFalse(redacted.contains(deepseekKey))
        assertFalse(redacted.contains(openaiKey))
        assertTrue(redacted.contains(ProotLlmApiEnvResolver.REDACTED_SECRET_PLACEHOLDER))

        // 按服务商过滤
        val filtered = ProotLlmApiEnvResolver.resolve(
            requestedFilters = listOf("openai", "anthropic"),
            apiKeyLoader = { provider -> if (provider == LlmProvider.OPENAI) openaiKey else null },
            modelConfigsLoader = {
                mapOf(ModelRole.FAST_CHEAP to RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini"))
            },
            customBaseUrlLoader = { "" },
        )
        assertEquals(openaiKey, filtered.envVars["OPENAI_API_KEY"])
        assertEquals("openai", filtered.envVars["MAID_LLM_PROVIDER"])
        assertFalse(filtered.envVars.containsKey("DEEPSEEK_API_KEY"))
        assertTrue(filtered.missingRequestedFilters.contains("anthropic"))
    }

    @Test
    fun executeProotLinuxTaskWithLlmApi_isBlockedWhenSafetySwitchDisabled() = runBlocking {
        val terminal = FakeProotTerminalHandle(
            generation = 1L,
            rootfsPath = rootfsDir.absolutePath,
            initialWorkingDir = "/root",
        )
        val agentExecutor = ScriptedPromptExecutor(mutableListOf())
        val manager = ProotLinuxAgentLifecycleManager(
            agent = ProotLinuxAgent(
                promptExecutor = agentExecutor,
                model = testModel,
                visionToolSet = null,
            ),
            rootfsDirProvider = { rootfsDir },
            terminalFactory = { terminal },
            safetyConfigProvider = { ModelSafetyConfig.BALANCED }, // allowLocalLlmApiKeyAccess = false
        )

        try {
            val toolSet = manager.asToolSet()
            val taskId = toolSet.executeProotLinuxTaskWithLlmApi(
                task = "用 python 调用本地配置的 OpenAI API 总结日志",
            )
            val toolResult = withTimeout(5_000) { toolSet.waitTask(taskId) }
            assertTrue(toolResult.contains("Blocked by Model Safety Policy"))
            assertTrue(toolResult.contains("executeProotLinuxTaskWithLlmApi"))
            // 权限关闭时，不应调用 LLM，也不应向终端发送任何命令
            assertTrue(agentExecutor.capturedPrompts.isEmpty())
            assertTrue(terminal.executedCommands.isEmpty())
        } finally {
            manager.close()
        }
    }

    @Test
    fun executeProotLinuxTaskWithLlmApi_injectsRedactsAndUnsetsEnvVars_whenAllowed() = runBlocking {
        val secretKey = "sk-live-secret-token-778899"
        val createdTerminals = mutableListOf<FakeProotTerminalHandle>()
        val genCounter = AtomicLong(0L)
        val session = ChatSession(id = "sess_llm_env", title = "LLM Env Linux Task")

        val prootExecutor = ScriptedPromptExecutor(
            mutableListOf(
                // 第一次任务（带 LLM API 变量）：执行一条命令（模拟意外回显密钥）-> reset 终端 -> 再执行一条命令 -> 完成
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "llm_call_1",
                            tool = "executeProotCommand",
                            content = """{"command":"python3 call_llm.py"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "llm_call_2",
                            tool = "resetProotTerminal",
                            content = "{}",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "llm_call_3",
                            tool = "executeProotCommand",
                            content = """{"command":"python3 verify_after_reset.py"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Assistant(
                            content = "已借助注入的 LLM API 环境变量完成脚本调用。",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                // 第二次任务（常规无 API Key 任务）：不应触发任何 export
                { _, _ ->
                    listOf(
                        Message.Tool.Call(
                            id = "plain_call_1",
                            tool = "executeProotCommand",
                            content = """{"command":"echo plain_ok"}""",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
                { _, _ ->
                    listOf(
                        Message.Assistant(
                            content = "常规任务完成。",
                            metaInfo = ResponseMetaInfo.Empty,
                        ),
                    )
                },
            ),
        )

        val manager = ProotLinuxAgentLifecycleManager(
            agent = ProotLinuxAgent(
                promptExecutor = prootExecutor,
                model = testModel,
                visionToolSet = null,
            ),
            rootfsDirProvider = { rootfsDir },
            terminalFactory = { workDir ->
                FakeProotTerminalHandle(
                    generation = genCounter.incrementAndGet(),
                    rootfsPath = rootfsDir.absolutePath,
                    initialWorkingDir = workDir,
                    onExecute = { cmd ->
                        if (cmd.contains("call_llm.py")) {
                            // 模拟命令输出意外包含密钥明文，验证工具结果会脱敏
                            ShellCommandResult(
                                exitCode = 0,
                                stdout = "llm_reply=ok leaked=$secretKey\n",
                                stderr = "",
                                durationMs = 4L,
                            )
                        } else {
                            ShellCommandResult(exitCode = 0, stdout = "ok\n", stderr = "", durationMs = 2L)
                        }
                    },
                ).also { createdTerminals += it }
            },
            safetyConfigProvider = {
                ModelSafetyConfig.BALANCED.copy(allowLocalLlmApiKeyAccess = true)
            },
            llmApiEnvResolver = { filters ->
                ProotLlmApiEnvResolver.resolve(
                    requestedFilters = filters,
                    apiKeyLoader = { provider ->
                        if (provider == LlmProvider.DEEPSEEK) secretKey else null
                    },
                    modelConfigsLoader = {
                        mapOf(
                            ModelRole.COMPLEX_PLANNING to RoleModelSelection(
                                provider = LlmProvider.DEEPSEEK,
                                modelId = "deepseek-chat",
                            ),
                        )
                    },
                    customBaseUrlLoader = { "" },
                )
            },
        )

        try {
            val plannerExecutor = ScriptedPromptExecutor(
                mutableListOf(
                    { _, _ ->
                        listOf(
                            Message.Tool.Call(
                                id = "p_llm_1",
                                tool = "executeProotLinuxTaskWithLlmApi",
                                content = """{"task":"在 Linux 容器内运行 python3 call_llm.py 调用 DeepSeek","providers":["deepseek"]}""",
                                metaInfo = ResponseMetaInfo.Empty,
                            ),
                        )
                    },
                    { prompt, _ -> waitForAgentTask(prompt, "waitLinuxTask") },
                    { _, _ ->
                        listOf(
                            Message.Tool.Call(
                                id = "p_finish",
                                tool = "finish",
                                content = """{"action":"final_exit","summary":"已完成带 LLM API 变量的 Linux 任务。"}""",
                                metaInfo = ResponseMetaInfo.Empty,
                            ),
                        )
                    },
                ),
            )
            val planner = PlannerAgent(
                promptExecutor = plannerExecutor,
                model = testModel,
                deviceActionAgent = null,
                prootLinuxLifecycleManager = manager,
                visionToolSet = null,
            )
            val planOutcome = PlannerLifecycleManager(plannerAgent = planner).runPlan(
                goal = "运行需要调用本机 DeepSeek API 的 Linux 脚本",
                session = session,
            )
            assertTrue(planOutcome.success)

            // 验证 Planner 系统提示词反映权限已开启
            val plannerSystem = plannerExecutor.capturedPrompts.first().messages
                .filterIsInstance<Message.System>()
                .joinToString("\n") { it.content }
            assertTrue(plannerSystem.contains("已开启") || plannerSystem.contains("开启「允许使用本机 LLM API Key」"))

            // 验证 ProotLinuxAgent 的系统提示词只含变量名，绝不含密钥明文
            val prootSystem = prootExecutor.capturedPrompts.first().messages
                .filterIsInstance<Message.System>()
                .joinToString("\n") { it.content }
            assertTrue(prootSystem.contains("DEEPSEEK_API_KEY"))
            assertTrue(prootSystem.contains("MAID_LLM_API_KEY"))
            assertFalse(prootSystem.contains(secretKey))

            // 验证返回给 ProotLinuxAgent 的工具输出中已对意外回显的密钥做脱敏
            val toolResultMessages = prootExecutor.capturedPrompts[1].messages
                .filterIsInstance<Message.Tool.Result>()
                .joinToString("\n") { it.content }
            assertTrue(toolResultMessages.contains(ProotLlmApiEnvResolver.REDACTED_SECRET_PLACEHOLDER))
            assertFalse(toolResultMessages.contains(secretKey))

            // 验证第一代终端：先 export，再执行 call_llm.py
            assertEquals(2, createdTerminals.size)
            val term1Cmds = createdTerminals[0].executedCommands
            assertTrue(term1Cmds[0].startsWith("export "))
            assertTrue(term1Cmds[0].contains("DEEPSEEK_API_KEY='$secretKey'"))
            assertTrue(term1Cmds[0].contains("MAID_LLM_API_KEY='$secretKey'"))
            assertEquals("python3 call_llm.py", term1Cmds[1])

            // 验证 reset 后的第二代终端：自动重新 export -> 执行命令 -> 任务结束自动 unset
            val term2Cmds = createdTerminals[1].executedCommands
            assertTrue(term2Cmds[0].startsWith("export "))
            assertTrue(term2Cmds[0].contains("DEEPSEEK_API_KEY='$secretKey'"))
            assertEquals("python3 verify_after_reset.py", term2Cmds[1])
            assertTrue(term2Cmds[2].startsWith("unset "))
            assertTrue(term2Cmds[2].contains("DEEPSEEK_API_KEY"))
            assertTrue(term2Cmds[2].contains("MAID_LLM_API_KEY"))

            // 紧接着执行一次普通 executeProotLinuxTask，不应再注入任何 export / unset
            val cmdsBeforePlain = createdTerminals[1].executedCommands.size
            val plainResult = manager.runTask(
                task = "运行普通 echo",
                session = session,
            )
            assertTrue(plainResult.success)
            assertTrue(plainResult.injectedLlmEnvVars.isEmpty())
            val plainAddedCmds = createdTerminals[1].executedCommands.drop(cmdsBeforePlain)
            assertEquals(listOf("echo plain_ok"), plainAddedCmds)
        } finally {
            manager.close()
        }
    }
}
