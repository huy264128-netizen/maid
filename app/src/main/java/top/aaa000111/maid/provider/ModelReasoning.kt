package top.aaa000111.maid.provider

import androidx.annotation.StringRes
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.aaa000111.maid.R

/** DEFAULT 不发送参数，具体等级的有效范围由服务商和模型决定。 */
enum class ModelReasoningEffort(
    val id: String,
    @param:StringRes val titleRes: Int,
) {
    DEFAULT("default", R.string.settings_reasoning_default),
    NONE("none", R.string.settings_reasoning_none),
    MINIMAL("minimal", R.string.settings_reasoning_minimal),
    LOW("low", R.string.settings_reasoning_low),
    MEDIUM("medium", R.string.settings_reasoning_medium),
    HIGH("high", R.string.settings_reasoning_high),
    XHIGH("xhigh", R.string.settings_reasoning_xhigh),
    MAX("max", R.string.settings_reasoning_max);

    companion object {
        fun fromId(id: String?): ModelReasoningEffort =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 仅对已接通 effort 协议的服务商开放，不能从 supportsReasoning 推断参数兼容性。 */
fun RoleModelSelection.availableReasoningEfforts(): List<ModelReasoningEffort> {
    if (!isComplete || modelMetadata?.supportsReasoning == false) {
        return listOf(ModelReasoningEffort.DEFAULT)
    }
    return when (provider) {
        LlmProvider.OPENAI, LlmProvider.CHATGPT, LlmProvider.CUSTOM ->
            ModelReasoningEffort.entries
        LlmProvider.OPENROUTER ->
            ModelReasoningEffort.entries.filter { it != ModelReasoningEffort.MAX }
        else -> listOf(ModelReasoningEffort.DEFAULT)
    }
}

fun RoleModelSelection.withReasoning(executor: PromptExecutor): PromptExecutor {
    if (reasoningEffort == ModelReasoningEffort.DEFAULT ||
        reasoningEffort !in availableReasoningEfforts()
    ) return executor
    return ReasoningPromptExecutor(executor, requireNotNull(provider), reasoningEffort)
}

/**
 * 当前 Koog 快照将 additionalProperties 序列化为 additional_properties。
 * 只提升本项目设置的合法 effort 字段，其余内容维持原样；未来 SDK 已平铺时无需处理。
 */
internal fun normalizeReasoningRequest(body: String): String? {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val extra = root["additional_properties"] as? JsonObject ?: return null
    val output = root.toMutableMap()
    val remaining = extra.toMutableMap()
    for (key in listOf("reasoning_effort", "reasoning")) {
        val value = extra[key] ?: continue
        val effort = if (key == "reasoning_effort") value as? JsonPrimitive
            else (value as? JsonObject)?.get("effort") as? JsonPrimitive
        if (ModelReasoningEffort.entries.none {
                it != ModelReasoningEffort.DEFAULT && it.id == effort?.contentOrNull
            }
        ) continue
        output[key] = value
        remaining.remove(key)
    }
    if (remaining.size == extra.size) return null
    if (remaining.isEmpty()) output.remove("additional_properties")
    else output["additional_properties"] = JsonObject(remaining)
    return JsonObject(output).toString()
}

/** 按角色绑定包装共享执行器，不将某个角色的强度写入缓存或其他角色。 */
internal class ReasoningPromptExecutor(
    private val delegate: PromptExecutor,
    private val provider: LlmProvider,
    private val effort: ModelReasoningEffort,
) : PromptExecutor by delegate {
    private fun configure(prompt: Prompt): Prompt {
        if (effort == ModelReasoningEffort.DEFAULT) return prompt
        val additional = prompt.params.additionalProperties.orEmpty().toMutableMap()
        additional.remove("reasoning_effort")
        additional.remove("reasoning")
        if (provider == LlmProvider.CHATGPT || provider == LlmProvider.OPENROUTER) {
            additional["reasoning"] = JsonObject(mapOf("effort" to JsonPrimitive(effort.id)))
        } else {
            additional["reasoning_effort"] = JsonPrimitive(effort.id)
        }
        // 推理模型常常不接受 temperature；显式强度由模型使用默认采样设置。
        return prompt.withParams(prompt.params.copy(temperature = null, additionalProperties = additional))
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> = delegate.execute(configure(prompt), model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(configure(prompt), model, tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<List<Message.Response>> = delegate.executeMultipleChoices(configure(prompt), model, tools)
}
