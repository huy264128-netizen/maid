package top.aaa000111.maid.skill

data class SkillDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val versionHash: String,
    val source: String,
)

/** 元数据快照在任务开始时固定；支持文件也从同一版本读取。 */
class SkillExecutionContext(catalog: Map<String, SkillDescriptor>) {
    val catalog: Map<String, SkillDescriptor> = java.util.Collections.unmodifiableMap(LinkedHashMap(catalog))
}

object SkillFrontmatter {
    fun parse(text: String): Pair<String, String> {
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").lines()
        require(lines.firstOrNull() == "---") { "SKILL.md requires frontmatter" }
        val end = lines.drop(1).indexOf("---") + 1
        require(end > 1 && lines.drop(end + 1).any { it.isNotBlank() }) { "Skill body is empty" }
        // P1 支持明确的单行 name/description；多行 YAML 显式拒绝，不进行猜测。
        val values = lines.subList(1, end).filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val separator = line.indexOf(':')
            require(separator > 0) { "Unsupported frontmatter" }
            line.substring(0, separator).trim() to line.substring(separator + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        }
        val name = values["name"].orEmpty()
        val description = values["description"].orEmpty()
        require(name.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "Invalid skill name" }
        require(description.isNotBlank() && description.length <= 512 && description !in listOf("|", ">")) {
            "Use a single-line skill description (1..512 chars)"
        }
        return name to description
    }
}
