package top.aaa000111.maid.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 最近使用的模型排在前面；服务商的不同自定义端点独立记忆。历史只保存配置，不含凭据。 */
internal object RoleModelHistory {
    fun remember(history: List<RoleModelSelection>, selection: RoleModelSelection): List<RoleModelSelection> {
        if (!selection.isComplete) return history
        return listOf(selection) + history.filterNot {
            it.providerCacheKey == selection.providerCacheKey &&
                it.normalizedModelId == selection.normalizedModelId
        }
    }

    fun selectProvider(
        current: RoleModelSelection,
        history: List<RoleModelSelection>,
        target: ModelProviderTarget?,
    ): RoleModelSelection {
        if (target == null) return RoleModelSelection()
        if (current.providerCacheKey == target.cacheKey) return current
        return history.firstOrNull { it.providerCacheKey == target.cacheKey }
            ?: RoleModelSelection(provider = target.provider, customProviderId = target.customProviderId)
    }

    fun selectModel(
        current: RoleModelSelection,
        history: List<RoleModelSelection>,
        option: LlmModelOption,
    ): RoleModelSelection {
        require(current.provider == option.provider)
        val saved = remember(history, current).firstOrNull {
            it.providerCacheKey == current.providerCacheKey && it.normalizedModelId == option.modelId.trim()
        }
        val selection = current.copy(
            modelId = option.modelId.trim(),
            modelMetadata = option.metadata.takeUnless { it.isEmpty } ?: saved?.modelMetadata,
            reasoningEffort = saved?.reasoningEffort ?: ModelReasoningEffort.DEFAULT,
        )
        return if (selection.reasoningEffort in selection.availableReasoningEfforts()) selection
            else selection.copy(reasoningEffort = ModelReasoningEffort.DEFAULT)
    }

    fun encode(history: List<RoleModelSelection>): String = JsonArray(
        history.filter { it.isComplete }.map { selection ->
            buildJsonObject {
                put("provider", JsonPrimitive(requireNotNull(selection.provider).id))
                put("model", JsonPrimitive(selection.normalizedModelId))
                selection.normalizedCustomProviderId?.let { put("custom_provider_id", JsonPrimitive(it)) }
                if (selection.reasoningEffort != ModelReasoningEffort.DEFAULT) {
                    put("reasoning_effort", JsonPrimitive(selection.reasoningEffort.id))
                }
                selection.modelMetadata?.let { meta ->
                    meta.contextLength?.let { put("context_length", JsonPrimitive(it)) }
                    meta.supportsVision?.let { put("vision", JsonPrimitive(it)) }
                    meta.supportsTools?.let { put("tools", JsonPrimitive(it)) }
                    meta.supportsReasoning?.let { put("reasoning", JsonPrimitive(it)) }
                }
            }
        },
    ).toString()

    fun decode(raw: String?): List<RoleModelSelection> {
        val items = runCatching { Json.parseToJsonElement(raw ?: "") as? JsonArray }.getOrNull()
            ?: return emptyList()
        return items.mapNotNull { item ->
            runCatching {
                val obj = item as? JsonObject ?: return@mapNotNull null
                fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
                fun flag(key: String) = (obj[key] as? JsonPrimitive)?.booleanOrNull
                val provider = LlmProvider.entries.firstOrNull { it.id == text("provider") }
                    ?: return@mapNotNull null
                val model = text("model")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RoleModelSelection(
                    provider = provider,
                    modelId = model,
                    customProviderId = text("custom_provider_id"),
                    modelMetadata = LlmModelMetadata(
                        contextLength = (obj["context_length"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                        supportsVision = flag("vision"),
                        supportsTools = flag("tools"),
                        supportsReasoning = flag("reasoning"),
                    ).takeUnless { it.isEmpty },
                    reasoningEffort = ModelReasoningEffort.fromId(text("reasoning_effort")),
                )
            }.getOrNull()
        }.distinctBy { it.providerCacheKey to it.normalizedModelId }
    }
}
