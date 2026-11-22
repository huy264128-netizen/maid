package top.aaa000111.maid.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

data class ModelConfigGroup(
    val id: String,
    val name: String,
    val selections: Map<ModelRole, RoleModelSelection>,
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}

data class ModelGroups(
    val groups: List<ModelConfigGroup> = emptyList(),
    val activeId: String = ModelConfigGroup.DEFAULT_ID,
)

internal fun List<ModelConfigGroup>.replaceGroup(group: ModelConfigGroup): List<ModelConfigGroup> =
    if (any { it.id == group.id }) map { if (it.id == group.id) group else it }
    else this + group

internal object ModelGroupJson {
    fun encode(groups: List<ModelConfigGroup>): String = JsonArray(groups.map { group ->
        buildJsonObject {
            put("id", JsonPrimitive(group.id))
            put("name", JsonPrimitive(group.name))
            put("roles", buildJsonObject {
                group.selections.forEach { (role, selection) ->
                    put(role.keyPrefix, Json.parseToJsonElement(RoleModelHistory.encode(listOf(selection))))
                }
            })
        }
    }).toString()

    fun decode(raw: String?): List<ModelConfigGroup> {
        val items = runCatching { Json.parseToJsonElement(raw.orEmpty()) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val roles = obj["roles"] as? JsonObject ?: return@mapNotNull null
            ModelConfigGroup(
                id,
                (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                ModelRole.entries.associateWith { role ->
                    RoleModelHistory.decode(roles[role.keyPrefix]?.toString()).firstOrNull()
                        ?: RoleModelSelection()
                },
            )
        }.distinctBy { it.id }
    }
}
