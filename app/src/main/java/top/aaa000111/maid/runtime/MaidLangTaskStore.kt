package top.aaa000111.maid.runtime

import BytecodeFile
import Compiler
import LexState
import MaidValue
import Token
import VM
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lexer
import parser
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.PrivilegeManager
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.UUID

/**
 * 已编译 MaidLang 任务的持久化元数据。
 */
data class MaidLangCompiledTaskMeta(
    val taskId: String,
    val name: String = "",
    val createdAtMillis: Long,
    val bytecodeSizeBytes: Int,
    val referencedNatives: List<String> = emptyList(),
    val requiredToolsets: List<String> = emptyList(),
)

/**
 * 编译并保存 MaidLang 任务的结果。
 */
sealed interface MaidLangCompileResult {
    data class Success(
        val meta: MaidLangCompiledTaskMeta,
        val missingToolsets: List<MaidLangMissingToolset>,
    ) : MaidLangCompileResult

    data class Failure(
        val stage: String,
        val errorMessage: String,
    ) : MaidLangCompileResult
}

/**
 * 执行已编译 MaidLang 任务的结果。
 */
sealed interface MaidLangExecutionResult {
    data class Success(
        val taskId: String,
        val value: String,
        val durationMillis: Long,
    ) : MaidLangExecutionResult

    data class Failure(
        val taskId: String,
        val errorMessage: String,
        val missingToolsets: List<MaidLangMissingToolset> = emptyList(),
    ) : MaidLangExecutionResult
}

/**
 * 已编译 MaidLang 脚本（`.mlbc`）任务仓库。
 *
 * 每个任务在 [rootDir] 下以 `<taskId>.mlbc`（字节码）与 `<taskId>.json`（元数据）原子落盘，
 * 支持按 `taskId` 列出、校验权限、加载并交由 MaidLang [VM] 执行。
 */
class MaidLangTaskStore(
    private val rootDir: File,
    private val privilegesProvider: () -> Set<AndroidPrivilege> = ::defaultEffectivePrivileges,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val ioLock = Any()

    /**
     * 编译一段 MaidLang 源代码并在编译通过后持久化，返回生成的 `taskId` 与元数据；
     * 任一阶段失败均返回详细报错且不写入磁盘。
     */
    fun makeTask(
        source: String,
        name: String = "",
    ): MaidLangCompileResult {
        if (source.isBlank()) {
            return MaidLangCompileResult.Failure(
                stage = "validation",
                errorMessage = "MaidLang source code must not be blank.",
            )
        }

        val originalTokens = try {
            lexer(source)
        } catch (e: Throwable) {
            return MaidLangCompileResult.Failure(
                stage = "lexer",
                errorMessage = e.message ?: e::class.simpleName ?: "Lexer error",
            )
        }

        val availablePrivileges = runCatching { privilegesProvider() }.getOrDefault(emptySet())
        val checkResult = try {
            MaidLangNativeRegistry.check(source, availablePrivileges)
        } catch (e: Throwable) {
            return MaidLangCompileResult.Failure(
                stage = "check",
                errorMessage = e.message ?: e::class.simpleName ?: "Native check error",
            )
        }

        // 若源码直接调用了已注册的内置原生函数但未手写 external fun 声明，自动通过注册表前置对应声明。
        val autoImportNatives = detectUndeclaredCalledNatives(originalTokens)
        val tokensToParse = if (autoImportNatives.isEmpty()) {
            originalTokens
        } else {
            val preparedSource = MaidLangNativeRegistry.prependDeclarations(source, autoImportNatives)
            try {
                lexer(preparedSource)
            } catch (e: Throwable) {
                return MaidLangCompileResult.Failure(
                    stage = "lexer",
                    errorMessage = e.message ?: e::class.simpleName ?: "Lexer error after declaration prepending",
                )
            }
        }

        val program = try {
            parser(tokensToParse).program()
        } catch (e: Throwable) {
            return MaidLangCompileResult.Failure(
                stage = "parser",
                errorMessage = e.message ?: e::class.simpleName ?: "Syntax parse error",
            )
        }

        val chunk = try {
            Compiler().compile(program)
        } catch (e: Throwable) {
            return MaidLangCompileResult.Failure(
                stage = "compiler",
                errorMessage = e.message ?: e::class.simpleName ?: "Compilation error",
            )
        }

        val bytecode = try {
            BytecodeFile.encode(chunk)
        } catch (e: Throwable) {
            return MaidLangCompileResult.Failure(
                stage = "bytecode",
                errorMessage = e.message ?: e::class.simpleName ?: "Bytecode encode error",
            )
        }

        val taskId = generateTaskId()
        val meta = MaidLangCompiledTaskMeta(
            taskId = taskId,
            name = name.trim(),
            createdAtMillis = clock.millis(),
            bytecodeSizeBytes = bytecode.size,
            referencedNatives = checkResult.referencedNatives.toList(),
            requiredToolsets = checkResult.requiredToolsets.toList(),
        )

        return try {
            synchronized(ioLock) {
                ensureRootDir()
                atomicWriteBytes(bytecodeFile(taskId), bytecode)
                atomicWriteText(metaFile(taskId), encodeMeta(meta))
            }
            MaidLangCompileResult.Success(
                meta = meta,
                missingToolsets = checkResult.missingToolsets,
            )
        } catch (e: Throwable) {
            runCatching { deleteTask(taskId) }
            MaidLangCompileResult.Failure(
                stage = "storage",
                errorMessage = e.message ?: e::class.simpleName ?: "Failed to save compiled task",
            )
        }
    }

    fun hasTask(taskId: String): Boolean {
        if (!isValidTaskId(taskId)) return false
        return synchronized(ioLock) {
            bytecodeFile(taskId).isFile
        }
    }

    fun getTask(taskId: String): MaidLangCompiledTaskMeta? {
        if (!isValidTaskId(taskId)) return null
        return synchronized(ioLock) {
            val bcFile = bytecodeFile(taskId)
            if (!bcFile.isFile) return null
            val mf = metaFile(taskId)
            if (mf.isFile) {
                runCatching { decodeMeta(mf.readText(StandardCharsets.UTF_8)) }.getOrNull()
            } else {
                MaidLangCompiledTaskMeta(
                    taskId = taskId,
                    name = "",
                    createdAtMillis = bcFile.lastModified(),
                    bytecodeSizeBytes = bcFile.length().toInt(),
                )
            }
        }
    }

    fun listTasks(): List<MaidLangCompiledTaskMeta> = synchronized(ioLock) {
        if (!rootDir.isDirectory) return emptyList()
        val files = rootDir.listFiles { file ->
            file.isFile && file.name.endsWith(BYTECODE_EXTENSION)
        } ?: return emptyList()

        files.mapNotNull { file ->
            val taskId = file.name.removeSuffix(BYTECODE_EXTENSION)
            getTask(taskId)
        }.sortedByDescending { it.createdAtMillis }
    }

    fun deleteTask(taskId: String): Boolean {
        if (!isValidTaskId(taskId)) return false
        return synchronized(ioLock) {
            val deletedBc = bytecodeFile(taskId).delete()
            val deletedMeta = metaFile(taskId).delete()
            deletedBc || deletedMeta
        }
    }

    /**
     * 按 [taskId] 加载 `.mlbc` 字节码并在权限过滤后的 MaidLang VM 中执行。
     */
    fun executeTask(
        taskId: String,
        maxInstructions: Long = DEFAULT_MAX_INSTRUCTIONS,
        maxCallDepth: Int = DEFAULT_MAX_CALL_DEPTH,
    ): MaidLangExecutionResult {
        val normalizedId = taskId.trim()
        if (!isValidTaskId(normalizedId)) {
            return MaidLangExecutionResult.Failure(
                taskId = normalizedId,
                errorMessage = "Invalid compiled MaidLang taskId: '$taskId'.",
            )
        }

        val (bytecode, meta) = synchronized(ioLock) {
            val bcFile = bytecodeFile(normalizedId)
            if (!bcFile.isFile) {
                return MaidLangExecutionResult.Failure(
                    taskId = normalizedId,
                    errorMessage = "Compiled MaidLang task '$normalizedId' not found.",
                )
            }
            val bytes = try {
                bcFile.readBytes()
            } catch (e: Throwable) {
                return MaidLangExecutionResult.Failure(
                    taskId = normalizedId,
                    errorMessage = "Failed to read bytecode for '$normalizedId': ${e.message ?: e::class.simpleName}",
                )
            }
            val loadedMeta = getTask(normalizedId)
            bytes to loadedMeta
        }

        val availablePrivileges = runCatching { privilegesProvider() }.getOrDefault(emptySet())
        val missingToolsets = computeMissingToolsets(meta, availablePrivileges)
        if (missingToolsets.isNotEmpty()) {
            val details = missingToolsets.joinToString("; ") { missing ->
                val privs = missing.requiredPrivileges.joinToString("/") { it.name }
                "${missing.toolsetId}(${missing.displayTitle}, requires: $privs)"
            }
            return MaidLangExecutionResult.Failure(
                taskId = normalizedId,
                errorMessage = "Missing required toolset permissions for task '$normalizedId': $details",
                missingToolsets = missingToolsets,
            )
        }

        val chunk = try {
            BytecodeFile.decode(bytecode)
        } catch (e: Throwable) {
            return MaidLangExecutionResult.Failure(
                taskId = normalizedId,
                errorMessage = "Invalid compiled bytecode for '$normalizedId': ${e.message ?: e::class.simpleName}",
            )
        }

        val startMillis = clock.millis()
        return try {
            val vm = MaidLangNativeRegistry.createVmForPrivileges(
                availablePrivileges = availablePrivileges,
                maxCallDepth = maxCallDepth,
                maxInstructions = maxInstructions,
            )
            val result = vm.execute(chunk)
            val durationMillis = (clock.millis() - startMillis).coerceAtLeast(0L)
            MaidLangExecutionResult.Success(
                taskId = normalizedId,
                value = formatMaidValue(result),
                durationMillis = durationMillis,
            )
        } catch (e: Throwable) {
            MaidLangExecutionResult.Failure(
                taskId = normalizedId,
                errorMessage = e.message ?: e::class.simpleName ?: "VM execution failed",
            )
        }
    }

    private fun computeMissingToolsets(
        meta: MaidLangCompiledTaskMeta?,
        availablePrivileges: Set<AndroidPrivilege>,
    ): List<MaidLangMissingToolset> {
        if (meta == null || meta.referencedNatives.isEmpty()) return emptyList()
        val missingByToolset = LinkedHashMap<String, MutableList<MaidLangNativeEntry>>()
        for (nativeName in meta.referencedNatives) {
            val entry = MaidLangNativeRegistry[nativeName] ?: continue
            if (!entry.isAllowed(availablePrivileges)) {
                val toolsetId = entry.toolsetId ?: "native"
                missingByToolset.getOrPut(toolsetId) { mutableListOf() }.add(entry)
            }
        }
        return missingByToolset.map { (toolsetId, toolEntries) ->
            val spec = ToolsetPermissionCatalog.resolve(toolsetId)
            val required = LinkedHashSet<AndroidPrivilege>().apply {
                spec?.requiredPrivileges?.let(::addAll)
                toolEntries.forEach { addAll(it.requiredPrivileges) }
            }
            MaidLangMissingToolset(
                toolsetId = toolsetId,
                displayTitle = spec?.displayTitle ?: toolsetId,
                requiredPrivileges = required,
                referencedNatives = toolEntries.map { it.declaration.name },
            )
        }
    }

    private fun detectUndeclaredCalledNatives(tokens: List<Token>): List<String> {
        val declaredFunctions = HashSet<String>()
        val calledNatives = LinkedHashSet<String>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.type != LexState.IDENTIFIER) continue
            val prev = tokens.getOrNull(i - 1)
            if (prev?.type == LexState.IDENTIFIER && prev.value == "fun") {
                declaredFunctions.add(token.value)
                continue
            }
            val next = tokens.getOrNull(i + 1)
            if (next?.type == LexState.OPERATOR && next.value == "(" && token.value in MaidLangNativeRegistry) {
                calledNatives.add(token.value)
            }
        }
        return calledNatives.filterNot { it in declaredFunctions }
    }

    private fun ensureRootDir() {
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            error("Unable to create MaidLang task directory: ${rootDir.absolutePath}")
        }
    }

    private fun bytecodeFile(taskId: String): File = File(rootDir, "$taskId$BYTECODE_EXTENSION")

    private fun metaFile(taskId: String): File = File(rootDir, "$taskId$META_EXTENSION")

    private fun atomicWriteBytes(target: File, bytes: ByteArray) {
        val temp = File.createTempFile(".mlt-", ".tmp", rootDir)
        try {
            temp.writeBytes(bytes)
            moveReplacing(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun atomicWriteText(target: File, content: String) {
        atomicWriteBytes(target, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Throwable) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun encodeMeta(meta: MaidLangCompiledTaskMeta): String {
        val obj = buildJsonObject {
            put("taskId", meta.taskId)
            put("name", meta.name)
            put("createdAtMillis", meta.createdAtMillis)
            put("bytecodeSizeBytes", meta.bytecodeSizeBytes)
            put(
                "referencedNatives",
                buildJsonArray {
                    meta.referencedNatives.forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "requiredToolsets",
                buildJsonArray {
                    meta.requiredToolsets.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
        return JSON.encodeToString(JsonObject.serializer(), obj)
    }

    private fun decodeMeta(raw: String): MaidLangCompiledTaskMeta {
        val obj = JSON.parseToJsonElement(raw) as? JsonObject
            ?: error("Corrupted task metadata JSON")
        val taskId = (obj["taskId"] as? JsonPrimitive)?.contentOrNull
            ?: error("Missing taskId in task metadata")
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val createdAtMillis = (obj["createdAtMillis"] as? JsonPrimitive)?.longOrNull ?: 0L
        val bytecodeSizeBytes = (obj["bytecodeSizeBytes"] as? JsonPrimitive)?.intOrNull ?: 0
        val referencedNatives = (obj["referencedNatives"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val requiredToolsets = (obj["requiredToolsets"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        return MaidLangCompiledTaskMeta(
            taskId = taskId,
            name = name,
            createdAtMillis = createdAtMillis,
            bytecodeSizeBytes = bytecodeSizeBytes,
            referencedNatives = referencedNatives,
            requiredToolsets = requiredToolsets,
        )
    }

    companion object {
        const val TASKS_DIR_NAME = "maidlang_tasks"
        const val BYTECODE_EXTENSION = ".mlbc"
        const val META_EXTENSION = ".json"
        const val DEFAULT_MAX_INSTRUCTIONS = 1_000_000L
        const val DEFAULT_MAX_CALL_DEPTH = 256

        private val TASK_ID_REGEX = Regex("^[A-Za-z0-9_-]{4,64}$")
        private val JSON = Json { ignoreUnknownKeys = true }

        fun isValidTaskId(taskId: String): Boolean = TASK_ID_REGEX.matches(taskId)

        private fun generateTaskId(): String {
            return "mlt_" + UUID.randomUUID().toString().replace("-", "").take(12)
        }

        fun defaultEffectivePrivileges(): Set<AndroidPrivilege> {
            return MaidLangNativeRegistry.currentAvailablePrivileges() +
                PrivilegeManager.overview.value.availablePrivileges
        }

        fun formatMaidValue(value: MaidValue): String = when (value) {
            is MaidValue.NullVal -> "null"
            is MaidValue.IntVal -> value.value.toString()
            is MaidValue.FloatVal -> value.value.toString()
            is MaidValue.CharVal -> value.value.toString()
            is MaidValue.StringVal -> value.value
            is MaidValue.FunctionVal -> "<function ${value.node.name}>"
            is MaidValue.NativeFuncVal -> "<native ${value.name}>"
        }
    }
}
