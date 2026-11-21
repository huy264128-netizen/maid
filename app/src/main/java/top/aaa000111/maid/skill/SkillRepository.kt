package top.aaa000111.maid.skill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import top.aaa000111.maid.learning.atomicWrite
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.TreeMap

/** 受信任应用侧安装入口。模型只获得 SkillToolSet 的只读操作。 */
class SkillRepository(private val root: Path) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun install(
        files: Map<String, String>,
        source: String = "local",
        expectedVersion: String? = null,
    ): SkillDescriptor = withContext(Dispatchers.IO) {
        mutex.withLock {
            val packageFiles = TreeMap(files)
            require(packageFiles.size in 1..64) { "Invalid package size" }
            require(packageFiles.values.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 512 * 1024) { "Skill package too large" }
            packageFiles.forEach { (path, content) ->
                validatePath(path)
                require(content.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Skill file too large" }
            }
            val (name, description) = SkillFrontmatter.parse(packageFiles["SKILL.md"] ?: error("Missing SKILL.md"))
            val index = readIndex()
            val previous = index[name]
            if (expectedVersion != null) check(previous?.versionHash == expectedVersion) { "Skill version conflict" }
            val version = hash(packageFiles)
            val descriptor = SkillDescriptor(name, name, description, version, source)
            val packagePath = versionPath(name, version)
            // 完整包作为单个不可变 JSON 写入；索引最后切换，崩溃时不会指向半个目录。
            if (!Files.exists(packagePath)) {
                atomicWrite(packagePath, buildJsonObject {
                    put("schemaVersion", 1)
                    put("files", JsonObject(packageFiles.mapValues { JsonPrimitive(it.value) }))
                }.toString())
            } else {
                check(readPackage(name, version) == packageFiles) { "Immutable skill package mismatch" }
            }
            index[name] = descriptor
            writeIndex(index)
            descriptor
        }
    }

    suspend fun snapshot(): SkillExecutionContext = withContext(Dispatchers.IO) {
        mutex.withLock { SkillExecutionContext(readIndex().toMap()) }
    }

    suspend fun read(id: String, version: String, filePath: String = "SKILL.md"): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            validatePath(filePath)
            val files = readPackage(id, version)
            files[filePath] ?: error("Skill file not found")
        }
    }

    private fun readPackage(id: String, version: String): Map<String, String> {
        val path = versionPath(id, version)
        require(!Files.isSymbolicLink(path)) { "Redirected skill package" }
        val parsed = json.parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)).jsonObject
        val files = parsed.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
        check(hash(files) == version) { "Skill integrity check failed" }
        return files
    }

    private fun versionPath(id: String, version: String): Path {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "Invalid skill id" }
        require(version.matches(Regex("[0-9a-f]{64}"))) { "Invalid skill version" }
        val versions = root.resolve("versions")
        require(!Files.isSymbolicLink(root) && !Files.isSymbolicLink(versions)) { "Redirected skill storage" }
        return versions.resolve("$id-$version.json")
    }

    private fun readIndex(): MutableMap<String, SkillDescriptor> {
        val path = root.resolve("active.json")
        require(!Files.isSymbolicLink(root) && !Files.isSymbolicLink(path)) { "Redirected skill index" }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return linkedMapOf()
        val index = json.parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)).jsonObject
        return index.mapValues { (id, value) ->
            val o = value.jsonObject
            SkillDescriptor(id, o.getValue("name").jsonPrimitive.content,
                o.getValue("description").jsonPrimitive.content, o.getValue("versionHash").jsonPrimitive.content,
                o["source"]?.jsonPrimitive?.content ?: "local")
        }.toMutableMap()
    }

    private fun writeIndex(index: Map<String, SkillDescriptor>) {
        atomicWrite(root.resolve("active.json"), JsonObject(index.mapValues { (_, d) -> buildJsonObject {
            put("name", d.name); put("description", d.description)
            put("versionHash", d.versionHash); put("source", d.source)
        } }).toString())
    }

    companion object {
        internal fun validatePath(path: String) {
            require(path.length in 1..180 && !path.contains('\\') && !path.startsWith('/')) { "Invalid skill path" }
            val parts = path.split('/')
            require(parts.all { it.isNotBlank() && it != "." && it != ".." && it.matches(Regex("[a-zA-Z0-9_.-]+")) }) {
                "Invalid skill path"
            }
            require(path == "SKILL.md" || (parts.size >= 2 && parts.first() in setOf("references", "templates", "scripts", "assets"))) {
                "Unsupported skill file"
            }
        }

        internal fun hash(files: Map<String, String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            TreeMap(files).forEach { (path, text) ->
                // 长度分隔，避免路径/正文边界歧义。
                listOf(path, text).forEach { value ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                    digest.update(bytes)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
