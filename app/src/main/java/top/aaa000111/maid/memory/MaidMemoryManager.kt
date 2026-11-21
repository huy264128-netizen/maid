package top.aaa000111.maid.memory

import android.content.Context
import ai.koog.agents.memory.config.MemoryScopeType
import ai.koog.agents.memory.config.MemoryScopesProfile
import ai.koog.agents.memory.feature.AgentMemory
import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.Fact
import ai.koog.agents.memory.model.MemoryScope
import ai.koog.agents.memory.model.MemorySubject
import ai.koog.agents.memory.model.MultipleFacts
import ai.koog.agents.memory.model.SingleFact
import ai.koog.agents.memory.providers.LocalFileMemoryProvider
import ai.koog.agents.memory.providers.LocalMemoryConfig
import ai.koog.agents.memory.storage.SimpleStorage
import ai.koog.rag.base.files.JVMFileSystemProvider
import java.nio.file.Path

/**
 * Koog 记忆模块全局单例管理器：
 * 基于 `LocalFileMemoryProvider` + `JVMFileSystemProvider.ReadWrite` 将记忆事实持久化在本地目录中，
 * 并提供直接读写接口与 `AgentMemory` Feature 安装配置能力。
 */
object MaidMemoryManager {

    const val DEFAULT_STORAGE_DIRECTORY = "koog_memory"
    const val DEFAULT_PRODUCT_NAME = "maid"
    const val DEFAULT_AGENT_NAME = "maid_assistant"
    const val DEFAULT_FEATURE_NAME = "chat_memory"
    const val DEFAULT_ORGANIZATION_NAME = "maid"

    private data class MemoryState(
        val rootPath: Path,
        val storageDirectory: String,
        val defaultScope: MemoryScope,
    ) {
        val memoryConfig: LocalMemoryConfig = LocalMemoryConfig(
            storageDirectory = storageDirectory,
            defaultScope = defaultScope,
        )

        val memoryProvider: LocalFileMemoryProvider<Path> = LocalFileMemoryProvider(
            config = memoryConfig,
            storage = SimpleStorage(JVMFileSystemProvider.ReadWrite),
            fs = JVMFileSystemProvider.ReadWrite,
            root = rootPath,
        )
    }

    @Volatile
    private var state: MemoryState? = null

    val defaultScopesProfile: MemoryScopesProfile = MemoryScopesProfile(
        MemoryScopeType.PRODUCT to DEFAULT_PRODUCT_NAME,
        MemoryScopeType.AGENT to DEFAULT_AGENT_NAME,
        MemoryScopeType.FEATURE to DEFAULT_FEATURE_NAME,
        MemoryScopeType.ORGANIZATION to DEFAULT_ORGANIZATION_NAME,
    )

    val isInitialized: Boolean
        get() = state != null

    val memoryProvider: LocalFileMemoryProvider<Path>
        get() = requireState().memoryProvider

    val memoryProviderOrNull: LocalFileMemoryProvider<Path>?
        get() = state?.memoryProvider

    val memoryConfig: LocalMemoryConfig
        get() = requireState().memoryConfig

    val defaultScope: MemoryScope
        get() = requireState().defaultScope

    val rootPath: Path
        get() = requireState().rootPath

    fun init(
        context: Context,
        storageDirectory: String = DEFAULT_STORAGE_DIRECTORY,
        defaultScope: MemoryScope = MemoryScope.Product(DEFAULT_PRODUCT_NAME),
    ) {
        val targetRoot = context.applicationContext.filesDir.toPath()
        val current = state
        if (current != null &&
            current.rootPath == targetRoot &&
            current.storageDirectory == storageDirectory &&
            current.defaultScope == defaultScope
        ) {
            return
        }
        init(
            rootPath = targetRoot,
            storageDirectory = storageDirectory,
            defaultScope = defaultScope,
        )
    }

    fun init(
        rootPath: Path,
        storageDirectory: String = DEFAULT_STORAGE_DIRECTORY,
        defaultScope: MemoryScope = MemoryScope.Product(DEFAULT_PRODUCT_NAME),
    ) {
        synchronized(this) {
            MaidMemorySubjects.ensureRegistered()
            state = MemoryState(
                rootPath = rootPath,
                storageDirectory = storageDirectory,
                defaultScope = defaultScope,
            )
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            state = null
        }
    }

    private fun requireState(): MemoryState {
        return state ?: error("MaidMemoryManager must be initialized via init(...) first")
    }

    /**
     * 为 Koog `AIAgent` 配置 `AgentMemory` Feature。
     */
    fun configureAgentMemory(
        config: AgentMemory.Config,
        agentName: String = DEFAULT_AGENT_NAME,
        featureName: String = DEFAULT_FEATURE_NAME,
        productName: String = DEFAULT_PRODUCT_NAME,
        organizationName: String = DEFAULT_ORGANIZATION_NAME,
    ) {
        val current = requireState()
        MaidMemorySubjects.ensureRegistered()
        config.memoryProvider = current.memoryProvider
        config.agentName = agentName
        config.featureName = featureName
        config.productName = productName
        config.organizationName = organizationName
    }

    suspend fun saveSingleFact(
        concept: Concept,
        value: String,
        subject: MemorySubject = MaidMemorySubjects.User,
        scope: MemoryScope = defaultScope,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        saveFact(
            fact = SingleFact(
                concept = concept,
                timestamp = timestamp,
                value = value,
            ),
            subject = subject,
            scope = scope,
        )
    }

    suspend fun saveMultipleFacts(
        concept: Concept,
        values: List<String>,
        subject: MemorySubject = MaidMemorySubjects.User,
        scope: MemoryScope = defaultScope,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        saveFact(
            fact = MultipleFacts(
                concept = concept,
                timestamp = timestamp,
                values = values,
            ),
            subject = subject,
            scope = scope,
        )
    }

    suspend fun saveFact(
        fact: Fact,
        subject: MemorySubject = MaidMemorySubjects.User,
        scope: MemoryScope = defaultScope,
    ) {
        val current = requireState()
        MaidMemorySubjects.ensureRegistered()
        current.memoryProvider.save(fact = fact, subject = subject, scope = scope)
    }

    suspend fun loadFacts(
        concept: Concept,
        subject: MemorySubject = MaidMemorySubjects.User,
        scope: MemoryScope = defaultScope,
    ): List<Fact> {
        val current = requireState()
        MaidMemorySubjects.ensureRegistered()
        return current.memoryProvider.load(concept = concept, subject = subject, scope = scope)
    }

    suspend fun loadAllFacts(
        subject: MemorySubject = MaidMemorySubjects.User,
        scope: MemoryScope = defaultScope,
    ): List<Fact> {
        val current = requireState()
        MaidMemorySubjects.ensureRegistered()
        return current.memoryProvider.loadAll(subject = subject, scope = scope)
    }

    suspend fun loadFactsByDescription(
        description: String,
        subject: MemorySubject = MaidMemorySubjects.User,
        scope: MemoryScope = defaultScope,
    ): List<Fact> {
        val current = requireState()
        MaidMemorySubjects.ensureRegistered()
        return current.memoryProvider.loadByDescription(
            description = description,
            subject = subject,
            scope = scope,
        )
    }

    /**
     * 清空存储在本地目录中的所有跨聊天长期记忆事实，并重新初始化记忆提供者。
     */
    fun clearAllMemory() {
        synchronized(this) {
            val current = state ?: return
            val dir = current.rootPath.resolve(current.storageDirectory).toFile()
            runCatching {
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
            state = MemoryState(
                rootPath = current.rootPath,
                storageDirectory = current.storageDirectory,
                defaultScope = current.defaultScope,
            )
        }
    }
}
