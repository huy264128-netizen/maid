package top.aaa000111.maid.runtime

import Interpreter
import LexState
import NativeFunction
import NativeRegistry
import VM
import android.content.Context
import lexer
import operators
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.PrivilegeManager
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog
import whiteSpace

/**
 * MaidLang 原生函数支持的类型枚举，对应脚本中的 `external fun` 类型关键字。
 */
enum class MaidLangNativeType(val keyword: String) {
    INT("int"),
    FLOAT("float"),
    CHAR("char"),
    STRING("string"),
    BOOL("bool"),
    VOID("void"),
    ;

    companion object {
        fun fromKeyword(keyword: String): MaidLangNativeType {
            return entries.firstOrNull { it.keyword == keyword }
                ?: throw IllegalArgumentException("Unsupported MaidLang native type keyword: $keyword")
        }
    }
}

/**
 * MaidLang 权限组配置：将一组 [AndroidPrivilege] 组合为可复用的可用权限集合。
 */
data class MaidLangPermissionGroup(
    val id: String,
    val displayName: String,
    val privileges: Set<AndroidPrivilege>,
) {
    init {
        require(id.isNotBlank()) { "Permission group id must not be blank" }
    }

    /** 判断该权限组是否满足某项原生函数或工具集所需的候选权限（满足任意一项或无要求即允许）。 */
    fun allows(requiredPrivileges: Set<AndroidPrivilege>): Boolean {
        return requiredPrivileges.isEmpty() || requiredPrivileges.any { it in privileges }
    }

    companion object {
        val NONE = MaidLangPermissionGroup(
            id = "none",
            displayName = "仅基础权限（无特殊权限）",
            privileges = emptySet(),
        )

        val STANDARD = MaidLangPermissionGroup(
            id = "standard",
            displayName = "普通系统特殊权限",
            privileges = setOf(
                AndroidPrivilege.FILE_MANAGEMENT,
                AndroidPrivilege.USAGE_STATS,
                AndroidPrivilege.WRITE_SETTINGS,
                AndroidPrivilege.SYSTEM_ALERT_WINDOW,
            ),
        )

        val ACCESSIBILITY = MaidLangPermissionGroup(
            id = "accessibility",
            displayName = "无障碍权限组",
            privileges = STANDARD.privileges + AndroidPrivilege.ACCESSIBILITY,
        )

        val SHIZUKU = MaidLangPermissionGroup(
            id = "shizuku",
            displayName = "Shizuku 权限组",
            privileges = ACCESSIBILITY.privileges + AndroidPrivilege.SHIZUKU,
        )

        val DHIZUKU = MaidLangPermissionGroup(
            id = "dhizuku",
            displayName = "Dhizuku 设备所有者权限组",
            privileges = STANDARD.privileges + AndroidPrivilege.DHIZUKU,
        )

        val ROOT = MaidLangPermissionGroup(
            id = "root",
            displayName = "Root 权限组",
            privileges = SHIZUKU.privileges + setOf(
                AndroidPrivilege.ROOT,
                AndroidPrivilege.ROOT_SHIZUKU,
            ),
        )

        val ALL = MaidLangPermissionGroup(
            id = "all",
            displayName = "全部权限",
            privileges = AndroidPrivilege.entries.toSet(),
        )

        val presets: List<MaidLangPermissionGroup> = listOf(
            NONE,
            STANDARD,
            ACCESSIBILITY,
            SHIZUKU,
            DHIZUKU,
            ROOT,
            ALL,
        )
    }
}

/**
 * 单个宿主原生函数的 MaidLang 签名声明。
 *
 * 用于生成与 MaidLang `external fun` 语法严格一致的源码声明，
 * 避免脚本手工拼写声明与宿主实际实现不一致。
 */
data class MaidLangNativeDeclaration(
    val name: String,
    val paramTypes: List<MaidLangNativeType> = emptyList(),
    val returnType: MaidLangNativeType = MaidLangNativeType.VOID,
    val description: String = "",
) {
    init {
        require(isValidMaidLangIdentifier(name)) {
            "Invalid MaidLang native function name: '$name'"
        }
        require(MaidLangNativeType.VOID !in paramTypes) {
            "Native function '$name' parameters cannot be void"
        }
    }

    /** 生成标准 MaidLang `external fun` 声明语句。 */
    fun toExternalFunDeclaration(): String {
        val params = paramTypes.joinToString(", ") { it.keyword }
        return "external fun $name($params) -> ${returnType.keyword};"
    }

    /** 生成附带注释说明（若存在）的 `external fun` 声明文本，同时保持为合法 MaidLang 源码。 */
    fun toDocumentedDeclaration(): String {
        val trimmedDescription = description.trim()
        if (trimmedDescription.isEmpty()) {
            return toExternalFunDeclaration()
        }
        val commentLines = trimmedDescription.lines().joinToString("\n") { line ->
            val content = line.trim()
            if (content.isEmpty()) "//" else "// $content"
        }
        return "$commentLines\n${toExternalFunDeclaration()}"
    }

    private companion object {
        private val RESERVED_KEYWORDS = setOf(
            "int",
            "float",
            "char",
            "string",
            "bool",
            "void",
            "var",
            "fun",
            "external",
            "if",
            "else",
            "while",
            "for",
            "return",
            "import",
            "true",
            "false",
        )

        private fun isValidMaidLangIdentifier(name: String): Boolean {
            if (name.isBlank() || name in RESERVED_KEYWORDS) return false
            if (name.first().isDigit()) return false
            if (name.any { it in operators || it in whiteSpace || it.isWhitespace() }) return false
            return runCatching {
                val tokens = lexer(name)
                tokens.size == 1 &&
                    tokens[0].type == LexState.IDENTIFIER &&
                    tokens[0].value == name
            }.getOrDefault(false)
        }
    }
}

/**
 * 已注册的 MaidLang 原生函数条目，同时记录：
 * - [declaration]：MaidLang `external fun` 签名声明
 * - [implementation]：Kotlin 宿主实现
 * - [toolsetId]：所属 Android 工具集标识（如 `input`、`screen`、`shell` 等；非工具集函数可为 `null`）
 * - [requiredPrivileges]：解锁该原生函数/工具集的候选可用权限集合（满足任意一项即可；空集表示无需特殊权限）
 */
data class MaidLangNativeEntry(
    val declaration: MaidLangNativeDeclaration,
    val implementation: NativeFunction,
    val toolsetId: String? = null,
    val requiredPrivileges: Set<AndroidPrivilege> = emptySet(),
) {
    /** 判断在给定的可用权限集合下是否允许调用该原生函数。 */
    fun isAllowed(availablePrivileges: Set<AndroidPrivilege>): Boolean {
        return requiredPrivileges.isEmpty() || requiredPrivileges.any { it in availablePrivileges }
    }

    /** 判断给定的权限组是否满足该原生函数的权限要求。 */
    fun isAllowed(permissionGroup: MaidLangPermissionGroup): Boolean {
        return permissionGroup.allows(requiredPrivileges)
    }
}

/**
 * 源码权限检查中识别出的单个缺失工具集详情。
 */
data class MaidLangMissingToolset(
    val toolsetId: String,
    val displayTitle: String,
    val requiredPrivileges: Set<AndroidPrivilege>,
    val referencedNatives: List<String>,
)

/**
 * 对 MaidLang 源代码执行词法扫描后的工具集与权限检查结果。
 */
data class MaidLangCheckResult(
    val referencedNatives: Set<String>,
    val requiredToolsets: Set<String>,
    val missingToolsets: List<MaidLangMissingToolset>,
    val missingNatives: Set<String>,
    val availablePrivileges: Set<AndroidPrivilege>,
) {
    val missingToolsetIds: Set<String>
        get() = missingToolsets.mapTo(LinkedHashSet()) { it.toolsetId }

    val isSatisfied: Boolean
        get() = missingToolsets.isEmpty() && missingNatives.isEmpty()
}

/**
 * 应用级 MaidLang 原生函数注册、权限组配置与声明管理单例。
 *
 * 默认预注册应用内置的 12 个 Android 工具集原生函数，并记录每个函数所属的 `toolsetId` 与解锁所需的
 * `requiredPrivileges`；可通过 [check] 对脚本源码执行单次 `lexer` 扫描以检查还缺少哪些工具集。
 */
object MaidLangNativeRegistry {
    private val lock = Any()
    private val entries = LinkedHashMap<String, MaidLangNativeEntry>()
    private val nativeRegistry = NativeRegistry()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activePermissionGroup: MaidLangPermissionGroup = MaidLangPermissionGroup.NONE

    init {
        registerBuiltInAndroidTools()
    }

    /** 绑定应用 [Context] 以支持需要上下文的内置 Android 工具集，并确保内置工具已注册。 */
    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            registerBuiltInAndroidToolsLocked()
        }
    }

    /** 注册全部内置 Android 工具集对应的 MaidLang 原生函数。 */
    fun registerBuiltInAndroidTools() {
        synchronized(lock) {
            registerBuiltInAndroidToolsLocked()
        }
    }

    /** 重置权限组为默认（[MaidLangPermissionGroup.NONE]），清空并重新注册全部内置工具。 */
    fun resetToDefaults() {
        synchronized(lock) {
            activePermissionGroup = MaidLangPermissionGroup.NONE
            entries.clear()
            nativeRegistry.clear()
            registerBuiltInAndroidToolsLocked()
        }
    }

    /** 返回当前激活的权限组设置。 */
    fun currentPermissionGroup(): MaidLangPermissionGroup = activePermissionGroup

    /** 返回当前激活的可用权限集合。 */
    fun currentAvailablePrivileges(): Set<AndroidPrivilege> = activePermissionGroup.privileges

    /** 设置当前激活的权限组。 */
    fun setPermissionGroup(group: MaidLangPermissionGroup) {
        synchronized(lock) {
            activePermissionGroup = group
        }
    }

    /** 直接按权限集合更新当前可用权限组。 */
    fun setAvailablePrivileges(
        privileges: Set<AndroidPrivilege>,
        id: String = "custom",
        displayName: String = "自定义权限组",
    ): MaidLangPermissionGroup {
        val group = MaidLangPermissionGroup(
            id = id,
            displayName = displayName,
            privileges = privileges.toSet(),
        )
        setPermissionGroup(group)
        return group
    }

    /** 从 [PrivilegeManager] 当前快照同步可用权限集合。 */
    fun syncPrivilegesFromManager(): MaidLangPermissionGroup {
        val current = PrivilegeManager.overview.value.availablePrivileges
        return setAvailablePrivileges(
            privileges = current,
            id = "runtime_detected",
            displayName = "当前设备已授予权限",
        )
    }

    /** 注册一个带声明与工具集权限信息的原生函数；同名函数会被新条目覆盖。 */
    fun register(
        declaration: MaidLangNativeDeclaration,
        implementation: NativeFunction,
        toolsetId: String? = null,
        requiredPrivileges: Set<AndroidPrivilege> = emptySet(),
    ): MaidLangNativeDeclaration = synchronized(lock) {
        val entry = MaidLangNativeEntry(
            declaration = declaration,
            implementation = implementation,
            toolsetId = toolsetId,
            requiredPrivileges = requiredPrivileges.toSet(),
        )
        entries[declaration.name] = entry
        nativeRegistry.register(declaration.name, implementation)
        declaration
    }

    /** 以签名参数便捷注册一个原生函数；同名函数会被新条目覆盖。 */
    fun register(
        name: String,
        paramTypes: List<MaidLangNativeType> = emptyList(),
        returnType: MaidLangNativeType = MaidLangNativeType.VOID,
        description: String = "",
        toolsetId: String? = null,
        requiredPrivileges: Set<AndroidPrivilege> = emptySet(),
        implementation: NativeFunction,
    ): MaidLangNativeDeclaration {
        val declaration = MaidLangNativeDeclaration(
            name = name,
            paramTypes = paramTypes,
            returnType = returnType,
            description = description,
        )
        return register(
            declaration = declaration,
            implementation = implementation,
            toolsetId = toolsetId,
            requiredPrivileges = requiredPrivileges,
        )
    }

    /** 批量注册原生函数条目。 */
    fun registerAll(natives: Iterable<MaidLangNativeEntry>) {
        synchronized(lock) {
            for (entry in natives) {
                entries[entry.declaration.name] = entry
                nativeRegistry.register(entry.declaration.name, entry.implementation)
            }
        }
    }

    /** 注销指定名称的原生函数，若存在则返回原条目。 */
    fun unregister(name: String): MaidLangNativeEntry? = synchronized(lock) {
        nativeRegistry.unregister(name)
        entries.remove(name)
    }

    /** 清空所有已注册的原生函数与声明。 */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            nativeRegistry.clear()
        }
    }

    operator fun contains(name: String): Boolean = synchronized(lock) {
        name in entries
    }

    operator fun get(name: String): MaidLangNativeEntry? = synchronized(lock) {
        entries[name]
    }

    fun declarationOf(name: String): MaidLangNativeDeclaration? = synchronized(lock) {
        entries[name]?.declaration
    }

    fun names(): Set<String> = synchronized(lock) {
        entries.keys.toSet()
    }

    /** 返回所有已注册原生函数所属的工具集 ID 集合（按注册顺序）。 */
    fun registeredToolsetIds(): Set<String> = synchronized(lock) {
        entries.values.mapNotNullTo(LinkedHashSet()) { it.toolsetId }
    }

    /** 返回归属于指定 [toolsetId] 的全部已注册原生函数条目。 */
    fun entriesForToolset(toolsetId: String): List<MaidLangNativeEntry> = synchronized(lock) {
        entries.values.filter { it.toolsetId == toolsetId }
    }

    /** 返回全部已注册原生函数的声明列表（按注册顺序）。 */
    fun declarations(): List<MaidLangNativeDeclaration> = synchronized(lock) {
        entries.values.map(MaidLangNativeEntry::declaration)
    }

    /**
     * 按给定名称列表返回对应原生函数的声明（按传入顺序去重）。
     * 若存在未注册的函数名则抛出 [IllegalArgumentException]。
     */
    fun declarations(names: Collection<String>): List<MaidLangNativeDeclaration> = synchronized(lock) {
        resolveEntriesLocked(names).map(MaidLangNativeEntry::declaration)
    }

    /** 返回在指定权限集合下允许调用的原生函数名称集合。 */
    fun allowedNativeNames(
        availablePrivileges: Set<AndroidPrivilege> = currentAvailablePrivileges(),
    ): Set<String> = synchronized(lock) {
        entries.values
            .filter { it.isAllowed(availablePrivileges) }
            .mapTo(LinkedHashSet()) { it.declaration.name }
    }

    /**
     * 使用单次 [lexer] 扫描源代码中的标识符 token，检查脚本涉及哪些已注册原生函数，
     * 以及在 [availablePrivileges] 下还缺少哪些工具集与权限。
     */
    fun check(
        source: String,
        availablePrivileges: Set<AndroidPrivilege> = currentAvailablePrivileges(),
    ): MaidLangCheckResult {
        val tokens = lexer(source)
        return synchronized(lock) {
            val referencedNatives = LinkedHashSet<String>()
            val requiredToolsets = LinkedHashSet<String>()
            val missingNatives = LinkedHashSet<String>()
            val missingByToolset = LinkedHashMap<String, MutableList<MaidLangNativeEntry>>()

            for (token in tokens) {
                if (token.type != LexState.IDENTIFIER) continue
                val entry = entries[token.value] ?: continue
                if (!referencedNatives.add(entry.declaration.name)) continue

                val toolsetId = entry.toolsetId
                if (toolsetId != null) {
                    requiredToolsets.add(toolsetId)
                }
                if (!entry.isAllowed(availablePrivileges)) {
                    missingNatives.add(entry.declaration.name)
                    if (toolsetId != null) {
                        missingByToolset.getOrPut(toolsetId) { mutableListOf() }.add(entry)
                    }
                }
            }

            val missingToolsets = missingByToolset.map { (toolsetId, toolEntries) ->
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

            MaidLangCheckResult(
                referencedNatives = referencedNatives,
                requiredToolsets = requiredToolsets,
                missingToolsets = missingToolsets,
                missingNatives = missingNatives,
                availablePrivileges = availablePrivileges.toSet(),
            )
        }
    }

    /** 使用指定 [permissionGroup] 检查源代码缺少哪些工具集。 */
    fun check(
        source: String,
        permissionGroup: MaidLangPermissionGroup,
    ): MaidLangCheckResult = check(source, permissionGroup.privileges)

    /**
     * 为全部或指定引入的原生函数生成 `external fun` 声明源码。
     *
     * @param names 需要引入的原生函数名集合；为 `null` 时导出全部已注册函数。
     */
    fun buildExternalDeclarations(names: Collection<String>? = null): String {
        val selected = if (names == null) declarations() else declarations(names)
        return selected.joinToString("\n") { it.toExternalFunDeclaration() }
    }

    /**
     * 为全部或指定引入的原生函数生成带注释说明的 `external fun` 声明源码。
     */
    fun buildDocumentedDeclarations(names: Collection<String>? = null): String {
        val selected = if (names == null) declarations() else declarations(names)
        return selected.joinToString("\n") { it.toDocumentedDeclaration() }
    }

    /**
     * 为在 [availablePrivileges] 下可用的原生函数生成 `external fun` 声明源码。
     */
    fun buildExternalDeclarationsForPrivileges(
        availablePrivileges: Set<AndroidPrivilege> = currentAvailablePrivileges(),
        names: Collection<String>? = null,
    ): String {
        val allowed = allowedNativeNames(availablePrivileges)
        val filtered = if (names == null) {
            allowed
        } else {
            val resolved = declarations(names).map { it.name }
            resolved.filter { it in allowed }
        }
        return buildExternalDeclarations(filtered)
    }

    /**
     * 将全部或指定引入的原生函数 `external fun` 声明前置到脚本源码顶部。
     */
    fun prependDeclarations(
        source: String,
        names: Collection<String>? = null,
    ): String {
        val header = buildExternalDeclarations(names)
        if (header.isEmpty()) return source
        if (source.isBlank()) return header
        return "$header\n$source"
    }

    /**
     * 生成独立的 [NativeRegistry] 快照，供单次或按需隔离的 [Interpreter] / [VM] 使用。
     *
     * @param names 需要引入的原生函数名集合；为 `null` 时复制全部已注册函数。
     */
    fun snapshotNativeRegistry(names: Collection<String>? = null): NativeRegistry = synchronized(lock) {
        if (names == null) {
            nativeRegistry.copy()
        } else {
            val selected = resolveEntriesLocked(names)
            NativeRegistry().apply {
                for (entry in selected) {
                    register(entry.declaration.name, entry.implementation)
                }
            }
        }
    }

    /** 按权限集合过滤后生成独立的 [NativeRegistry] 快照。 */
    fun snapshotAllowedNativeRegistry(
        availablePrivileges: Set<AndroidPrivilege> = currentAvailablePrivileges(),
        names: Collection<String>? = null,
    ): NativeRegistry = synchronized(lock) {
        val candidates = if (names == null) entries.values else resolveEntriesLocked(names)
        NativeRegistry().apply {
            for (entry in candidates) {
                if (entry.isAllowed(availablePrivileges)) {
                    register(entry.declaration.name, entry.implementation)
                }
            }
        }
    }

    /** 按权限组过滤后生成独立的 [NativeRegistry] 快照。 */
    fun snapshotAllowedNativeRegistry(
        permissionGroup: MaidLangPermissionGroup,
        names: Collection<String>? = null,
    ): NativeRegistry = snapshotAllowedNativeRegistry(permissionGroup.privileges, names)

    /** 使用当前注册表快照创建 [Interpreter]。 */
    fun createInterpreter(
        names: Collection<String>? = null,
        maxCallDepth: Int = 256,
    ): Interpreter {
        return Interpreter(
            nativeRegistry = snapshotNativeRegistry(names),
            maxCallDepth = maxCallDepth,
        )
    }

    /** 使用权限过滤后的注册表快照创建 [Interpreter]。 */
    fun createInterpreterForPrivileges(
        availablePrivileges: Set<AndroidPrivilege> = currentAvailablePrivileges(),
        names: Collection<String>? = null,
        maxCallDepth: Int = 256,
    ): Interpreter {
        return Interpreter(
            nativeRegistry = snapshotAllowedNativeRegistry(availablePrivileges, names),
            maxCallDepth = maxCallDepth,
        )
    }

    /** 使用当前注册表快照创建 [VM]。 */
    fun createVm(
        names: Collection<String>? = null,
        maxCallDepth: Int = 1024,
        maxStackSize: Int = 65536,
        maxInstructions: Long = Long.MAX_VALUE,
    ): VM {
        return VM(
            nativeRegistry = snapshotNativeRegistry(names),
            maxCallDepth = maxCallDepth,
            maxStackSize = maxStackSize,
            maxInstructions = maxInstructions,
        )
    }

    /** 使用权限过滤后的注册表快照创建 [VM]。 */
    fun createVmForPrivileges(
        availablePrivileges: Set<AndroidPrivilege> = currentAvailablePrivileges(),
        names: Collection<String>? = null,
        maxCallDepth: Int = 1024,
        maxStackSize: Int = 65536,
        maxInstructions: Long = Long.MAX_VALUE,
    ): VM {
        return VM(
            nativeRegistry = snapshotAllowedNativeRegistry(availablePrivileges, names),
            maxCallDepth = maxCallDepth,
            maxStackSize = maxStackSize,
            maxInstructions = maxInstructions,
        )
    }

    private fun registerBuiltInAndroidToolsLocked() {
        val builtIns = MaidLangBuiltInAndroidTools.buildEntries {
            appContext ?: error("MaidLangNativeRegistry must be initialized via init(context) before invoking context-dependent Android tools")
        }
        for (entry in builtIns) {
            entries[entry.declaration.name] = entry
            nativeRegistry.register(entry.declaration.name, entry.implementation)
        }
    }

    private fun resolveEntriesLocked(names: Collection<String>): List<MaidLangNativeEntry> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<MaidLangNativeEntry>(names.size)
        for (name in names) {
            if (!seen.add(name)) continue
            val entry = entries[name]
                ?: throw IllegalArgumentException("MaidLang native function '$name' is not registered")
            result.add(entry)
        }
        return result
    }
}
