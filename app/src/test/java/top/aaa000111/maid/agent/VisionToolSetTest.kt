package top.aaa000111.maid.agent

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
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.provider.LlmProvider
import java.util.Base64

class VisionToolSetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val visionModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gpt-4o-mini",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Vision.Image,
        ),
        contextLength = 128_000L,
    )

    private class FakeVisionExecutor(
        private val replyText: String = "图中显示了一个蓝色按钮，文字为“确认”。",
    ) : PromptExecutor {
        val capturedPrompts = mutableListOf<Prompt>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> {
            capturedPrompts += prompt
            return listOf(Message.Assistant(content = replyText, metaInfo = ResponseMetaInfo.Empty))
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
    fun analyzeImage_readsLocalFileUriAndSendsImageAttachmentToFastVisionModel() = runBlocking {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
        )
        val imageFile = tempFolder.newFile("screen_capture.png")
        imageFile.writeBytes(pngBytes)

        val executor = FakeVisionExecutor()
        val toolSet = VisionToolSet(
            visionBindingProvider = {
                ChatModelBinding(
                    executor = executor,
                    model = visionModel,
                    provider = LlmProvider.OPENAI,
                )
            },
        )

        val result = toolSet.analyzeImage(
            imageUri = imageFile.toURI().toString(),
            question = "图里的按钮写了什么字？",
        )

        assertEquals("图中显示了一个蓝色按钮，文字为“确认”。", result)
        assertEquals(1, executor.capturedPrompts.size)
        val userMessage = executor.capturedPrompts.single().messages
            .filterIsInstance<Message.User>()
            .single()
        assertEquals("图里的按钮写了什么字？", userMessage.content)
        val attachment = userMessage.attachments.single() as Attachment.Image
        assertEquals("image/png", attachment.mimeType)
        assertEquals("png", attachment.format)
        assertTrue(attachment.content is AttachmentContent.Binary.Bytes)
        assertEquals(
            pngBytes.toList(),
            (attachment.content as AttachmentContent.Binary.Bytes).asBytes().toList(),
        )
    }

    @Test
    fun analyzeImage_supportsHttpsUrlAndDataUriAndHandlesErrorsGracefully() = runBlocking {
        val executor = FakeVisionExecutor(replyText = "远程与内联图片均已解析")
        val toolSet = VisionToolSet(
            visionBindingProvider = {
                ChatModelBinding(
                    executor = executor,
                    model = visionModel,
                    provider = LlmProvider.OPENAI,
                )
            },
        )

        val urlResult = toolSet.analyzeImage(
            imageUri = "https://example.com/assets/chart.webp?v=1",
            question = "提取图表标题",
        )
        assertEquals("远程与内联图片均已解析", urlResult)
        val urlAttachment = executor.capturedPrompts[0].messages
            .filterIsInstance<Message.User>()
            .single()
            .attachments
            .single() as Attachment.Image
        assertEquals("image/webp", urlAttachment.mimeType)
        assertTrue(urlAttachment.content is AttachmentContent.URL)

        val base64 = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        val dataResult = toolSet.analyzeImage(
            imageUri = "data:image/jpeg;base64,$base64",
            question = "描述图片",
        )
        assertEquals("远程与内联图片均已解析", dataResult)
        val dataAttachment = executor.capturedPrompts[1].messages
            .filterIsInstance<Message.User>()
            .single()
            .attachments
            .single() as Attachment.Image
        assertEquals("image/jpeg", dataAttachment.mimeType)

        val missingResult = toolSet.analyzeImage(
            imageUri = "/nonexistent/path/missing.png",
            question = "描述图片",
        )
        assertTrue(missingResult.contains("无法读取或解析图片 URI"))

        val unconfiguredToolSet = VisionToolSet(
            visionBindingProvider = { error("No chat model configured for roles: FAST_VISION") },
        )
        val unconfiguredResult = unconfiguredToolSet.analyzeImage(
            imageUri = "https://example.com/a.png",
            question = "描述图片",
        )
        assertTrue(unconfiguredResult.contains("快速视觉模型不可用"))
    }
}
