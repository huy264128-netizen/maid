package top.aaa000111.maid.skill

import top.aaa000111.maid.prompt.PromptResources
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import top.aaa000111.maid.learning.TaskExecutionContext

/** 每次 Agent 运行只注册读取接口；权限由原有 Android 工具链决定。 */
class SkillToolSet(
    private val repository: SkillRepository,
    private val context: SkillExecutionContext,
) : ToolSet {
    @Tool
    @LLMDescription("List or search reusable skills by ID, name or description. Returns metadata only; read the relevant skill before following it.")
    fun listSkills(
        @LLMDescription("Optional search text or exact skill ID; empty lists available skills") query: String = "",
    ): String = buildJsonArray {
        SkillResolver.resolve(context, query).forEach { skill -> add(buildJsonObject {
            put("id", skill.id); put("description", skill.description); put("version", skill.versionHash)
        }) }
    }.toString()

    @Tool
    @LLMDescription("Read a skill or a supporting text file from this task's fixed version. Skills do not grant permissions. This tool never runs scripts.")
    suspend fun readSkill(
        @LLMDescription("Exact skill ID returned by listSkills") skillId: String,
        @LLMDescription("SKILL.md or a relative supporting path, such as references/checklist.md") filePath: String = "SKILL.md",
    ): String {
        val descriptor = context.catalog[skillId] ?: return "Skill not found in this task's catalog."
        val content = repository.read(skillId, descriptor.versionHash, filePath)
        currentCoroutineContext()[TaskExecutionContext]?.recordSkill(descriptor, filePath)
        return buildJsonObject {
            put("skillId", descriptor.id); put("version", descriptor.versionHash)
            put("filePath", filePath); put("content", content)
        }.toString()
    }

    companion object {
        val PROMPT_GUIDANCE: String
            get() = "\n" + PromptResources.read("skill/usage.md")
    }
}
