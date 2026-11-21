package top.aaa000111.maid.chat

import android.content.Context
import ai.koog.prompt.message.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.provider.LlmProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 持久化存储单个 [ChatSession] 所需的完整数据结构。
 */
data class PersistedChatSession(
    val id: String,
    val title: String,
    val defaultTitlePrefix: String = ChatSession.DEFAULT_TITLE,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val items: List<ChatItem>,
    val history: List<Message>,
    val fileUriPool: List<String> = emptyList(),
    val imageUriPool: List<String> = emptyList(),
    val attachments: List<SessionAttachment> = emptyList(),
) {
    val uriPool: List<String>
        get() = (fileUriPool + imageUriPool).distinct()
}

/**
 * 会话持久化存储接口，便于在运行时注入本地文件仓库，或在单元测试中使用独立目录/内存替身。
 */
interface ChatSessionStore {
    fun loadAllSessions(): List<PersistedChatSession>
    fun enqueueSave(session: PersistedChatSession)
    fun deleteSession(sessionId: String)
    fun clearAllSessions()
    fun getActiveSessionId(): String?
    fun setActiveSessionId(sessionId: String?)
    fun flush()
}

/**
 * 基于本地文件系统的会话持久化存储实现：
 * - 每个会话以独立 JSON 文件保存在 `sessions/<sessionId>.json`；
 * - 元信息（当前选中会话 ID）保存在 `meta.json`；
 * - 写入采用单线程后台队列合并 + 临时文件原子替换，兼顾响应速度与断电/强杀安全性。
 */
class FileChatSessionStore(
    val rootPath: Path,
) : ChatSessionStore {

    private val sessionsDir: Path = rootPath.resolve(SESSIONS_SUBDIR)
    private val metaFile: Path = rootPath.resolve(META_FILE_NAME)

    private val queueLock = Any()
    private val pendingSaves = LinkedHashMap<String, PersistedChatSession>()
    private val pendingDeletes = LinkedHashSet<String>()
    private var pendingClearAll = false
    private var hasLoadedActiveSessionId = false
    private var cachedActiveSessionId: String? = null
    private var hasPendingActiveSessionId = false
    private var pendingActiveSessionId: String? = null
    private var isShutdown = false

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "maid-session-store-io").apply {
            isDaemon = true
        }
    }

    init {
        Files.createDirectories(sessionsDir)
    }

    override fun loadAllSessions(): List<PersistedChatSession> {
        drainPendingWrites()
        if (!Files.isDirectory(sessionsDir)) return emptyList()
        val loaded = mutableListOf<PersistedChatSession>()
        Files.newDirectoryStream(sessionsDir, "*$SESSION_FILE_EXT").use { stream ->
            for (file in stream) {
                if (!Files.isRegularFile(file)) continue
                val parsed = runCatching {
                    val raw = Files.readAllBytes(file).toString(StandardCharsets.UTF_8)
                    ChatSessionJsonCodec.decodeSession(raw)
                }.getOrNull()
                if (parsed != null) {
                    loaded += parsed
                }
            }
        }
        return loaded.sortedWith(
            compareByDescending<PersistedChatSession> { it.updatedAtMillis }
                .thenByDescending { it.createdAtMillis }
                .thenBy { it.id },
        )
    }

    override fun enqueueSave(session: PersistedChatSession) {
        synchronized(queueLock) {
            if (isShutdown) return
            pendingDeletes.remove(session.id)
            pendingSaves[session.id] = session
        }
        scheduleDrain()
    }

    override fun deleteSession(sessionId: String) {
        synchronized(queueLock) {
            if (isShutdown) return
            pendingSaves.remove(sessionId)
            pendingDeletes.add(sessionId)
            if (hasLoadedActiveSessionId && cachedActiveSessionId == sessionId) {
                cachedActiveSessionId = null
                hasPendingActiveSessionId = true
                pendingActiveSessionId = null
            }
        }
        SessionAttachmentStorage.deleteSessionInternalFiles(sessionId)
        scheduleDrain()
    }

    override fun clearAllSessions() {
        synchronized(queueLock) {
            if (isShutdown) return
            pendingSaves.clear()
            pendingDeletes.clear()
            pendingClearAll = true
            hasLoadedActiveSessionId = true
            cachedActiveSessionId = null
            hasPendingActiveSessionId = true
            pendingActiveSessionId = null
        }
        scheduleDrain()
    }

    override fun getActiveSessionId(): String? {
        synchronized(queueLock) {
            if (hasLoadedActiveSessionId) {
                return cachedActiveSessionId
            }
        }
        val loadedFromDisk = readActiveSessionIdFromDisk()
        synchronized(queueLock) {
            if (!hasLoadedActiveSessionId) {
                cachedActiveSessionId = loadedFromDisk
                hasLoadedActiveSessionId = true
            }
            return cachedActiveSessionId
        }
    }

    override fun setActiveSessionId(sessionId: String?) {
        val normalized = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(queueLock) {
            if (isShutdown) return
            hasLoadedActiveSessionId = true
            if (cachedActiveSessionId == normalized && !hasPendingActiveSessionId) {
                return
            }
            cachedActiveSessionId = normalized
            hasPendingActiveSessionId = true
            pendingActiveSessionId = normalized
        }
        scheduleDrain()
    }

    override fun flush() {
        val future = synchronized(queueLock) {
            if (isShutdown) null else ioExecutor.submit { drainPendingWrites() }
        }
        future?.get()
    }

    fun close() {
        val future = synchronized(queueLock) {
            if (isShutdown) return
            isShutdown = true
            ioExecutor.submit { drainPendingWrites() }
        }
        runCatching { future.get() }
        ioExecutor.shutdown()
    }

    private fun scheduleDrain() {
        synchronized(queueLock) {
            if (isShutdown) return
            ioExecutor.execute { drainPendingWrites() }
        }
    }

    private fun drainPendingWrites() {
        while (true) {
            val saveBatch: List<PersistedChatSession>
            val deleteBatch: List<String>
            val doClearAll: Boolean
            val activeUpdate: Pair<Boolean, String?>
            synchronized(queueLock) {
                if (pendingSaves.isEmpty() &&
                    pendingDeletes.isEmpty() &&
                    !pendingClearAll &&
                    !hasPendingActiveSessionId
                ) {
                    return
                }
                doClearAll = pendingClearAll
                pendingClearAll = false
                saveBatch = pendingSaves.values.toList()
                pendingSaves.clear()
                deleteBatch = pendingDeletes.toList()
                pendingDeletes.clear()
                activeUpdate = hasPendingActiveSessionId to pendingActiveSessionId
                hasPendingActiveSessionId = false
            }

            if (doClearAll) {
                runCatching {
                    if (Files.isDirectory(sessionsDir)) {
                        Files.newDirectoryStream(sessionsDir, "*$SESSION_FILE_EXT").use { stream ->
                            for (file in stream) {
                                val parsedId = runCatching {
                                    val raw = Files.readAllBytes(file).toString(StandardCharsets.UTF_8)
                                    ChatSessionJsonCodec.decodeSession(raw)?.id
                                }.getOrNull()
                                if (parsedId != null) {
                                    SessionAttachmentStorage.deleteSessionInternalFiles(parsedId)
                                }
                                runCatching { Files.deleteIfExists(file) }
                            }
                        }
                    }
                    Files.deleteIfExists(metaFile)
                }
            }

            for (sessionId in deleteBatch) {
                runCatching {
                    Files.deleteIfExists(sessionFileFor(sessionId))
                }
            }
            for (session in saveBatch) {
                runCatching {
                    val encoded = ChatSessionJsonCodec.encodeSession(session)
                    writeAtomically(sessionFileFor(session.id), encoded)
                }
            }
            if (activeUpdate.first) {
                runCatching {
                    val encodedMeta = ChatSessionJsonCodec.encodeMeta(activeUpdate.second)
                    writeAtomically(metaFile, encodedMeta)
                }
            }
        }
    }

    private fun readActiveSessionIdFromDisk(): String? {
        if (!Files.isRegularFile(metaFile)) return null
        return runCatching {
            val raw = Files.readAllBytes(metaFile).toString(StandardCharsets.UTF_8)
            ChatSessionJsonCodec.decodeActiveSessionId(raw)
        }.getOrNull()
    }

    private fun sessionFileFor(sessionId: String): Path {
        val sanitized = sanitizeFileName(sessionId)
        return sessionsDir.resolve("$sanitized$SESSION_FILE_EXT")
    }

    private fun writeAtomically(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val tempFile = target.parent.resolve("${target.fileName}.tmp")
        Files.write(tempFile, content.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(
                tempFile,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            Files.move(
                tempFile,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        private const val SESSIONS_SUBDIR = "sessions"
        private const val META_FILE_NAME = "meta.json"
        private const val SESSION_FILE_EXT = ".json"

        internal fun sanitizeFileName(raw: String): String {
            val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
            return cleaned.ifEmpty { "session" }
        }
    }
}

/**
 * 全局对话会话持久化仓储单例：
 * 在 `AppRuntime.init(context)` 中初始化，负责把多会话元信息、GUI 消息列表、附件信息（`id` / `description` / `uri`）
 * 与 Koog 短期对话历史持久化到应用私有目录。
 */
object ChatSessionRepository : ChatSessionStore {

    const val DEFAULT_DIRECTORY_NAME = "maid_sessions"

    @Volatile
    private var store: FileChatSessionStore? = null

    val isReady: Boolean
        get() = store != null

    val rootPathOrNull: Path?
        get() = store?.rootPath

    fun init(context: Context) {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        SessionAttachmentStorage.init(appContext)
        val targetPath = appContext.filesDir.toPath().resolve(DEFAULT_DIRECTORY_NAME)
        init(targetPath)
    }

    fun init(rootPath: Path) {
        synchronized(this) {
            val existing = store
            if (existing != null && existing.rootPath == rootPath) {
                return
            }
            existing?.close()
            store = FileChatSessionStore(rootPath)
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            store?.close()
            store = null
        }
    }

    private fun requireStore(): FileChatSessionStore {
        return store ?: error("ChatSessionRepository must be initialized via init(...) first")
    }

    override fun loadAllSessions(): List<PersistedChatSession> = requireStore().loadAllSessions()

    override fun enqueueSave(session: PersistedChatSession) {
        store?.enqueueSave(session)
    }

    override fun deleteSession(sessionId: String) {
        store?.deleteSession(sessionId)
    }

    override fun clearAllSessions() {
        store?.clearAllSessions()
    }

    override fun getActiveSessionId(): String? = store?.getActiveSessionId()

    override fun setActiveSessionId(sessionId: String?) {
        store?.setActiveSessionId(sessionId)
    }

    override fun flush() {
        store?.flush()
    }
}

internal object ChatSessionJsonCodec {

    private const val CURRENT_SCHEMA_VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeSession(session: PersistedChatSession): String {
        val historyElements = session.history.mapNotNull { message ->
            runCatching {
                json.encodeToJsonElement(Message.serializer(), message)
            }.getOrNull()
        }
        val root = buildJsonObject {
            put("version", CURRENT_SCHEMA_VERSION)
            put("id", session.id)
            put("title", session.title)
            put("defaultTitlePrefix", session.defaultTitlePrefix)
            put("createdAtMillis", session.createdAtMillis)
            put("updatedAtMillis", session.updatedAtMillis)
            put("fileUriPool", encodeStringArray(session.fileUriPool))
            put("imageUriPool", encodeStringArray(session.imageUriPool))
            put("uriPool", encodeStringArray(session.uriPool))
            put("attachments", buildJsonArray {
                session.attachments.forEach { attachment ->
                    add(encodeSessionAttachment(attachment))
                }
            })
            put("items", buildJsonArray {
                session.items.forEach { item ->
                    add(encodeChatItem(item))
                }
            })
            put("history", JsonArray(historyElements))
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun decodeSession(rawJson: String): PersistedChatSession? {
        val root = runCatching {
            json.parseToJsonElement(rawJson) as? JsonObject
        }.getOrNull() ?: return null

        val id = root.stringValue("id")?.takeIf { it.isNotBlank() } ?: return null
        val createdAt = root.longValue("createdAtMillis") ?: System.currentTimeMillis()
        val updatedAt = root.longValue("updatedAtMillis") ?: createdAt
        val defaultPrefix = root.stringValue("defaultTitlePrefix")
            ?.takeIf { it.isNotBlank() }
            ?: ChatSession.DEFAULT_TITLE
        val title = root.stringValue("title")
            ?.takeIf { it.isNotBlank() }
            ?: defaultPrefix

        val items = (root["items"] as? JsonArray)
            ?.mapNotNull { decodeChatItem(it) }
            .orEmpty()

        val history = (root["history"] as? JsonArray)
            ?.mapNotNull { element ->
                runCatching {
                    json.decodeFromJsonElement(Message.serializer(), element)
                }.getOrNull()
            }
            .orEmpty()

        val storedAttachments = (root["attachments"] as? JsonArray)
            ?.mapNotNull { decodeSessionAttachment(it) }
            .orEmpty()

        val storedFilePool = root.stringListValue("fileUriPool")
        val storedImagePool = root.stringListValue("imageUriPool")
        val legacyCombinedPool = root.stringListValue("uriPool")
        val userFileUris = items.filterIsInstance<ChatItem.UserMessage>().flatMap { it.fileUris }
        val userImageUris = items.filterIsInstance<ChatItem.UserMessage>().flatMap { it.imageUris }

        val mergedImagePool = ChatSession.mergeNormalizedUris(
            ChatSession.mergeNormalizedUris(storedImagePool, userImageUris),
            storedAttachments.filter { it.isImage }.map { it.uri },
        )
        val mergedFilePool = ChatSession.mergeNormalizedUris(
            ChatSession.mergeNormalizedUris(
                ChatSession.mergeNormalizedUris(storedFilePool, userFileUris),
                legacyCombinedPool.filterNot { it in mergedImagePool },
            ),
            storedAttachments.filterNot { it.isImage }.map { it.uri },
        )
        val mergedAttachments = ChatSession.mergeAttachmentsWithUris(
            existing = storedAttachments,
            fileUris = mergedFilePool,
            imageUris = mergedImagePool,
        )

        return PersistedChatSession(
            id = id,
            title = title,
            defaultTitlePrefix = defaultPrefix,
            createdAtMillis = createdAt,
            updatedAtMillis = updatedAt,
            items = items,
            history = history,
            fileUriPool = mergedFilePool,
            imageUriPool = mergedImagePool,
            attachments = mergedAttachments,
        )
    }

    fun encodeMeta(activeSessionId: String?): String {
        val root = buildJsonObject {
            put("version", CURRENT_SCHEMA_VERSION)
            if (activeSessionId != null) {
                put("activeSessionId", activeSessionId)
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun decodeActiveSessionId(rawJson: String): String? {
        val root = runCatching {
            json.parseToJsonElement(rawJson) as? JsonObject
        }.getOrNull() ?: return null
        return root.stringValue("activeSessionId")?.takeIf { it.isNotBlank() }
    }

    private fun encodeSessionAttachment(attachment: SessionAttachment): JsonObject = buildJsonObject {
        put("id", attachment.id)
        put("fileName", attachment.normalizedFileName)
        put("description", attachment.description)
        put("uri", attachment.uri)
        put("originalUri", attachment.originalUri)
        put("isImage", attachment.isImage)
        put("createdAtMillis", attachment.createdAtMillis)
    }

    private fun decodeSessionAttachment(element: JsonElement): SessionAttachment? {
        val obj = element as? JsonObject ?: return null
        val id = obj.stringValue("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = obj.stringValue("uri")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val originalUri = obj.stringValue("originalUri")?.trim()?.takeIf { it.isNotEmpty() } ?: uri
        val isImage = obj.booleanValue("isImage") ?: false
        val fileName = obj.stringValue("fileName")?.trim().orEmpty()
        val description = obj.stringValue("description")?.trim().orEmpty()
        val createdAt = obj.longValue("createdAtMillis") ?: System.currentTimeMillis()
        return SessionAttachment(
            id = id,
            description = description,
            uri = uri,
            originalUri = originalUri,
            isImage = isImage,
            createdAtMillis = createdAt,
            fileName = fileName,
        ).normalized()
    }

    private fun encodeChatItem(item: ChatItem): JsonObject = when (item) {
        is ChatItem.UserMessage -> buildJsonObject {
            put("type", "user")
            put("id", item.id)
            put("content", item.content)
            put("fileUris", encodeStringArray(item.fileUris))
            put("imageUris", encodeStringArray(item.imageUris))
            put("attachmentIds", encodeStringArray(item.attachmentIds))
            put("createdAtMillis", item.createdAtMillis)
        }

        is ChatItem.AssistantMessage -> buildJsonObject {
            put("type", "assistant")
            put("id", item.id)
            put("markdown", item.markdown)
            item.provider?.let { put("provider", it.id) }
            item.modelId?.let { put("modelId", it) }
            item.senderLabel?.let { put("senderLabel", it) }
            put("fileUris", encodeStringArray(item.fileUris))
            put("imageUris", encodeStringArray(item.imageUris))
            put("attachmentIds", encodeStringArray(item.attachmentIds))
            put("isStreaming", false)
            put("createdAtMillis", item.createdAtMillis)
        }

        is ChatItem.ToolStatusItem -> buildJsonObject {
            put("type", "tool_status")
            put("id", item.id)
            put("title", item.title)
            put("detail", item.detail)
            put("isCompleted", item.isCompleted)
            put("createdAtMillis", item.createdAtMillis)
        }

        is ChatItem.PermissionRequestItem -> buildJsonObject {
            put("type", "permission_request")
            put("id", item.id)
            put("toolsetId", item.toolsetId)
            put("toolsetTitle", item.toolsetTitle)
            put("toolsetDescription", item.toolsetDescription)
            put("requiredPrivileges", encodeStringArray(item.requiredPrivileges.map { it.name }))
            put("isCompleted", item.isCompleted)
            put("isGranted", item.isGranted)
            put("matchedPrivileges", encodeStringArray(item.matchedPrivileges.map { it.name }))
            put("availablePrivileges", encodeStringArray(item.availablePrivileges.map { it.name }))
            put("resultSummary", item.resultSummary)
            put("createdAtMillis", item.createdAtMillis)
        }

        is ChatItem.FileCardItem -> buildJsonObject {
            put("type", "file_card")
            put("id", item.id)
            put("attachmentId", item.attachmentId)
            put("fileName", item.fileName)
            put("description", item.description)
            put("uri", item.uri)
            put("isImage", item.isImage)
            put("createdAtMillis", item.createdAtMillis)
        }

        is ChatItem.SystemNoticeItem -> buildJsonObject {
            put("type", "system_notice")
            put("id", item.id)
            put("message", item.message)
            put("isError", item.isError)
            put("createdAtMillis", item.createdAtMillis)
        }
    }

    private fun decodeChatItem(element: JsonElement): ChatItem? {
        val obj = element as? JsonObject ?: return null
        val type = obj.stringValue("type") ?: return null
        val id = obj.stringValue("id")?.takeIf { it.isNotBlank() } ?: return null
        val createdAt = obj.longValue("createdAtMillis") ?: System.currentTimeMillis()
        return when (type) {
            "user" -> ChatItem.UserMessage(
                id = id,
                content = obj.stringValue("content").orEmpty(),
                fileUris = obj.stringListValue("fileUris"),
                imageUris = obj.stringListValue("imageUris"),
                attachmentIds = obj.stringListValue("attachmentIds"),
                createdAtMillis = createdAt,
            )

            "assistant" -> {
                val providerId = obj.stringValue("provider")
                val provider = providerId?.let { target ->
                    LlmProvider.entries.firstOrNull { it.id == target || it.name == target }
                }
                ChatItem.AssistantMessage(
                    id = id,
                    markdown = obj.stringValue("markdown").orEmpty(),
                    provider = provider,
                    modelId = obj.stringValue("modelId"),
                    senderLabel = obj.stringValue("senderLabel"),
                    fileUris = obj.stringListValue("fileUris"),
                    imageUris = obj.stringListValue("imageUris"),
                    attachmentIds = obj.stringListValue("attachmentIds"),
                    // 进程重启后不再存在活跃流式输出，统一恢复为非流式已完成状态
                    isStreaming = false,
                    createdAtMillis = createdAt,
                )
            }

            "tool_status" -> ChatItem.ToolStatusItem(
                id = id,
                title = obj.stringValue("title").orEmpty(),
                detail = obj.stringValue("detail").orEmpty(),
                isCompleted = obj.booleanValue("isCompleted") ?: true,
                createdAtMillis = createdAt,
            )

            "permission_request" -> {
                val requiredPrivileges = obj.stringListValue("requiredPrivileges")
                    .mapNotNull { raw -> AndroidPrivilege.entries.firstOrNull { it.name == raw } }
                val matchedPrivileges = obj.stringListValue("matchedPrivileges")
                    .mapNotNull { raw -> AndroidPrivilege.entries.firstOrNull { it.name == raw } }
                val availablePrivileges = obj.stringListValue("availablePrivileges")
                    .mapNotNull { raw -> AndroidPrivilege.entries.firstOrNull { it.name == raw } }
                ChatItem.PermissionRequestItem(
                    id = id,
                    toolsetId = obj.stringValue("toolsetId").orEmpty(),
                    toolsetTitle = obj.stringValue("toolsetTitle").orEmpty(),
                    toolsetDescription = obj.stringValue("toolsetDescription").orEmpty(),
                    requiredPrivileges = requiredPrivileges,
                    isCompleted = obj.booleanValue("isCompleted") ?: false,
                    isGranted = obj.booleanValue("isGranted") ?: false,
                    matchedPrivileges = matchedPrivileges,
                    availablePrivileges = availablePrivileges,
                    resultSummary = obj.stringValue("resultSummary").orEmpty(),
                    createdAtMillis = createdAt,
                )
            }

            "file_card" -> ChatItem.FileCardItem(
                id = id,
                attachmentId = obj.stringValue("attachmentId").orEmpty(),
                fileName = obj.stringValue("fileName").orEmpty(),
                description = obj.stringValue("description").orEmpty(),
                uri = obj.stringValue("uri").orEmpty(),
                isImage = obj.booleanValue("isImage") ?: false,
                createdAtMillis = createdAt,
            )

            "system_notice" -> ChatItem.SystemNoticeItem(
                id = id,
                message = obj.stringValue("message").orEmpty(),
                isError = obj.booleanValue("isError") ?: false,
                createdAtMillis = createdAt,
            )

            else -> null
        }
    }

    private fun encodeStringArray(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull ?: (this[key] as? JsonPrimitive)?.intOrNull?.toLong()

    private fun JsonObject.booleanValue(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.stringListValue(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            .orEmpty()
}
