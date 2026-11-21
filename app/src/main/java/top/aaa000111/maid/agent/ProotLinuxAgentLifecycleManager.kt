package top.aaa000111.maid.agent

import android.content.Context
import android.os.Environment
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.aaa000111.maid.androidtool.PrivilegeBackend
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.DefaultUriFileTransfer
import top.aaa000111.maid.chat.SessionAttachmentStorage
import top.aaa000111.maid.chat.TransferMode
import top.aaa000111.maid.chat.UriFileTransfer
import top.aaa000111.maid.proot.ProotConnection
import top.aaa000111.maid.proot.ProotConnectionStatus
import top.aaa000111.maid.proot.TermuxProotRuntime
import top.aaa000111.maid.settings.ModelSafetyConfig
import top.aaa000111.maid.settings.ModelSafetyRepository
import top.aaa000111.maid.shizuku.ShellCommandExecutor
import top.aaa000111.maid.shizuku.ShellCommandResult
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [ProotLinuxAgentLifecycleManager] 持有的专用 PRoot 终端抽象，
 * 生产环境直接包装独立的 [ProotConnection]，单测中可注入轻量桩实现。
 */
interface ProotLinuxTerminalHandle : Closeable {
    val isAlive: Boolean

    fun connect(): Boolean

    fun execute(
        command: String,
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
    ): ShellCommandResult

    fun snapshotStatus(): ProotConnectionStatus
}

/**
 * 生产环境专用 PRoot 终端句柄：
 * 持有与用户交互终端（`ProotConnectionManager`）**完全独立**的 [ProotConnection] 进程，
 * 挂载同一个全局 `rootfs`（`<filesDir>/rootfs`），避免与用户前台终端互相干扰。
 */
internal class DedicatedProotConnectionHandle(
    private val connection: ProotConnection,
) : ProotLinuxTerminalHandle {

    override val isAlive: Boolean
        get() = connection.isAlive

    override fun connect(): Boolean = connection.connect()

    override fun execute(command: String, timeoutMillis: Long): ShellCommandResult {
        return connection.execute(command = command, timeoutMillis = timeoutMillis)
    }

    override fun snapshotStatus(): ProotConnectionStatus = connection.snapshotStatus()

    override fun close() {
        connection.close()
    }
}

enum class ProotLinuxLifecycleStatus {
    IDLE,
    RUNNING,
    CLOSED,
}

data class ProotLinuxLifecycleState(
    val status: ProotLinuxLifecycleStatus = ProotLinuxLifecycleStatus.IDLE,
    val terminalConnected: Boolean = false,
    val terminalGeneration: Long = 0L,
    val rootfsPath: String = "",
    val activeSessionId: String? = null,
    val suggestedWorkingDir: String = ProotLinuxAgentLifecycleManager.DEFAULT_PROOT_HOME,
    val executedCommandCount: Long = 0L,
    val completedTaskCount: Long = 0L,
    val lastError: String? = null,
)

/**
 * 绑定到 [ProotLinuxAgentLifecycleManager] 专用 PRoot 终端的终端工具集。
 */
class ProotLinuxTerminalToolSet internal constructor(
    private val lifecycleManager: ProotLinuxAgentLifecycleManager,
    private val suggestedWorkingDirDisplay: String,
    private val suggestedWorkingDirPosix: String,
    private val secretValuesToRedact: Set<String> = emptySet(),
) : ToolSet {

    override val name: String = TOOLSET_NAME

    private val commandCounter = AtomicLong(0L)

    internal fun executedCommandCount(): Int = commandCounter.get().toInt()

    @Tool
    @LLMDescription(
        "Execute a shell command inside the dedicated persistent PRoot Linux terminal managed alongside this agent. " +
            "Shell state (working directory, exported env vars) persists across calls until resetProotTerminal is called. " +
            "You are encouraged (not required) to use the suggested session workspace ~/<session id>/, and you are free to modify the container environment (install packages, create scripts, edit configs).",
    )
    suspend fun executeProotCommand(
        @LLMDescription("POSIX shell command to execute inside the PRoot Linux container.")
        command: String,
        @LLMDescription("Optional working directory to cd into before running the command (supports '~' and '~/...'). Leave empty to stay in the terminal's current working directory.")
        workingDir: String = "",
        @LLMDescription("Optional command timeout in seconds (clamped to 5..600, default 60).")
        timeoutSeconds: Int = 60,
    ): String {
        val trimmedCommand = command.trim()
        if (trimmedCommand.isEmpty()) {
            return "Command must not be blank."
        }
        val safeTimeoutMs = timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1_000L
        val effectiveCommand = buildCommandWithOptionalWorkingDir(
            command = trimmedCommand,
            workingDir = workingDir,
        )

        val result = withContext(Dispatchers.IO) {
            lifecycleManager.executeInDedicatedTerminal(
                command = effectiveCommand,
                timeoutMillis = safeTimeoutMs,
            )
        }
        commandCounter.incrementAndGet()
        return formatCommandResult(
            result = result,
            secretValuesToRedact = secretValuesToRedact,
        )
    }

    @Tool
    @LLMDescription(
        "Inspect the status of the dedicated PRoot Linux terminal (connection state, generation, rootfs path, suggested workspace, and executed command count).",
    )
    fun getProotTerminalStatus(): String {
        val status = lifecycleManager.terminalStatusSnapshot()
        return buildString {
            appendLine("connected=${status.isConnected}")
            appendLine("generation=${status.sessionGeneration}")
            appendLine("shell=${status.shellExecutable}")
            appendLine("initialWorkingDir=${status.workingDir}")
            appendLine("suggestedWorkspace=$suggestedWorkingDirDisplay ($suggestedWorkingDirPosix)")
            if (status.rootfsPath.isNotBlank()) {
                appendLine("rootfsPath=${status.rootfsPath}")
            }
            append("executedCommands=${status.executedCommandCount}")
        }
    }

    @Tool
    @LLMDescription(
        "Restart the dedicated PRoot Linux terminal process cleanly if the shell is wedged or needs a fresh session. " +
            "Files and installed packages in the PRoot container filesystem are preserved.",
    )
    suspend fun resetProotTerminal(): String {
        val status = withContext(Dispatchers.IO) {
            lifecycleManager.resetDedicatedTerminal().snapshotStatus()
        }
        return "Dedicated PRoot terminal restarted (connected=${status.isConnected}, generation=${status.sessionGeneration}, workingDir=${status.workingDir})."
    }

    companion object {
        const val TOOLSET_NAME = "proot_linux_terminal"
        private const val MIN_TIMEOUT_SECONDS = 5
        private const val MAX_TIMEOUT_SECONDS = 600
        private const val MAX_OUTPUT_CHARS = 24_000

        internal fun buildCommandWithOptionalWorkingDir(
            command: String,
            workingDir: String,
        ): String {
            val trimmedDir = workingDir.trim()
            if (trimmedDir.isEmpty()) return command
            val expandedDir = expandTildeToProotHome(trimmedDir)
            val escapedDir = "'" + expandedDir.replace("'", "'\"'\"'") + "'"
            return "cd $escapedDir && $command"
        }

        internal fun formatCommandResult(
            result: ShellCommandResult,
            secretValuesToRedact: Collection<String> = emptySet(),
        ): String = buildString {
            appendLine("exitCode=${result.exitCode}")
            appendLine("success=${result.isSuccess}")
            appendLine("timedOut=${result.timedOut}")
            appendLine("durationMs=${result.durationMs}")
            val rawStdout = ProotLlmApiEnvResolver.redactSecrets(
                text = result.stdout.trimEnd(),
                secretValues = secretValuesToRedact,
            )
            val rawStderr = ProotLlmApiEnvResolver.redactSecrets(
                text = result.stderr.trimEnd(),
                secretValues = secretValuesToRedact,
            )
            val stdout = truncateOutput(rawStdout)
            val stderr = truncateOutput(rawStderr)
            if (stdout.isNotEmpty()) {
                appendLine("stdout:")
                appendLine(stdout)
            } else {
                appendLine("stdout: (empty)")
            }
            if (stderr.isNotEmpty()) {
                appendLine("stderr:")
                append(stderr)
            }
        }.trimEnd()

        private fun truncateOutput(text: String): String {
            if (text.length <= MAX_OUTPUT_CHARS) return text
            val omitted = text.length - MAX_OUTPUT_CHARS
            return text.take(MAX_OUTPUT_CHARS) + "\n...[truncated $omitted chars]"
        }
    }
}

/**
 * PRoot Linux 与宿主 / [ChatSession] 附件系统之间的文件输入输出工具集：
 * - 支持将当前会话附件池中的文件（按 `id` 或 URI）导入到 PRoot 容器内；
 * - 支持将 PRoot 容器内生成的产物导出到当前会话（注册新附件 `id` 与文件描述，供规划者/对话助手发送文件卡片）；
 * - 支持宿主路径/URI 与 PRoot 路径之间的双向拷贝或移动（复用统一 [UriFileTransfer]）；
 * - 提供直接读写 PRoot 内 UTF-8 文本文件的便捷工具。
 */
class ProotLinuxFileIoToolSet internal constructor(
    private val rootfsDirProvider: () -> File,
    private val session: ChatSession? = null,
    private val defaultBaseDirPosix: String = ProotLinuxAgentLifecycleManager.DEFAULT_PROOT_HOME,
    private val fileTransferProvider: () -> UriFileTransfer = { SessionAttachmentStorage.defaultFileTransfer },
) : ToolSet {

    override val name: String = TOOLSET_NAME

    @Tool
    @LLMDescription(
        "List all files and images currently registered in the active chat session's attachment pool, including their IDs, filenames, and descriptions.",
    )
    fun listSessionAttachments(): String {
        val activeSession = session
            ?: return "No active ChatSession is attached to this PRoot Linux task."
        val attachments = activeSession.attachments
        if (attachments.isEmpty()) {
            return "The active session currently has no registered files or images."
        }
        return buildString {
            appendLine("sessionId=${activeSession.id}")
            appendLine("count=${attachments.size}")
            attachments.forEach { att ->
                val kind = if (att.isImage) "image" else "file"
                appendLine("- [$kind] id=${att.id} | fileName=${att.normalizedFileName} | description=${att.normalizedDescription} | uri=${att.uri}")
            }
        }.trimEnd()
    }

    @Tool
    @LLMDescription(
        "Import (copy) a file or image from the active chat session (by attachment ID such as 'file_1'/'img_1', or by URI) into the PRoot Linux filesystem. " +
            "If prootTargetPath is empty, the file is placed under the suggested session workspace ~/<session id>/<fileName>.",
    )
    suspend fun importSessionFileToProot(
        @LLMDescription("Session attachment ID (e.g. 'file_1', 'img_1') or source URI ('file://...', 'content://...', or local path).")
        attachmentIdOrUri: String,
        @LLMDescription("Optional destination file or directory path inside PRoot Linux (e.g. '~/session_1/input.csv' or '/tmp/'). Leave empty to place under the suggested session directory.")
        prootTargetPath: String = "",
        @LLMDescription("Whether to overwrite an existing file at the destination (default true).")
        overwrite: Boolean = true,
    ): String {
        val rawRef = attachmentIdOrUri.trim()
        if (rawRef.isEmpty()) return "attachmentIdOrUri must not be blank."

        val activeSession = session
        val attachment = activeSession?.findAttachment(rawRef)
        val sourceUri = activeSession?.accessAttachmentUri(attachment?.id ?: rawRef) ?: rawRef
        val inferredFileName = attachment?.normalizedFileName?.takeIf { it.isNotBlank() }
            ?: SessionAttachmentStorage.inferDisplayName(sourceUri).ifBlank { "imported_file" }

        val rootfsDir = rootfsDirProvider()
        val effectiveTargetSpec = prootTargetPath.trim().ifEmpty {
            "${defaultBaseDirPosix.trimEnd('/')}/$inferredFileName"
        }
        val isExplicitDir = effectiveTargetSpec.endsWith("/")
        val (normalizedPosix, targetHostFile) = resolveProotPath(
            prootPath = effectiveTargetSpec,
            rootfsDir = rootfsDir,
            defaultBaseDirPosix = defaultBaseDirPosix,
        )
        val finalHostFile = if (isExplicitDir || targetHostFile.isDirectory) {
            File(targetHostFile, inferredFileName)
        } else {
            targetHostFile
        }
        val finalPosixPath = if (isExplicitDir || targetHostFile.isDirectory) {
            "${normalizedPosix.trimEnd('/')}/$inferredFileName"
        } else {
            normalizedPosix
        }

        finalHostFile.parentFile?.mkdirs()
        val result = fileTransferProvider().transfer(
            sourceUri = sourceUri,
            targetUri = finalHostFile.toURI().toString(),
            mode = TransferMode.COPY,
            overwrite = overwrite,
            defaultFileName = inferredFileName,
        )
        if (!result.success) {
            return "Failed to import file into PRoot Linux: ${result.message}"
        }
        return buildString {
            appendLine("Imported file into PRoot Linux:")
            if (attachment != null) {
                appendLine("sourceAttachmentId=${attachment.id}")
            }
            appendLine("fileName=$inferredFileName")
            appendLine("prootPath=$finalPosixPath")
            append("bytes=${result.bytesTransferred}")
        }
    }

    @Tool
    @LLMDescription(
        "Export a file created or stored inside the PRoot Linux filesystem into the active chat session's attachment pool, " +
            "generating a session attachment ID (e.g. 'file_2' or 'img_2') so Planner/ChatAgent can inspect it, export it to external storage, or send a FileCard to the user.",
    )
    suspend fun exportProotFileToSession(
        @LLMDescription("Source file path inside the PRoot Linux container (supports '~' and '~/...').")
        prootSourcePath: String,
        @LLMDescription("Clear description of what this file contains so downstream agents and the user understand its purpose.")
        description: String,
        @LLMDescription("Optional display file name override. Leave empty to use the file's basename in PRoot.")
        fileName: String = "",
        @LLMDescription("If true, moves the file (removes it from PRoot after exporting). Default false (keeps a copy inside PRoot).")
        moveFromProot: Boolean = false,
    ): String {
        val activeSession = session
            ?: return "Cannot export file to session: no active ChatSession is bound to this task."
        val trimmedSource = prootSourcePath.trim()
        if (trimmedSource.isEmpty()) return "prootSourcePath must not be blank."

        val rootfsDir = rootfsDirProvider()
        val (normalizedPosix, sourceHostFile) = resolveProotPath(
            prootPath = trimmedSource,
            rootfsDir = rootfsDir,
            defaultBaseDirPosix = defaultBaseDirPosix,
        )
        if (!sourceHostFile.exists() || !sourceHostFile.isFile) {
            return "PRoot source file does not exist or is not a regular file: $normalizedPosix"
        }

        val resolvedFileName = fileName.trim().ifEmpty { sourceHostFile.name }
        val resolvedDesc = description.trim().ifEmpty { "PRoot Linux 导出文件：$resolvedFileName" }

        val moveResult = runCatching {
            activeSession.moveFileToSessionWithResult(
                uri = sourceHostFile.toURI().toString(),
                description = resolvedDesc,
                fileName = resolvedFileName,
                fileTransfer = fileTransferProvider(),
                transferMode = if (moveFromProot) TransferMode.MOVE else TransferMode.COPY,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return "Failed to export PRoot file '$normalizedPosix' to session: ${error.message ?: "unknown error"}"
        }

        val att = moveResult.attachment
        return buildString {
            appendLine("Exported PRoot file to active session:")
            appendLine("id=${att.id}")
            appendLine("type=${if (att.isImage) "image" else "file"}")
            appendLine("fileName=${att.normalizedFileName}")
            appendLine("description=${att.normalizedDescription}")
            appendLine("prootSourcePath=$normalizedPosix")
            appendLine("sessionUri=${att.uri}")
            appendLine("bytes=${moveResult.transfer.bytesTransferred}")
            append("removedFromProot=${moveResult.transfer.sourceDeleted}")
        }
    }

    @Tool
    @LLMDescription(
        "Transfer (copy or move) a file directly between an Android host URI/path (e.g. '/sdcard/Download/a.txt', 'file://...', 'content://...') and a PRoot Linux path.",
    )
    suspend fun transferFileBetweenHostAndProot(
        @LLMDescription("Transfer direction: 'to_proot' (Android host -> PRoot Linux) or 'from_proot' (PRoot Linux -> Android host).")
        direction: String,
        @LLMDescription("Android host path or URI (e.g. '/sdcard/Download/report.pdf', 'file:///...', 'content://...').")
        hostUriOrPath: String,
        @LLMDescription("File or directory path inside PRoot Linux (supports '~' and '~/...').")
        prootPath: String,
        @LLMDescription("If true, moves the file (deletes source after transfer); default false (copies).")
        move: Boolean = false,
        @LLMDescription("Whether to overwrite if destination exists (default true).")
        overwrite: Boolean = true,
    ): String {
        val trimmedHost = hostUriOrPath.trim()
        val trimmedProot = prootPath.trim()
        if (trimmedHost.isEmpty()) return "hostUriOrPath must not be blank."
        if (trimmedProot.isEmpty()) return "prootPath must not be blank."

        val rootfsDir = rootfsDirProvider()
        val (normalizedPosix, prootHostFile) = resolveProotPath(
            prootPath = trimmedProot,
            rootfsDir = rootfsDir,
            defaultBaseDirPosix = defaultBaseDirPosix,
        )
        val mode = if (move) TransferMode.MOVE else TransferMode.COPY
        val normalizedDirection = direction.trim().lowercase()

        return when (normalizedDirection) {
            "to_proot", "in", "import", "host_to_proot" -> {
                val targetIsDir = trimmedProot.endsWith("/") || prootHostFile.isDirectory
                val defaultName = SessionAttachmentStorage.inferDisplayName(trimmedHost).ifBlank { "file" }
                val finalTarget = if (targetIsDir) File(prootHostFile, defaultName) else prootHostFile
                finalTarget.parentFile?.mkdirs()
                val res = fileTransferProvider().transfer(
                    sourceUri = trimmedHost,
                    targetUri = finalTarget.toURI().toString(),
                    mode = mode,
                    overwrite = overwrite,
                    defaultFileName = defaultName,
                )
                if (!res.success) {
                    "Transfer to PRoot failed: ${res.message}"
                } else {
                    val finalPosix = if (targetIsDir) "${normalizedPosix.trimEnd('/')}/$defaultName" else normalizedPosix
                    "Transferred host -> PRoot ($mode): $trimmedHost -> $finalPosix (${res.bytesTransferred} bytes, sourceDeleted=${res.sourceDeleted})"
                }
            }

            "from_proot", "out", "export", "proot_to_host" -> {
                if (!prootHostFile.exists()) {
                    return "PRoot source path does not exist: $normalizedPosix"
                }
                val res = fileTransferProvider().transfer(
                    sourceUri = prootHostFile.toURI().toString(),
                    targetUri = trimmedHost,
                    mode = mode,
                    overwrite = overwrite,
                    defaultFileName = prootHostFile.name,
                )
                if (!res.success) {
                    "Transfer from PRoot failed: ${res.message}"
                } else {
                    "Transferred PRoot -> host ($mode): $normalizedPosix -> ${res.targetPath.ifBlank { res.targetUri }} (${res.bytesTransferred} bytes, sourceDeleted=${res.sourceDeleted})"
                }
            }

            else -> "Invalid direction '$direction'. Expected 'to_proot' or 'from_proot'."
        }
    }

    @Tool
    @LLMDescription(
        "Read a UTF-8 text file directly from the PRoot Linux filesystem (safer than shell quoting for scripts/configs/logs).",
    )
    suspend fun readProotTextFile(
        @LLMDescription("File path inside PRoot Linux (supports '~' and '~/...').")
        prootPath: String,
        @LLMDescription("Maximum characters to return (clamped to 256..64000, default 16000).")
        maxChars: Int = 16_000,
    ): String {
        val trimmed = prootPath.trim()
        if (trimmed.isEmpty()) return "prootPath must not be blank."
        val rootfsDir = rootfsDirProvider()
        val (normalizedPosix, hostFile) = resolveProotPath(
            prootPath = trimmed,
            rootfsDir = rootfsDir,
            defaultBaseDirPosix = defaultBaseDirPosix,
        )
        if (!hostFile.exists() || !hostFile.isFile) {
            return "File not found in PRoot Linux: $normalizedPosix"
        }
        val cap = maxChars.coerceIn(256, 64_000)
        return withContext(Dispatchers.IO) {
            val content = hostFile.readText(Charsets.UTF_8)
            if (content.length <= cap) {
                "path=$normalizedPosix\nlength=${content.length}\n---\n$content"
            } else {
                val omitted = content.length - cap
                "path=$normalizedPosix\nlength=${content.length} (truncated $omitted chars)\n---\n${content.take(cap)}"
            }
        }
    }

    @Tool
    @LLMDescription(
        "Write or append UTF-8 text directly to a file inside the PRoot Linux filesystem (automatically creates parent directories and avoids shell escaping issues).",
    )
    suspend fun writeProotTextFile(
        @LLMDescription("Target file path inside PRoot Linux (supports '~' and '~/...').")
        prootPath: String,
        @LLMDescription("UTF-8 text content to write.")
        content: String,
        @LLMDescription("If true, appends to existing file; default false (overwrites).")
        append: Boolean = false,
    ): String {
        val trimmed = prootPath.trim()
        if (trimmed.isEmpty()) return "prootPath must not be blank."
        val rootfsDir = rootfsDirProvider()
        val (normalizedPosix, hostFile) = resolveProotPath(
            prootPath = trimmed,
            rootfsDir = rootfsDir,
            defaultBaseDirPosix = defaultBaseDirPosix,
        )
        return withContext(Dispatchers.IO) {
            hostFile.parentFile?.mkdirs()
            if (append) {
                hostFile.appendText(content, Charsets.UTF_8)
            } else {
                hostFile.writeText(content, Charsets.UTF_8)
            }
            val bytes = hostFile.length()
            "Wrote ${if (append) "(appended) " else ""}PRoot file: $normalizedPosix ($bytes bytes)"
        }
    }

    companion object {
        const val TOOLSET_NAME = "proot_linux_file_io"

        /**
         * 将 PRoot Linux 路径规范化为 POSIX 绝对路径（相对于容器根 `/`），并映射为宿主 `rootfsDir` 下的 [File]。
         * 保证路径规范化后始终位于 `rootfsDir` 之内，不会逃逸到 Android 宿主文件系统。
         */
        internal fun resolveProotPath(
            prootPath: String,
            rootfsDir: File,
            defaultBaseDirPosix: String = ProotLinuxAgentLifecycleManager.DEFAULT_PROOT_HOME,
        ): Pair<String, File> {
            val expanded = expandTildeToProotHome(prootPath.trim())
            val combined = if (expanded.startsWith("/")) {
                expanded
            } else {
                val base = expandTildeToProotHome(defaultBaseDirPosix.trim().ifEmpty { "/" })
                "${base.trimEnd('/')}/$expanded"
            }
            val segments = ArrayDeque<String>()
            combined.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> segments.addLast(segment)
                }
            }
            val normalizedPosix = if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
            val hostFile = if (segments.isEmpty()) {
                rootfsDir
            } else {
                File(rootfsDir, segments.joinToString(File.separator))
            }
            return normalizedPosix to hostFile
        }
    }
}

/**
 * [ProotLinuxAgent] 与其专用 PRoot 终端的统一生命周期管理器：
 * 1. **协同管理 Agent 与专用 PRoot 终端**：与用户交互终端（`ProotConnectionManager`）隔离，
 *    拥有独立的常驻 [ProotConnection] 进程，自动建连、断线重连，并在 rootfs 重置或应用释放时同步销毁；
 * 2. **串行协调与状态可观测**：通过 [Mutex] 防止并发任务交叉污染同一专用终端的 Shell 状态，并暴露 [state]；
 * 3. **工作目录建议而非强制**：基于当前会话 ID 计算建议工作目录 `~/<session id>/`（`/root/<session id>`），
 *    但不强制隔离会话，允许模型对 PRoot 容器本身进行安装/配置/改造；
 * 4. **向 [PlannerAgent] 暴露工具桥接**：通过 [asToolSet] / [asToolRegistry] 提供 [ProotLinuxAgentManager]。
 */
class ProotLinuxAgentLifecycleManager(
    private val agent: ProotLinuxAgent = ProotLinuxAgent(),
    private val rootfsDirProvider: (() -> File)? = null,
    private val terminalFactory: ((initialWorkingDir: String) -> ProotLinuxTerminalHandle)? = null,
    private val fileTransferProvider: (() -> UriFileTransfer)? = null,
    private val safetyConfigProvider: (suspend () -> ModelSafetyConfig)? = null,
    private val llmApiEnvResolver: (suspend (requestedFilters: List<String>) -> ProotLlmApiEnvBundle)? = null,
) : AutoCloseable {

    private val terminalLock = Any()
    private val taskMutex = Mutex()
    private val generationCounter = AtomicLong(0L)
    private val completedTasksCounter = AtomicLong(0L)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var dedicatedTerminal: ProotLinuxTerminalHandle? = null

    @Volatile
    private var rootfsResetHook: Closeable? = null

    private var activeTaskEnvOverlay: Map<String, String> = emptyMap()
    private var envAppliedOnGeneration: Long = -1L

    private val _state = MutableStateFlow(ProotLinuxLifecycleState())
    val state: StateFlow<ProotLinuxLifecycleState> = _state.asStateFlow()

    val isClosed: Boolean
        get() = closed.get()

    val isTerminalConnected: Boolean
        get() = synchronized(terminalLock) { dedicatedTerminal?.isAlive == true }

    val manager: ProotLinuxAgentManager by lazy { ProotLinuxAgentManager(this) }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        synchronized(terminalLock) {
            if (closed.get()) {
                closed.set(false)
            }
            appContext = appCtx
            if (rootfsResetHook == null) {
                rootfsResetHook = TermuxProotRuntime.registerBeforeRootfsResetListener {
                    disconnectDedicatedTerminal()
                }
            }
            val rootfsPath = runCatching { TermuxProotRuntime.getRootfsDir(appCtx).absolutePath }.getOrDefault("")
            _state.value = _state.value.copy(
                status = if (_state.value.status == ProotLinuxLifecycleStatus.CLOSED) {
                    ProotLinuxLifecycleStatus.IDLE
                } else {
                    _state.value.status
                },
                rootfsPath = rootfsPath,
            )
        }
    }

    /**
     * 查询当前模型安全策略是否允许向 PRoot Linux 任务注入本机 LLM API Key。
     */
    suspend fun isLocalLlmApiKeyAccessAllowed(): Boolean {
        return resolveSafetyConfig().allowLocalLlmApiKeyAccess
    }

    /**
     * 获取或创建本管理器独占的持久化 PRoot 终端（若进程未启动或已退出则自动重连）。
     */
    fun acquireDedicatedTerminal(
        initialWorkingDir: String = DEFAULT_PROOT_HOME,
    ): ProotLinuxTerminalHandle = synchronized(terminalLock) {
        check(!closed.get()) { "ProotLinuxAgentLifecycleManager has been closed" }
        val existing = dedicatedTerminal
        if (existing != null && existing.isAlive) {
            updateTerminalSnapshotLocked(existing.snapshotStatus())
            return existing
        }
        existing?.let { runCatching { it.close() } }
        dedicatedTerminal = null
        envAppliedOnGeneration = -1L

        val created = createTerminalHandleLocked(initialWorkingDir)
        created.connect()
        dedicatedTerminal = created
        updateTerminalSnapshotLocked(created.snapshotStatus())
        created
    }

    /**
     * 在本管理器独占的 PRoot 终端中执行单条命令（自动建连、按需导出任务级临时环境变量并刷新生命周期状态）。
     */
    fun executeInDedicatedTerminal(
        command: String,
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
        initialWorkingDir: String = DEFAULT_PROOT_HOME,
    ): ShellCommandResult = synchronized(terminalLock) {
        if (closed.get()) {
            return ShellCommandResult.failure("ProotLinuxAgentLifecycleManager has been closed")
        }
        val handle = runCatching {
            acquireDedicatedTerminal(initialWorkingDir = initialWorkingDir)
        }.getOrElse { error ->
            return ShellCommandResult.failure(
                error.message ?: "Failed to start dedicated PRoot terminal",
            )
        }
        val envApplyError = ensureActiveTaskEnvAppliedLocked(handle)
        if (envApplyError != null) {
            return ShellCommandResult.failure(envApplyError)
        }
        val result = handle.execute(command = command, timeoutMillis = timeoutMillis)
        updateTerminalSnapshotLocked(handle.snapshotStatus())
        result
    }

    /**
     * 重置专用 PRoot 终端（断开并重新拉起新的 Shell 进程，容器文件系统保留）。
     */
    fun resetDedicatedTerminal(
        initialWorkingDir: String = DEFAULT_PROOT_HOME,
    ): ProotLinuxTerminalHandle = synchronized(terminalLock) {
        disconnectDedicatedTerminalLocked()
        acquireDedicatedTerminal(initialWorkingDir = initialWorkingDir)
    }

    /**
     * 断开专用 PRoot 终端（不销毁管理器本身，下次任务时可重新拉起）。
     */
    fun disconnectDedicatedTerminal() = synchronized(terminalLock) {
        disconnectDedicatedTerminalLocked()
    }

    fun terminalStatusSnapshot(): ProotConnectionStatus = synchronized(terminalLock) {
        dedicatedTerminal?.snapshotStatus() ?: ProotConnectionStatus(
            isConnected = false,
            rootfsPath = runCatching { resolveRootfsDir().absolutePath }.getOrDefault(""),
            workingDir = DEFAULT_PROOT_HOME,
        )
    }

    /**
     * 在本生命周期管理器内执行一次 PRoot Linux Agent 任务。
     *
     * @param includeLlmApiEnv 为 `true` 时受 [ModelSafetyConfig.allowLocalLlmApiKeyAccess] 权限管控，
     *                         在任务期间将本机已配置的 LLM API 环境变量临时注入专用 PRoot 终端，
     *                         并在任务结束（含异常或取消）后自动 `unset` 清理。
     * @param llmApiProviders  当 [includeLlmApiEnv] 为 `true` 时可选限定注入的服务商或角色名称列表；
     *                         留空表示注入全部本机已配置的服务商与角色。
     */
    suspend fun runTask(
        task: String,
        context: String = "",
        session: ChatSession? = null,
        sessionIdOverride: String? = null,
        fileAttachmentIds: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        includeLlmApiEnv: Boolean = false,
        llmApiProviders: List<String> = emptyList(),
    ): ProotLinuxExecutionResult {
        val normalizedTask = task.trim()
        require(normalizedTask.isNotEmpty()) { "PRoot Linux task must not be blank" }
        check(!closed.get()) { "ProotLinuxAgentLifecycleManager has been closed" }

        val llmEnvBundle: ProotLlmApiEnvBundle? = if (includeLlmApiEnv) {
            val safetyConfig = resolveSafetyConfig()
            val blockReason = safetyConfig.evaluateToolCall("executeProotLinuxTaskWithLlmApi")
            if (blockReason != null || !safetyConfig.allowLocalLlmApiKeyAccess) {
                throw SecurityException(
                    blockReason
                        ?: "Blocked by Model Safety Policy (${safetyConfig.safetyLevel.name}): " +
                        "local LLM API key access is disabled in Settings -> 模型安全性.",
                )
            }
            val resolved = resolveLlmApiEnv(llmApiProviders)
            if (resolved.isEmpty) {
                val missingHint = if (resolved.missingRequestedFilters.isNotEmpty()) {
                    " (unmatched or unconfigured: ${resolved.missingRequestedFilters.joinToString(", ")})"
                } else {
                    ""
                }
                throw IllegalStateException(
                    "No matching local LLM API key is configured$missingHint. " +
                        "Please save an API key in Settings -> API Key 管理 first.",
                )
            }
            resolved
        } else {
            null
        }

        return taskMutex.withLock {
            check(!closed.get()) { "ProotLinuxAgentLifecycleManager has been closed" }
            val resolvedSessionId = sessionIdOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: session?.id?.trim()?.takeIf { it.isNotEmpty() }
            val (suggestedDisplay, suggestedPosix) = suggestWorkingDir(resolvedSessionId)
            val rootfsDir = resolveRootfsDir()

            _state.value = _state.value.copy(
                status = ProotLinuxLifecycleStatus.RUNNING,
                rootfsPath = rootfsDir.absolutePath,
                activeSessionId = resolvedSessionId,
                suggestedWorkingDir = suggestedPosix,
                lastError = null,
            )

            if (llmEnvBundle != null && !llmEnvBundle.isEmpty) {
                synchronized(terminalLock) {
                    activeTaskEnvOverlay = llmEnvBundle.envVars
                    envAppliedOnGeneration = -1L
                }
            }

            val terminalToolSet = ProotLinuxTerminalToolSet(
                lifecycleManager = this,
                suggestedWorkingDirDisplay = suggestedDisplay,
                suggestedWorkingDirPosix = suggestedPosix,
                secretValuesToRedact = llmEnvBundle?.secretValues.orEmpty(),
            )
            val fileIoToolSet = ProotLinuxFileIoToolSet(
                rootfsDirProvider = ::resolveRootfsDir,
                session = session,
                defaultBaseDirPosix = suggestedPosix,
                fileTransferProvider = ::resolveFileTransfer,
            )

            try {
                val result = agent.execute(
                    task = normalizedTask,
                    context = context,
                    session = session,
                    sessionId = resolvedSessionId,
                    suggestedWorkingDirDisplay = suggestedDisplay,
                    suggestedWorkingDirPosix = suggestedPosix,
                    terminalToolSet = terminalToolSet,
                    fileIoToolSet = fileIoToolSet,
                    fileAttachmentIds = fileAttachmentIds,
                    imageUris = imageUris,
                    llmApiEnvPromptSection = llmEnvBundle?.toSafePromptSection(),
                    injectedLlmEnvVars = llmEnvBundle?.envVarNames.orEmpty(),
                )
                val completed = completedTasksCounter.incrementAndGet()
                val termSnapshot = terminalStatusSnapshot()
                _state.value = _state.value.copy(
                    status = if (closed.get()) ProotLinuxLifecycleStatus.CLOSED else ProotLinuxLifecycleStatus.IDLE,
                    terminalConnected = termSnapshot.isConnected,
                    terminalGeneration = termSnapshot.sessionGeneration,
                    executedCommandCount = termSnapshot.executedCommandCount,
                    completedTaskCount = completed,
                    activeSessionId = null,
                    lastError = null,
                )
                result
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(
                    status = if (closed.get()) ProotLinuxLifecycleStatus.CLOSED else ProotLinuxLifecycleStatus.IDLE,
                    activeSessionId = null,
                )
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    status = if (closed.get()) ProotLinuxLifecycleStatus.CLOSED else ProotLinuxLifecycleStatus.IDLE,
                    activeSessionId = null,
                    lastError = t.message ?: t::class.simpleName,
                )
                throw t
            } finally {
                if (llmEnvBundle != null && !llmEnvBundle.isEmpty) {
                    clearActiveTaskEnvOverlayAndScrubTerminal(llmEnvBundle.envVarNames)
                }
            }
        }
    }

    /**
     * 构造供 [PlannerAgent] 或其他上层 Agent 挂载的工具集桥接层。
     */
    fun asToolSet(session: ChatSession? = null): ProotLinuxAgentManager = ProotLinuxAgentManager(
        lifecycle = this,
        session = session,
    )

    fun asToolRegistry(session: ChatSession? = null): ToolRegistry = ToolRegistry {
        tools(asToolSet(session = session))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(terminalLock) {
            activeTaskEnvOverlay = emptyMap()
            envAppliedOnGeneration = -1L
            runCatching { rootfsResetHook?.close() }
            rootfsResetHook = null
            disconnectDedicatedTerminalLocked()
            _state.value = _state.value.copy(
                status = ProotLinuxLifecycleStatus.CLOSED,
                terminalConnected = false,
                activeSessionId = null,
            )
        }
    }

    internal fun resetForTest() {
        synchronized(terminalLock) {
            activeTaskEnvOverlay = emptyMap()
            envAppliedOnGeneration = -1L
            runCatching { rootfsResetHook?.close() }
            rootfsResetHook = null
            disconnectDedicatedTerminalLocked()
            appContext = null
            closed.set(false)
            completedTasksCounter.set(0L)
            _state.value = ProotLinuxLifecycleState()
        }
    }

    private suspend fun resolveSafetyConfig(): ModelSafetyConfig {
        safetyConfigProvider?.let { return it.invoke() }
        return if (ModelSafetyRepository.isReady) {
            ModelSafetyRepository.getSafetyConfig()
        } else {
            ModelSafetyConfig.DEFAULT
        }
    }

    private suspend fun resolveLlmApiEnv(requestedFilters: List<String>): ProotLlmApiEnvBundle {
        llmApiEnvResolver?.let { return it.invoke(requestedFilters) }
        return ProotLlmApiEnvResolver.resolve(requestedFilters = requestedFilters)
    }

    private fun ensureActiveTaskEnvAppliedLocked(handle: ProotLinuxTerminalHandle): String? {
        if (activeTaskEnvOverlay.isEmpty()) return null
        val currentGen = handle.snapshotStatus().sessionGeneration
        if (envAppliedOnGeneration == currentGen) return null
        val exportCmd = ProotLlmApiEnvResolver.buildShellExportCommand(activeTaskEnvOverlay)
        if (exportCmd.isEmpty()) {
            envAppliedOnGeneration = currentGen
            return null
        }
        val res = handle.execute(command = exportCmd, timeoutMillis = ENV_SYNC_TIMEOUT_MS)
        if (!res.isSuccess) {
            return "Failed to inject LLM API environment variables into dedicated PRoot terminal (exitCode=${res.exitCode})."
        }
        envAppliedOnGeneration = currentGen
        return null
    }

    private fun clearActiveTaskEnvOverlayAndScrubTerminal(varNames: List<String>) {
        synchronized(terminalLock) {
            activeTaskEnvOverlay = emptyMap()
            envAppliedOnGeneration = -1L
            val handle = dedicatedTerminal
            if (handle != null && handle.isAlive && varNames.isNotEmpty()) {
                val unsetCmd = ProotLlmApiEnvResolver.buildShellUnsetCommand(varNames)
                if (unsetCmd.isNotEmpty()) {
                    runCatching {
                        handle.execute(command = unsetCmd, timeoutMillis = ENV_SYNC_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    private fun disconnectDedicatedTerminalLocked() {
        val current = dedicatedTerminal
        dedicatedTerminal = null
        envAppliedOnGeneration = -1L
        current?.let { runCatching { it.close() } }
        _state.value = _state.value.copy(
            terminalConnected = false,
        )
    }

    private fun updateTerminalSnapshotLocked(snapshot: ProotConnectionStatus) {
        _state.value = _state.value.copy(
            terminalConnected = snapshot.isConnected,
            terminalGeneration = snapshot.sessionGeneration,
            rootfsPath = snapshot.rootfsPath.ifBlank { _state.value.rootfsPath },
            executedCommandCount = snapshot.executedCommandCount,
        )
    }

    private fun resolveRootfsDir(): File {
        rootfsDirProvider?.let { return it() }
        val ctx = appContext
            ?: error("ProotLinuxAgentLifecycleManager is not initialized; call init(context) or provide rootfsDirProvider")
        return TermuxProotRuntime.ensureRootfsInitialized(ctx)
    }

    private fun resolveFileTransfer(): UriFileTransfer {
        fileTransferProvider?.let { return it() }
        val ctx = appContext ?: return SessionAttachmentStorage.defaultFileTransfer
        return DefaultUriFileTransfer(
            contextProvider = { ctx },
            explicitDirectFileAccessChecker = {
                runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            },
            privilegedShellRunner = { cmd -> PrivilegeBackend.execShizukuOrRootShell(cmd) },
        )
    }

    private fun createTerminalHandleLocked(initialWorkingDir: String): ProotLinuxTerminalHandle {
        terminalFactory?.let { return it(initialWorkingDir) }
        val ctx = appContext
            ?: error("ProotLinuxAgentLifecycleManager is not initialized; call init(context) or provide terminalFactory")
        val prootFile = TermuxProotRuntime.getProotExecutable(ctx)
        if (!prootFile.exists()) {
            error("PRoot executable not found at ${prootFile.absolutePath}")
        }
        val rootfsDir = TermuxProotRuntime.ensureRootfsInitialized(ctx)
        val effectiveWorkDir = expandTildeToProotHome(initialWorkingDir.ifBlank { DEFAULT_PROOT_HOME })
        val shellExecutable = TermuxProotRuntime.resolvePreferredInteractiveShell(rootfsDir, "/bin/sh")
        val interactiveArgs = TermuxProotRuntime.buildProotInteractiveShellArgs(
            context = ctx,
            workingDir = effectiveWorkDir,
            shellExecutable = shellExecutable,
        )
        val env = TermuxProotRuntime.buildDefaultEnvironment(context = ctx)
        val gen = generationCounter.incrementAndGet()
        val connection = ProotConnection(
            connectionId = "proot-agent-$gen",
            generation = gen,
            rootfsDir = rootfsDir,
            shellExecutable = shellExecutable,
            initialWorkingDir = effectiveWorkDir,
            commandArgs = interactiveArgs,
            environment = env,
        )
        return DedicatedProotConnectionHandle(connection)
    }

    companion object {
        const val DEFAULT_PROOT_HOME = "/root"
        private const val ENV_SYNC_TIMEOUT_MS = 10_000L

        val default: ProotLinuxAgentLifecycleManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ProotLinuxAgentLifecycleManager()
        }

        fun init(context: Context) {
            default.init(context)
        }

        internal fun resetForTest() {
            default.resetForTest()
        }

        /**
         * 根据会话 ID 生成建议的 PRoot 工作目录（`~/<sessionId>` 与 `/root/<sessionId>`）。
         * 仅用于引导提示，不强制限制智能体访问或修改容器其他路径。
         */
        fun suggestWorkingDir(sessionId: String?): Pair<String, String> {
            val sanitized = sanitizeSessionSegment(sessionId)
            return if (sanitized.isEmpty()) {
                "~" to DEFAULT_PROOT_HOME
            } else {
                "~/$sanitized" to "$DEFAULT_PROOT_HOME/$sanitized"
            }
        }

        internal fun sanitizeSessionSegment(sessionId: String?): String {
            val trimmed = sessionId?.trim().orEmpty()
            if (trimmed.isEmpty()) return ""
            return trimmed.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trim('.')
                .take(80)
        }
    }
}


internal fun expandTildeToProotHome(rawPath: String): String {
    val trimmed = rawPath.trim()
    return when {
        trimmed == "~" -> ProotLinuxAgentLifecycleManager.DEFAULT_PROOT_HOME
        trimmed.startsWith("~/") -> ProotLinuxAgentLifecycleManager.DEFAULT_PROOT_HOME + trimmed.removePrefix("~")
        else -> trimmed
    }
}
