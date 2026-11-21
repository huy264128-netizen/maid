package top.aaa000111.maid.skill

/** 确定性检索：显式技能 ID 优先，再按名称/描述匹配；不自动把完整库塞入提示词。 */
object SkillResolver {
    fun resolve(context: SkillExecutionContext, query: String = "", limit: Int = 20): List<SkillDescriptor> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return context.catalog.values.map { skill ->
            val text = (skill.name + " " + skill.description).lowercase()
            val score = if (query.trim() == skill.id) Int.MAX_VALUE else terms.count { text.contains(it) }
            skill to score
        }.filter { terms.isEmpty() || it.second > 0 }
            .sortedWith(compareByDescending<Pair<SkillDescriptor, Int>> { it.second }.thenBy { it.first.id })
            .take(limit.coerceIn(1, 50)).map { it.first }
    }
}
