package top.aaa000111.maid.skill

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import kotlinx.coroutines.currentCoroutineContext
import top.aaa000111.maid.learning.TaskExecutionContext

internal suspend fun withTaskSkills(registry: ToolRegistry): ToolRegistry {
    val task = currentCoroutineContext()[TaskExecutionContext] ?: return registry
    return registry + ToolRegistry {
        tools(SkillToolSet(task.recorder.skillRepository, task.skillContext))
    }
}

internal suspend fun taskSkillGuidance(): String =
    if (currentCoroutineContext()[TaskExecutionContext] == null) "" else SkillToolSet.PROMPT_GUIDANCE
