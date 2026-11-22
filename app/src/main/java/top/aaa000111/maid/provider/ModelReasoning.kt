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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

/** 已选模型允许手动配置，具体档位是否有效由服务商校验。 */
fun RoleModelSelection.availableReasoningEfforts(): List<ModelReasoningEffort> {
    if (!isComplete) return listOf(ModelReasoningEffort.DEFAULT)
    return when (provider) {
        LlmProvider.ANTHROPIC -> ModelReasoningEffort.entries.filter {
            it != ModelReasoningEffort.NONE && it != ModelReasoningEffort.MINIMAL
        }
        LlmProvider.GEMINI -> ModelReasoningEffort.entries.filter {
            it != ModelReasoningEffort.NONE && it != ModelReasoningEffort.MAX
        }
        LlmProvider.OPENROUTER -> ModelReasoningEffort.entries.filter { it != ModelReasoningEffort.MAX }
        else -> ModelReasoningEffort.entries
    }
}

fun RoleModelSelection.withReasoning(executor: PromptExecutor): PromptExecutor {
    if (reasoningEffort == ModelReasoningEffort.DEFAULT ||
        reasoningEffort !in availableReasoningEfforts()
    ) return executor
    return ReasoningPromptExecutor(executor, requireNotNull(provider), reasoningEffort)
}

/**
 * Koog 的部分客户端把额外参数包装在 additional_properties 或 additionalProperties 中。
 * 将本项目配置的思考强度移到各 API 所需的位置，保留其他参数。
 */
internal fun normalizeReasoningRequest(body: String): String? {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val output = root.toMutableMap()
    promoteReasoningFields(output, listOf("reasoning_effort", "reasoning", "output_config"))
    val generationConfig = root["generationConfig"] as? JsonObject
    if (generationConfig != null) {
        val config = generationConfig.toMutableMap()
        promoteReasoningFields(config, listOf("thinkingConfig"))
        output["generationConfig"] = JsonObject(config)
    }
    return JsonObject(output).takeIf { it != root }?.toString()
}

private fun promoteReasoningFields(
    output: MutableMap<String, JsonElement>,
    fields: List<String>,
) {
    for (extraKey in listOf("additional_properties", "additionalProperties")) {
        val extra = output[extraKey] as? JsonObject ?: continue
        val remaining = extra.toMutableMap()
        for (key in fields) {
            val value = extra[key] ?: continue
            val effort = when (key) {
                "reasoning_effort" -> value as? JsonPrimitive
                "thinkingConfig" -> (value as? JsonObject)?.get("thinkingLevel") as? JsonPrimitive
                else -> (value as? JsonObject)?.get("effort") as? JsonPrimitive
            }
            if (ModelReasoningEffort.entries.none {
                    it != ModelReasoningEffort.DEFAULT && it.id.equals(effort?.contentOrNull, ignoreCase = true)
                }
            ) continue
            output[key] = if (key == "thinkingConfig" && value is JsonObject) {
                JsonObject((output[key] as? JsonObject).orEmpty() + value)
            } else value
            remaining.remove(key)
        }
        if (remaining.size == extra.size) continue
        if (remaining.isEmpty()) output.remove(extraKey)
        else output[extraKey] = JsonObject(remaining)
    }
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
        val effortValue = JsonPrimitive(effort.id)
        when (provider) {
            LlmProvider.CHATGPT, LlmProvider.OPENROUTER ->
                additional["reasoning"] = JsonObject(mapOf("effort" to effortValue))
            LlmProvider.ANTHROPIC ->
                additional["output_config"] = JsonObject(
                    (additional["output_config"] as? JsonObject).orEmpty() + ("effort" to effortValue),
                )
            LlmProvider.GEMINI ->
                additional["thinkingConfig"] = JsonObject(
                    (additional["thinkingConfig"] as? JsonObject).orEmpty() +
                        ("thinkingLevel" to JsonPrimitive(effort.name)),
                )
            else -> additional["reasoning_effort"] = effortValue
        }
        // 推理模型常常不接受 temperature；显式强度由模型使用默认采样设置。
        return prompt.withParams(prompt.params.copy(
            temperature = null,
            thinkingBudget = if (provider == LlmProvider.GEMINI) null else prompt.params.thinkingBudget,
            additionalProperties = additional,
        ))
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
