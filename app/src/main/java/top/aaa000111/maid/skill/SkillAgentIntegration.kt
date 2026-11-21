package top.aaa000111.maid.skill

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.aaa000111.maid.learning.TaskExecutionContext

internal suspend fun withTaskSkills(registry: ToolRegistry): ToolRegistry {
    val task = currentCoroutineContext()[TaskExecutionContext] ?: return registry
    return registry + ToolRegistry {
        tools(SkillToolSet(task.recorder.skillRepository, task.skillContext))
    }
}

internal suspend fun taskSkillGuidance(includeCatalog: Boolean = false): String {
    val task = currentCoroutineContext()[TaskExecutionContext] ?: return ""
    val guidance = SkillToolSet.PROMPT_GUIDANCE
    if (!includeCatalog) return guidance

    val skills = SkillResolver.resolve(task.skillContext)
    val catalog = buildJsonArray {
        skills.forEach { skill ->
            add(buildJsonObject {
                put("id", skill.id)
                put("description", skill.description)
            })
        }
    }
    return "$guidance\nskill_catalog: $catalog\n"
}
