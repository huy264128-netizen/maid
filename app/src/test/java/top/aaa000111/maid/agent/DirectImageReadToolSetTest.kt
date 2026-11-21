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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.AndroidToolSet
import top.aaa000111.maid.androidtool.AndroidToolsetDispatcher
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.ChatSessionContext
import top.aaa000111.maid.chat.SessionAttachmentStorage
import top.aaa000111.maid.chat.UserInput
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.search.HttpFetchResponse
import top.aaa000111.maid.search.RuleOnlySearchResultFilter
import top.aaa000111.maid.search.SearchEngineRouter
import top.aaa000111.maid.search.SearchHttpClient
import top.aaa000111.maid.search.WebPageFetcher
import top.aaa000111.maid.search.engines.WikipediaSearchEngine
import top.aaa000111.maid.settings.ModelSafetyConfig
import java.io.File

class DirectImageReadToolSetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val samplePngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47,
        0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D,
    )

    private val multimodalModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gpt-4o",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.Vision.Image,
        ),
        contextLength = 128_000L,
    )

    @After
    fun tearDown() {
        SessionAttachmentStorage.resetForTest()
    }

    private class ScriptedExecutor(
        private val script: (callIndex: Int, prompt: Prompt, tools: List<ToolDescriptor>) -> List<Message.Response>,
    ) : PromptExecutor {
        val capturedPrompts = mutableListOf<Prompt>()
        val capturedTools = mutableListOf<List<ToolDescriptor>>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> {
            val idx = capturedPrompts.size
            capturedPrompts += prompt
            capturedTools += tools
            return script(idx, prompt, tools)
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = emptyFlow()

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw UnsupportedOperationException()
    }

    @Test
    fun resolveImageAttachment_supportsSessionInternalProotAndStandardUris() = runBlocking {
        val internalRoot = tempFolder.newFolder("internal_files")
        SessionAttachmentStorage.setCustomInternalRootDirForTest(internalRoot)

        val externalImage = tempFolder.newFile("dashboard_shot.png").apply { writeBytes(samplePngBytes) }
        val session = ChatSession(id = "session_vision_1")
        val registered = session.registerAttachment(
            uri = externalImage.toURI().toString(),
            fileName = "dashboard_shot.png",
            description = "仪表盘截图",
            isImage = true,
        )

        val internalSub = File(internalRoot, "session_vision_1/direct_internal.png").apply {
            parentFile?.mkdirs()
            writeBytes(samplePngBytes)
        }
        val outsideSecret = tempFolder.newFile("outside_secret.png").apply { writeBytes(samplePngBytes) }

        val rootfs = tempFolder.newFolder("fake_rootfs")
        val prootImage = File(rootfs, "root/session_vision_1/plot.png").apply {
            parentFile?.mkdirs()
            writeBytes(samplePngBytes)
        }
        assertTrue(prootImage.isFile)
        assertTrue(internalSub.isFile)
        assertTrue(outsideSecret.isFile)

        withContext(ChatSessionContext(session)) {
            // 1. 会话附件 ID（img_1）
            val byId = VisionToolSet.resolveImageAttachment(registered.id)
            assertNotNull(byId)
            assertEquals("image/png", byId!!.mimeType)
            assertEquals(samplePngBytes.toList(), (byId.content as AttachmentContent.Binary.Bytes).asBytes().toList())

            // 2. 会话附件文件名
            val byName = VisionToolSet.resolveImageAttachment("dashboard_shot.png")
            assertNotNull(byName)
            assertEquals("image/png", byName!!.mimeType)

            // 3. internal:// 协议 & 会话内部相对路径
            val byInternalUri = VisionToolSet.resolveImageAttachment("internal://session_vision_1/direct_internal.png")
            assertNotNull(byInternalUri)
            val bySessionRel = VisionToolSet.resolveImageAttachment("direct_internal.png")
            assertNotNull(bySessionRel)

            // 4. internal:// 阻止路径穿越
            val escaped = VisionToolSet.resolveImageAttachment("internal://../outside_secret.png")
            assertNull(escaped)

            // 5. proot://、~/... 与容器工作目录相对路径
            val byProotScheme = VisionToolSet.resolveImageAttachment(
                rawIdOrUri = "proot:///root/session_vision_1/plot.png",
                rootfsDirOverride = rootfs,
            )
            assertNotNull(byProotScheme)

            val byTilde = VisionToolSet.resolveImageAttachment(
                rawIdOrUri = "~/session_vision_1/plot.png",
                rootfsDirOverride = rootfs,
            )
            assertNotNull(byTilde)

            val byProotRel = VisionToolSet.resolveImageAttachment(
                rawIdOrUri = "plot.png",
                prootDefaultBaseDirPosix = "/root/session_vision_1",
                rootfsDirOverride = rootfs,
            )
            assertNotNull(byProotRel)

            // 6. 标准 https:// URI
            val byHttps = VisionToolSet.resolveImageAttachment("https://example.org/diagram.webp")
            assertNotNull(byHttps)
            assertEquals("image/webp", byHttps!!.mimeType)
            assertTrue(byHttps.content is AttachmentContent.URL)
        }
    }

    @Test
    fun chatAgent_directMultimodalModel_canCallReadImageAndReceiveImageDirectlyInPrompt() = runBlocking {
        val internalRoot = tempFolder.newFolder("chat_internal")
        SessionAttachmentStorage.setCustomInternalRootDirForTest(internalRoot)

        val localShot = tempFolder.newFile("chart_local.png").apply { writeBytes(samplePngBytes) }
        val session = ChatSession(id = "session_direct_chat")
        val att = session.registerAttachment(
            uri = localShot.toURI().toString(),
            fileName = "chart_local.png",
            description = "实验结果图",
            isImage = true,
        )

        val executor = ScriptedExecutor { idx, _, _ ->
            if (idx == 0) {
                listOf(
                    Message.Tool.Call(
                        id = "call_read_1",
                        tool = DirectImageReadToolSet.TOOL_NAME,
                        content = """{"imageUri":"${att.id}","focus":"查看峰值"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(
                    Message.Assistant(
                        content = "我已直接通过原生视觉看到图表峰值。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val chatAgent = ChatAgent(
            promptExecutor = executor,
            model = multimodalModel,
            provider = LlmProvider.OPENAI,
            defaultSession = session,
            enableMemory = false,
            directImageInputProvider = { true },
        )

        val reply = chatAgent.chat(UserInput(text = "帮我看看刚才传的图 ${att.id}"))
        assertEquals("我已直接通过原生视觉看到图表峰值。", reply)

        // 原生多模态模式下挂载 readImage，不挂载二级 analyzeImage
        val firstTools = executor.capturedTools.first().map { it.name }
        assertTrue(DirectImageReadToolSet.TOOL_NAME in firstTools)
        assertFalse("analyzeImage" in firstTools)

        // 第二轮请求中，Message.Tool.Result 紧跟着带 Attachment.Image 的 Message.User
        assertEquals(2, executor.capturedPrompts.size)
        val secondMessages = executor.capturedPrompts[1].messages
        val toolResultIdx = secondMessages.indexOfLast { it is Message.Tool.Result && it.tool == DirectImageReadToolSet.TOOL_NAME }
        assertTrue(toolResultIdx >= 0)
        val injectedUserMsg = secondMessages[toolResultIdx + 1] as Message.User
        assertTrue(injectedUserMsg.content.contains("查看峰值"))
        val injectedImg = injectedUserMsg.attachments.single() as Attachment.Image
        assertEquals("image/png", injectedImg.mimeType)
        assertEquals(samplePngBytes.toList(), (injectedImg.content as AttachmentContent.Binary.Bytes).asBytes().toList())
    }

    internal class FakeScreenCaptureToolSet(
        private val screenshotPath: String,
    ) : AndroidToolSet() {
        override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(AndroidPrivilege.ACCESSIBILITY)

        @Tool
        @LLMDescription("Capture current screen to a PNG file")
        fun captureScreenshot(): String = "filePath=$screenshotPath"
    }

    @Test
    fun deviceActionAgent_directMultimodalModel_readsScreenshotFileDirectly() = runBlocking {
        val screenshotFile = tempFolder.newFile("screen_123.png").apply { writeBytes(samplePngBytes) }

        val fakeScreenTool = FakeScreenCaptureToolSet(screenshotFile.absolutePath)
        val dispatcher = AndroidToolsetDispatcher(
            toolSets = listOf(fakeScreenTool),
            privilegeChecker = { setOf(AndroidPrivilege.ACCESSIBILITY) },
        )

        val executor = ScriptedExecutor { idx, _, _ ->
            when (idx) {
                0 -> listOf(
                    Message.Tool.Call(
                        id = "call_shot",
                        tool = "captureScreenshot",
                        content = "{}",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
                1 -> listOf(
                    Message.Tool.Call(
                        id = "call_read_shot",
                        tool = DirectImageReadToolSet.TOOL_NAME,
                        content = """{"imageUri":"${screenshotFile.absolutePath}","focus":"定位设置按钮"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
                else -> listOf(
                    Message.Assistant(
                        content = "已直接看到截屏中的设置按钮。",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            }
        }

        val deviceAgent = DeviceActionAgent(
            promptExecutor = executor,
            model = multimodalModel,
            provider = LlmProvider.OPENAI,
            dispatcher = dispatcher,
            safetyConfigProvider = { ModelSafetyConfig.STRICT },
            directImageInputProvider = { true },
        )

        val result = deviceAgent.execute(task = "截屏并观察屏幕上的设置按钮")
        assertTrue(result.success)
        assertEquals("已直接看到截屏中的设置按钮。", result.summary)
        assertEquals(listOf("captureScreenshot", "readImage"), result.executedTools)

        val registeredNames = executor.capturedTools.first().map { it.name }
        assertTrue("readImage" in registeredNames)
        assertFalse("analyzeImage" in registeredNames)

        val finalPromptMessages = executor.capturedPrompts[2].messages
        val toolResultIdx = finalPromptMessages.indexOfLast { it is Message.Tool.Result && it.tool == "readImage" }
        assertTrue(toolResultIdx >= 0)
        val imageMsg = finalPromptMessages[toolResultIdx + 1] as Message.User
        assertEquals(1, imageMsg.attachments.size)
        assertTrue(imageMsg.attachments.single() is Attachment.Image)
    }

    @Test
    fun researchAgent_registersReadImageOnlyForNativeMultimodalModels() = runBlocking {
        val fakeHttp = object : SearchHttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMillis: Long,
                maxBodyBytes: Long,
            ): HttpFetchResponse = HttpFetchResponse(200, url, "{}")

            override fun close() = Unit
        }
        val router = SearchEngineRouter(
            engines = listOf(WikipediaSearchEngine(fakeHttp, RuleOnlySearchResultFilter)),
            defaultTinyModelFilter = RuleOnlySearchResultFilter,
        )
        val fetcher = WebPageFetcher(fakeHttp)

        // 1. 原生多模态模型 -> 注册 readImage 并能直接读取 https 图片 URL
        val multimodalExecutor = ScriptedExecutor { idx, _, _ ->
            if (idx == 0) {
                listOf(
                    Message.Tool.Call(
                        id = "call_res_img",
                        tool = "readImage",
                        content = """{"imageUri":"https://example.org/arch.png"}""",
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                )
            } else {
                listOf(Message.Assistant(content = "架构图分析完成。", metaInfo = ResponseMetaInfo.Empty))
            }
        }
        val multimodalResearch = ResearchAgent(
            promptExecutor = multimodalExecutor,
            model = multimodalModel,
            router = router,
            pageFetcher = fetcher,
            directImageInputProvider = { true },
        )
        val res = multimodalResearch.research(topic = "分析架构图")
        assertTrue(res.success)
        assertTrue(multimodalExecutor.capturedTools.first().any { it.name == "readImage" })
        val secondMsgs = multimodalExecutor.capturedPrompts[1].messages
        val imgUser = secondMsgs.last { it is Message.User } as Message.User
        val urlImg = imgUser.attachments.single() as Attachment.Image
        assertTrue(urlImg.content is AttachmentContent.URL)

        // 2. 非原生多模态模型 -> 不注册 readImage
        val textOnlyExecutor = ScriptedExecutor { _, _, _ ->
            listOf(Message.Assistant(content = "纯文本调研完成。", metaInfo = ResponseMetaInfo.Empty))
        }
        val textOnlyResearch = ResearchAgent(
            promptExecutor = textOnlyExecutor,
            model = multimodalModel,
            router = router,
            pageFetcher = fetcher,
            directImageInputProvider = { false },
        )
        textOnlyResearch.research(topic = "纯文本调研")
        assertFalse(textOnlyExecutor.capturedTools.first().any { it.name == "readImage" })
    }
}
