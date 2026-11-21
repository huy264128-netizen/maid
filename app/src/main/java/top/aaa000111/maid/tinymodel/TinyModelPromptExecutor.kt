package top.aaa000111.maid.tinymodel

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 将内部 [TinyModel] 桥接为 Koog [PromptExecutor] 的轻量适配器，供需要走 Koog 接口的内部事务复用。
 *
 * 仅处理纯文本内部对话，不对外暴露给用户配置，也不执行工具调用或审核接口。
 */
internal class TinyModelPromptExecutor(
    private val tinyModel: TinyModel,
) : PromptExecutor {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        val (systemInstruction, userPrompt) = extractSystemAndUserPrompt(prompt)
        val sampler = buildSamplerFromPrompt(prompt)
        val result = tinyModel.generate(
            prompt = userPrompt,
            systemPrompt = systemInstruction,
            sampler = sampler,
        )
        return listOf(
            Message.Assistant(
                content = result.text,
                metaInfo = ResponseMetaInfo.Empty,
                finishReason = "stop",
            ),
        )
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        val (systemInstruction, userPrompt) = extractSystemAndUserPrompt(prompt)
        val sampler = buildSamplerFromPrompt(prompt)
        tinyModel.generateStream(
            prompt = userPrompt,
            systemPrompt = systemInstruction,
            sampler = sampler,
        ).collect { chunk ->
            emit(StreamFrame.Append(text = chunk))
        }
        emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
    }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult {
        throw UnsupportedOperationException("TinyModel does not support moderation API")
    }

    private fun buildSamplerFromPrompt(prompt: Prompt): TinyModelSamplerConfig {
        val base = tinyModel.config.defaultSampler
        val temp = prompt.params.temperature
        return if (temp != null && temp >= 0.0) {
            base.copy(temperature = temp)
        } else {
            base
        }
    }

    internal companion object {
        internal fun extractSystemAndUserPrompt(prompt: Prompt): Pair<String?, String> {
            val systemParts = mutableListOf<String>()
            val transcriptParts = mutableListOf<String>()
            var lastSingleUserMessage: String? = null
            var nonSystemMessageCount = 0

            prompt.messages.forEach { message ->
                val text = message.content.trim()
                if (text.isEmpty()) return@forEach
                when (message) {
                    is Message.System -> systemParts += text
                    is Message.User -> {
                        nonSystemMessageCount++
                        lastSingleUserMessage = text
                        transcriptParts += "User: $text"
                    }
                    is Message.Assistant -> {
                        nonSystemMessageCount++
                        transcriptParts += "Assistant: $text"
                    }
                    else -> {
                        nonSystemMessageCount++
                        transcriptParts += "${message.role.name}: $text"
                    }
                }
            }

            val systemInstruction = systemParts.joinToString("\n\n").takeIf { it.isNotEmpty() }
            val userPrompt = when {
                nonSystemMessageCount == 1 && lastSingleUserMessage != null -> lastSingleUserMessage
                transcriptParts.isNotEmpty() -> transcriptParts.joinToString("\n\n")
                else -> error("Prompt must contain at least one non-empty non-system message")
            }
            return systemInstruction to userPrompt
        }
    }
}
