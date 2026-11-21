package top.aaa000111.maid.proot

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.aaa000111.maid.shizuku.ShellCommandExecutor
import top.aaa000111.maid.shizuku.ShellCommandResult
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PRoot 持久连接状态快照。
 */
data class ProotConnectionStatus(
    val isConnected: Boolean = false,
    val sessionGeneration: Long = 0L,
    val rootfsPath: String = "",
    val shellExecutable: String = "/bin/sh",
    val workingDir: String = "/root",
    val connectedAtMillis: Long? = null,
    val lastCommandAtMillis: Long? = null,
    val executedCommandCount: Long = 0L,
)

/**
 * 真正持有单个持久 `proot` 子进程连接（常驻 Shell 会话）的会话类。
 *
 * 特性：
 * 1. **持久持有底层 `Process` 与 I/O 管道**：持有常驻 `libproot.so ... /bin/sh` 进程的 `stdin` ([OutputStream])、`stdout` 与 `stderr` 后台采集线程。
 * 2. **跨命令保持会话状态**：在同一个 [ProotConnection] 内连续调用 [execute]，当前工作目录（`cd`）、导出的环境变量（`export`）、Shell 变量和函数均会在后续命令间完整保留。
 * 3. **边界哨兵同步与独立采集 stdout / stderr**：每次执行命令时通过唯一哨兵标记精确截取本次命令的 `stdout`、`stderr` 与退出码 `exitCode`，不会阻塞或串流。
 * 4. **实时原始字节流与交互式终端对接 (`vtfx`)**：支持通过 [addRawOutputListener] 订阅底层 `stdout`/`stderr` 的实时输出字节流（在后台哨兵命令执行期间自动静默哨兵文本），并通过 [sendRawBytes] 直接向 `stdin` 写入终端输入字节。
 * 5. **超时自动恢复**：若某条命令执行超时，会自动强制终止卡死的 `proot` 进程并清理连接，下次执行时可无缝重连。
 */
class ProotConnection(
    val connectionId: String = UUID.randomUUID().toString(),
    val generation: Long = 1L,
    val rootfsDir: File,
    val shellExecutable: String = "/bin/sh",
    val initialWorkingDir: String = "/root",
    val commandArgs: List<String>,
    val environment: Map<String, String>,
    val maxOutputBytesPerCommand: Int = ShellCommandExecutor.DEFAULT_MAX_OUTPUT_BYTES,
    private val processStarter: (commandArgs: List<String>, env: Map<String, String>) -> Process = ::defaultStartProcess,
) : Closeable {

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val commandCounter = AtomicLong(0L)
    private val executingSentinelCommand = AtomicBoolean(false)
    private val rawOutputListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinStream: OutputStream? = null

    private var stdoutPump: StreamPump? = null
    private var stderrPump: StreamPump? = null

    @Volatile
    private var connectedAtMillis: Long? = null

    @Volatile
    private var lastCommandAtMillis: Long? = null

    /**
     * 当前底层 `proot` 进程是否存活且连接处于打开状态。
     */
    val isAlive: Boolean
        get() = !closed.get() && (process?.isAlive == true)

    /**
     * 启动并建立持久 `proot` 进程连接（幂等：若已连接且存活则直接返回）。
     */
    fun connect(): Boolean = synchronized(lock) {
        if (closed.get()) return false
        if (process?.isAlive == true && stdinStream != null) {
            return true
        }
        closeProcessLocked()

        val startedProcess = processStarter(commandArgs, environment)
        val outPump = StreamPump(
            name = "proot-stdout-$connectionId",
            inputStream = startedProcess.inputStream,
            maxRetainedBytes = maxOutputBytesPerCommand * 2,
            onChunkRead = ::dispatchStdoutChunk,
        ).apply { start() }
        val errPump = StreamPump(
            name = "proot-stderr-$connectionId",
            inputStream = startedProcess.errorStream,
            maxRetainedBytes = maxOutputBytesPerCommand * 2,
            onChunkRead = ::dispatchStderrChunk,
        ).apply { start() }

        process = startedProcess
        stdinStream = startedProcess.outputStream
        stdoutPump = outPump
        stderrPump = errPump
        connectedAtMillis = System.currentTimeMillis()
        return startedProcess.isAlive
    }

    /**
     * 注册实时原始字节输出监听器（供 `vtfx` 交互式终端等实时消费 `stdout` / `stderr`）。
     * 返回 [Closeable] 用于取消订阅。
     */
    fun addRawOutputListener(listener: (ByteArray) -> Unit): Closeable {
        rawOutputListeners.add(listener)
        return Closeable {
            rawOutputListeners.remove(listener)
        }
    }

    private fun dispatchStdoutChunk(chunk: ByteArray) {
        if (executingSentinelCommand.get() || rawOutputListeners.isEmpty()) return
        val normalized = normalizeLfToCrLfForTerminal(chunk)
        for (listener in rawOutputListeners) {
            runCatching { listener(normalized) }
        }
    }

    private fun dispatchStderrChunk(chunk: ByteArray) {
        if (executingSentinelCommand.get() || rawOutputListeners.isEmpty()) return
        val text = String(chunk, StandardCharsets.UTF_8)
        var filteredText = text

        // 过滤 bash 在无 PTY 启动时的作业控制与终端进程组告警
        if (filteredText.contains("bash: cannot set terminal process group") ||
            filteredText.contains("bash: no job control in this shell")
        ) {
            filteredText = filteredText
                .lineSequence()
                .filterNot { line ->
                    val trimmed = line.trim()
                    trimmed.startsWith("bash: cannot set terminal process group") ||
                        trimmed.startsWith("bash: no job control in this shell")
                }
                .joinToString("\n")
        }

        if (filteredText.isEmpty()) return

        val normalized = normalizeLfToCrLfForTerminal(filteredText.toByteArray(StandardCharsets.UTF_8))
        for (listener in rawOutputListeners) {
            runCatching { listener(normalized) }
        }
    }

    private fun emitTerminalEchoForSentinelCommand(
        command: String,
        stdoutPayload: String,
        stderrPayload: String,
    ) {
        if (rawOutputListeners.isEmpty()) return
        val combined = buildString {
            append("\r\n").append(command.trim()).append("\r\n")
            if (stdoutPayload.isNotEmpty()) {
                append(stdoutPayload.replace("\r\n", "\n").replace("\n", "\r\n"))
                if (!stdoutPayload.endsWith("\n")) append("\r\n")
            }
            if (stderrPayload.isNotEmpty()) {
                append(stderrPayload.replace("\r\n", "\n").replace("\n", "\r\n"))
                if (!stderrPayload.endsWith("\n")) append("\r\n")
            }
            append("\u001b[01;32mroot@maid-proot\u001b[00m:\u001b[01;34m~\u001b[00m# ")
        }
        val bytes = combined.toByteArray(StandardCharsets.UTF_8)
        for (listener in rawOutputListeners) {
            runCatching { listener(bytes) }
        }
    }

    /**
     * 在当前持久 `proot` 连接中执行一条 Shell 命令，保留 `cd` / `export` 等会话上下文。
     */
    fun execute(
        command: String,
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
    ): ShellCommandResult = synchronized(lock) {
        if (command.isBlank()) {
            return ShellCommandResult.failure("Command must not be blank")
        }
        if (closed.get()) {
            return ShellCommandResult.failure("ProotConnection ($connectionId) is already closed")
        }

        val startNs = System.nanoTime()
        if (!connect()) {
            return ShellCommandResult.failure(
                message = "Failed to start or connect to persistent PRoot process",
                durationMs = elapsedMillis(startNs),
            )
        }

        val activeProcess = process
            ?: return ShellCommandResult.failure("PRoot process is unavailable")
        val stdin = stdinStream
            ?: return ShellCommandResult.failure("PRoot stdin stream is unavailable")
        val outPump = stdoutPump
            ?: return ShellCommandResult.failure("PRoot stdout pump is unavailable")
        val errPump = stderrPump
            ?: return ShellCommandResult.failure("PRoot stderr pump is unavailable")

        // 清空上一条命令可能残留的异步缓冲区噪音
        outPump.resetBuffer()
        errPump.resetBuffer()

        val token = UUID.randomUUID().toString().replace("-", "")
        val outBeginMarker = "__MAID_PROOT_OUT_BEGIN_${token}__"
        val outEndPrefix = "__MAID_PROOT_OUT_END_${token}_"
        val markerSuffix = "__"
        val errEndMarker = "__MAID_PROOT_ERR_END_${token}__"

        val script = buildString {
            append("MAID_SILENT_PROMPT=1\n")
            append("printf '%s\\n' '").append(outBeginMarker).append("'\n")
            append(command).append("\n")
            append("__maid_ec=$?\n")
            append("unset MAID_SILENT_PROMPT\n")
            append("printf '\\n%s%s%s\\n' '").append(outEndPrefix).append("' \"\$__maid_ec\" '").append(markerSuffix).append("'\n")
            append("printf '\\n%s\\n' '").append(errEndMarker).append("' >&2\n")
        }

        executingSentinelCommand.set(true)
        try {
            try {
                stdin.write(script.toByteArray(StandardCharsets.UTF_8))
                stdin.flush()
            } catch (e: IOException) {
                closeProcessLocked()
                return ShellCommandResult.failure(
                    message = "Failed to write command to PRoot stdin: ${e.message ?: e::class.java.simpleName}",
                    durationMs = elapsedMillis(startNs),
                )
            }

            val effectiveTimeoutMs = if (timeoutMillis <= 0L) ShellCommandExecutor.DEFAULT_TIMEOUT_MS else timeoutMillis
            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveTimeoutMs)

            val stdoutMatch = outPump.awaitEndMarker(
                beginMarker = outBeginMarker,
                endPrefix = outEndPrefix,
                endSuffix = markerSuffix,
                process = activeProcess,
                deadlineNs = deadlineNs,
            )

            if (stdoutMatch.timedOut) {
                val partialOut = outPump.snapshotCleanedText(outBeginMarker)
                val partialErr = errPump.snapshotCleanedText(null)
                // 超时后该常驻 shell 中可能仍有卡死的前台子进程，关闭当前进程以便下次自动重连
                closeProcessLocked()
                return ShellCommandResult(
                    exitCode = -1,
                    stdout = partialOut.text,
                    stderr = partialErr.text,
                    timedOut = true,
                    stdoutTruncated = partialOut.truncated,
                    stderrTruncated = partialErr.truncated,
                    durationMs = elapsedMillis(startNs),
                    errorMessage = "Command timed out after ${effectiveTimeoutMs}ms in persistent PRoot connection",
                )
            }

            if (!stdoutMatch.completed) {
                val partialOut = outPump.snapshotCleanedText(outBeginMarker)
                val partialErr = errPump.snapshotCleanedText(null)
                val exitCode = runCatching { activeProcess.exitValue() }.getOrDefault(-1)
                closeProcessLocked()
                return ShellCommandResult(
                    exitCode = exitCode,
                    stdout = partialOut.text,
                    stderr = partialErr.text,
                    timedOut = false,
                    stdoutTruncated = partialOut.truncated,
                    stderrTruncated = partialErr.truncated,
                    durationMs = elapsedMillis(startNs),
                    errorMessage = if (exitCode != 0 && partialErr.text.isNotBlank()) partialErr.text else null,
                )
            }

            // stdout 结束哨兵已到达，等待 stderr 结束哨兵（短窗口同步，避免 stderr 漏采）
            val stderrDeadlineNs = minOf(
                deadlineNs,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500L),
            )
            val stderrMatch = errPump.awaitSimpleEndMarker(
                endMarker = errEndMarker,
                process = activeProcess,
                deadlineNs = stderrDeadlineNs,
            )

            commandCounter.incrementAndGet()
            lastCommandAtMillis = System.currentTimeMillis()
            emitTerminalEchoForSentinelCommand(
                command = command,
                stdoutPayload = stdoutMatch.payload,
                stderrPayload = stderrMatch.payload,
            )

            return ShellCommandResult(
                exitCode = stdoutMatch.exitCode,
                stdout = stdoutMatch.payload,
                stderr = stderrMatch.payload,
                timedOut = false,
                stdoutTruncated = stdoutMatch.truncated,
                stderrTruncated = stderrMatch.truncated,
                durationMs = elapsedMillis(startNs),
                errorMessage = null,
            )
        } finally {
            executingSentinelCommand.set(false)
        }
    }

    /**
     * 直接向当前持久 `proot` 进程的 `stdin` 写入原始字节数组（供 `vtfx` `HostConnection` 实时转发）。
     * 由于管道模式下的 `/bin/sh` 没有内核 PTY 的 `icrnl` 转换，单独的 `\r` 会自动转换为 `\n` 以便 Shell 正常执行回车。
     */
    fun sendRawBytes(bytes: ByteArray): Boolean = synchronized(lock) {
        if (bytes.isEmpty()) return true
        if (closed.get() || !connect()) return false
        return try {
            val out = stdinStream ?: return false
            val normalized = normalizeCrToLfForPipeShell(bytes)
            out.write(normalized)
            out.flush()
            lastCommandAtMillis = System.currentTimeMillis()
            true
        } catch (_: IOException) {
            closeProcessLocked()
            false
        }
    }

    /**
     * 直接向当前持久 `proot` 进程的 `stdin` 写入原始文本输入数据。
     */
    fun sendRawInput(data: String): Boolean {
        return sendRawBytes(data.toByteArray(StandardCharsets.UTF_8))
    }

    fun snapshotStatus(): ProotConnectionStatus {
        return ProotConnectionStatus(
            isConnected = isAlive,
            sessionGeneration = generation,
            rootfsPath = rootfsDir.absolutePath,
            shellExecutable = shellExecutable,
            workingDir = initialWorkingDir,
            connectedAtMillis = connectedAtMillis,
            lastCommandAtMillis = lastCommandAtMillis,
            executedCommandCount = commandCounter.get(),
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(lock) {
                closeProcessLocked()
            }
            rawOutputListeners.clear()
        }
    }

    private fun closeProcessLocked() {
        val currentStdin = stdinStream
        stdinStream = null
        runCatching {
            currentStdin?.write("exit\n".toByteArray(StandardCharsets.UTF_8))
            currentStdin?.flush()
        }
        runCatching { currentStdin?.close() }

        val currentProcess = process
        process = null
        if (currentProcess != null) {
            runCatching {
                if (!currentProcess.waitFor(150L, TimeUnit.MILLISECONDS)) {
                    currentProcess.destroy()
                    if (!currentProcess.waitFor(200L, TimeUnit.MILLISECONDS)) {
                        currentProcess.destroyForcibly()
                        currentProcess.waitFor(500L, TimeUnit.MILLISECONDS)
                    }
                }
            }.onFailure {
                runCatching { currentProcess.destroyForcibly() }
            }
        }

        stdoutPump?.close()
        stderrPump?.close()
        stdoutPump = null
        stderrPump = null
    }

    private data class MarkerWaitResult(
        val completed: Boolean,
        val timedOut: Boolean,
        val exitCode: Int,
        val payload: String,
        val truncated: Boolean,
    )

    private data class CleanedSnapshot(
        val text: String,
        val truncated: Boolean,
    )

    private class StreamPump(
        name: String,
        private val inputStream: InputStream,
        private val maxRetainedBytes: Int,
        private val onChunkRead: (ByteArray) -> Unit = {},
    ) : Closeable {
        private val monitor = Object()
        private val buffer = ByteArrayOutputStream(8192)
        private var truncated = false
        private var eof = false
        private val stopped = AtomicBoolean(false)

        private val readerThread = Thread(
            {
                val chunk = ByteArray(8192)
                try {
                    while (!stopped.get()) {
                        val read = inputStream.read(chunk)
                        if (read == -1) {
                            break
                        }
                        val slice = chunk.copyOfRange(0, read)
                        synchronized(monitor) {
                            if (buffer.size() + read > maxRetainedBytes) {
                                val currentBytes = buffer.toByteArray()
                                buffer.reset()
                                val keep = (maxRetainedBytes / 2).coerceAtLeast(1024)
                                if (currentBytes.size > keep) {
                                    buffer.write(currentBytes, currentBytes.size - keep, keep)
                                }
                                truncated = true
                            }
                            val allowed = (maxRetainedBytes - buffer.size()).coerceAtLeast(0)
                            val toWrite = minOf(read, allowed)
                            if (toWrite > 0) {
                                buffer.write(chunk, 0, toWrite)
                            }
                            if (toWrite < read) {
                                truncated = true
                            }
                            monitor.notifyAll()
                        }
                        runCatching { onChunkRead(slice) }
                    }
                } catch (_: IOException) {
                    // Process closed stream
                } finally {
                    synchronized(monitor) {
                        eof = true
                        monitor.notifyAll()
                    }
                }
            },
            name,
        ).apply {
            isDaemon = true
        }

        fun start() {
            readerThread.start()
        }

        fun resetBuffer() {
            synchronized(monitor) {
                buffer.reset()
                truncated = false
            }
        }

        fun awaitEndMarker(
            beginMarker: String,
            endPrefix: String,
            endSuffix: String,
            process: Process,
            deadlineNs: Long,
        ): MarkerWaitResult {
            synchronized(monitor) {
                while (true) {
                    val raw = String(buffer.toByteArray(), StandardCharsets.UTF_8)
                    val endIdx = raw.indexOf(endPrefix)
                    if (endIdx >= 0) {
                        val afterPrefixIdx = endIdx + endPrefix.length
                        val suffixIdx = raw.indexOf(endSuffix, afterPrefixIdx)
                        if (suffixIdx >= afterPrefixIdx) {
                            val exitCodeStr = raw.substring(afterPrefixIdx, suffixIdx).trim()
                            val exitCode = exitCodeStr.toIntOrNull() ?: 0
                            val beginIdx = raw.indexOf(beginMarker)
                            val contentStart = if (beginIdx >= 0 && beginIdx < endIdx) {
                                val afterBegin = beginIdx + beginMarker.length
                                if (afterBegin < raw.length && raw[afterBegin] == '\n') {
                                    afterBegin + 1
                                } else if (
                                    afterBegin + 1 < raw.length &&
                                    raw[afterBegin] == '\r' &&
                                    raw[afterBegin + 1] == '\n'
                                ) {
                                    afterBegin + 2
                                } else {
                                    afterBegin
                                }
                            } else {
                                0
                            }
                            var contentEnd = endIdx
                            if (contentEnd > contentStart && raw[contentEnd - 1] == '\n') {
                                contentEnd -= 1
                                if (contentEnd > contentStart && raw[contentEnd - 1] == '\r') {
                                    contentEnd -= 1
                                }
                            }
                            val payload = raw.substring(contentStart, contentEnd.coerceAtLeast(contentStart))
                            val wasTruncated = truncated
                            buffer.reset()
                            truncated = false
                            return MarkerWaitResult(
                                completed = true,
                                timedOut = false,
                                exitCode = exitCode,
                                payload = payload,
                                truncated = wasTruncated,
                            )
                        }
                    }

                    if (eof && !process.isAlive) {
                        return MarkerWaitResult(
                            completed = false,
                            timedOut = false,
                            exitCode = -1,
                            payload = "",
                            truncated = truncated,
                        )
                    }

                    val remainingNs = deadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) {
                        return MarkerWaitResult(
                            completed = false,
                            timedOut = true,
                            exitCode = -1,
                            payload = "",
                            truncated = truncated,
                        )
                    }

                    val waitMs = (remainingNs / 1_000_000L).coerceIn(5L, 50L)
                    monitor.wait(waitMs)
                }
            }
        }

        fun awaitSimpleEndMarker(
            endMarker: String,
            process: Process,
            deadlineNs: Long,
        ): MarkerWaitResult {
            synchronized(monitor) {
                while (true) {
                    val raw = String(buffer.toByteArray(), StandardCharsets.UTF_8)
                    val endIdx = raw.indexOf(endMarker)
                    if (endIdx >= 0) {
                        var contentEnd = endIdx
                        if (contentEnd > 0 && raw[contentEnd - 1] == '\n') {
                            contentEnd -= 1
                            if (contentEnd > 0 && raw[contentEnd - 1] == '\r') {
                                contentEnd -= 1
                            }
                        }
                        val payload = raw.substring(0, contentEnd.coerceAtLeast(0))
                        val wasTruncated = truncated
                        buffer.reset()
                        truncated = false
                        return MarkerWaitResult(
                            completed = true,
                            timedOut = false,
                            exitCode = 0,
                            payload = payload,
                            truncated = wasTruncated,
                        )
                    }

                    val remainingNs = deadlineNs - System.nanoTime()
                    if ((eof && !process.isAlive) || remainingNs <= 0L) {
                        var cleaned = raw.replace(endMarker, "").trimEnd('\r', '\n')
                        if (cleaned == "bash: no job control in this shell" ||
                            cleaned == "bash: cannot set terminal process group (-1): Inappropriate ioctl for device\nbash: no job control in this shell"
                        ) {
                            cleaned = ""
                        }
                        val wasTruncated = truncated
                        buffer.reset()
                        truncated = false
                        return MarkerWaitResult(
                            completed = false,
                            timedOut = remainingNs <= 0L,
                            exitCode = 0,
                            payload = cleaned,
                            truncated = wasTruncated,
                        )
                    }

                    val waitMs = (remainingNs / 1_000_000L).coerceIn(5L, 50L)
                    monitor.wait(waitMs)
                }
            }
        }

        fun snapshotCleanedText(beginMarker: String?): CleanedSnapshot {
            synchronized(monitor) {
                var raw = String(buffer.toByteArray(), StandardCharsets.UTF_8)
                if (beginMarker != null) {
                    val idx = raw.indexOf(beginMarker)
                    if (idx >= 0) {
                        raw = raw.substring(idx + beginMarker.length).trimStart('\r', '\n')
                    }
                }
                val wasTruncated = truncated
                buffer.reset()
                truncated = false
                return CleanedSnapshot(text = raw, truncated = wasTruncated)
            }
        }

        override fun close() {
            if (stopped.compareAndSet(false, true)) {
                runCatching { inputStream.close() }
                readerThread.interrupt()
            }
        }
    }

    companion object {
        internal fun defaultStartProcess(
            commandArgs: List<String>,
            env: Map<String, String>,
        ): Process {
            val builder = ProcessBuilder(commandArgs)
            val processEnv = builder.environment()
            processEnv.putAll(env)
            return builder.start()
        }

        private fun elapsedMillis(startNs: Long): Long {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        }

        /**
         * 将管道输出中的独立 `\n` 转换为 `\r\n` 以满足 VT 终端模拟器 (`vtfx`) 的换行回车规范。
         */
        internal fun normalizeLfToCrLfForTerminal(bytes: ByteArray): ByteArray {
            var bareLfCount = 0
            for (i in bytes.indices) {
                if (bytes[i] == '\n'.code.toByte() && (i == 0 || bytes[i - 1] != '\r'.code.toByte())) {
                    bareLfCount++
                }
            }
            if (bareLfCount == 0) return bytes
            val out = ByteArray(bytes.size + bareLfCount)
            var outIdx = 0
            for (i in bytes.indices) {
                val b = bytes[i]
                if (b == '\n'.code.toByte() && (i == 0 || bytes[i - 1] != '\r'.code.toByte())) {
                    out[outIdx++] = '\r'.code.toByte()
                }
                out[outIdx++] = b
            }
            return out
        }

        /**
         * 管道模式下的 `/bin/sh` 期望以 `\n` 作为命令行结束符，而 VT 终端键盘回车发送 `\r`（或 `\r\n`）。
         */
        internal fun normalizeCrToLfForPipeShell(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(bytes.size)
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i]
                if (b == '\r'.code.toByte()) {
                    out.write('\n'.code)
                    if (i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) {
                        i += 2
                        continue
                    }
                } else {
                    out.write(b.toInt())
                }
                i++
            }
            return out.toByteArray()
        }
    }
}

/**
 * 全局单一 `rootfs` (`<filesDir>/rootfs`) 的 PRoot 持久连接管理器。
 *
 * 职责：
 * 1. **唯一持有当前活跃的 [ProotConnection] 实例**：维护与 `<filesDir>/rootfs` 绑定的常驻 `proot` 进程连接。
 * 2. **自动重连与状态可观测**：暴露 [status] (`StateFlow<ProotConnectionStatus>`)；若底层 `proot` 进程退出或尚未建立，调用 [acquireConnection] 或 [execute] 时自动创建并连接。
 * 3. **多终端输出监听器转发**：支持注册全局原始字节输出监听器（[addRawOutputListener]），即使 [ProotConnection] 重连也会自动接力转发输出字节流到 `vtfx` 终端。
 * 4. **与 `resetRootfs` 生命周期联动**：在重置或替换 `rootfs` 前自动调用 [disconnect] 销毁旧的 `proot` 进程连接，释放文件句柄，防止旧进程继续占用已删除的 `rootfs` 目录。
 */
object ProotConnectionManager {

    private val lock = Any()
    private val generationCounter = AtomicLong(0L)
    private val globalRawOutputListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    @Volatile
    private var activeConnection: ProotConnection? = null

    @Volatile
    private var connectionListenerHandle: Closeable? = null

    @Volatile
    internal var customProcessStarterForTest: ((List<String>, Map<String, String>) -> Process)? = null

    private val _status = MutableStateFlow(ProotConnectionStatus())
    val status: StateFlow<ProotConnectionStatus> = _status.asStateFlow()

    /**
     * 当前持有的活跃 [ProotConnection]（若未连接或已断开则为 `null`）。
     */
    val currentConnection: ProotConnection?
        get() = activeConnection?.takeIf { it.isAlive }

    /**
     * 当前是否已持有存活的 `proot` 持久进程连接。
     */
    val isConnected: Boolean
        get() = activeConnection?.isAlive == true

    /**
     * 注册全局 PRoot 原始输出字节监听器（自动跨重连生效）。
     */
    fun addRawOutputListener(listener: (ByteArray) -> Unit): Closeable {
        globalRawOutputListeners.add(listener)
        return Closeable {
            globalRawOutputListeners.remove(listener)
        }
    }

    private fun dispatchToGlobalListeners(bytes: ByteArray) {
        for (listener in globalRawOutputListeners) {
            runCatching { listener(bytes) }
        }
    }

    /**
     * 获取或建立全局单一 `rootfs` (`<filesDir>/rootfs`) 的持久 [ProotConnection]。
     */
    fun acquireConnection(
        context: Context,
        bindMounts: List<String> = emptyList(),
        fakeRootId: Boolean = true,
        link2Symlink: Boolean = true,
        sysvipc: Boolean = true,
        ashmemMemfd: Boolean = true,
        disableSeccomp: Boolean = false,
        workingDir: String = "/root",
        shellExecutable: String = "/bin/sh",
    ): ProotConnection = synchronized(lock) {
        val existing = activeConnection
        if (existing != null && existing.isAlive) {
            _status.value = existing.snapshotStatus()
            return existing
        }
        runCatching { connectionListenerHandle?.close() }
        connectionListenerHandle = null
        existing?.close()

        val rootfsDir = TermuxProotRuntime.ensureRootfsInitialized(context)
        val interactiveArgs = TermuxProotRuntime.buildProotInteractiveShellArgs(
            context = context,
            bindMounts = bindMounts,
            fakeRootId = fakeRootId,
            link2Symlink = link2Symlink,
            sysvipc = sysvipc,
            ashmemMemfd = ashmemMemfd,
            workingDir = workingDir,
            shellExecutable = shellExecutable,
        )
        val env = TermuxProotRuntime.buildDefaultEnvironment(
            context = context,
            disableSeccomp = disableSeccomp,
        )
        val gen = generationCounter.incrementAndGet()
        val starter = customProcessStarterForTest ?: ProotConnection.Companion::defaultStartProcess

        val connection = ProotConnection(
            generation = gen,
            rootfsDir = rootfsDir,
            shellExecutable = shellExecutable,
            initialWorkingDir = workingDir,
            commandArgs = interactiveArgs,
            environment = env,
            processStarter = starter,
        )
        connectionListenerHandle = connection.addRawOutputListener(::dispatchToGlobalListeners)
        connection.connect()
        activeConnection = connection
        _status.value = connection.snapshotStatus()
        return connection
    }

    /**
     * 向当前持久持有的 [ProotConnection] 发送终端原始输入字节（若尚未连接则自动建立连接）。
     */
    fun sendTerminalInput(
        context: Context,
        bytes: ByteArray,
    ): Boolean = synchronized(lock) {
        val prootFile = TermuxProotRuntime.getProotExecutable(context)
        if (customProcessStarterForTest == null && !prootFile.exists()) {
            return false
        }
        val connection = acquireConnection(context = context)
        val ok = connection.sendRawBytes(bytes)
        _status.value = connection.snapshotStatus()
        return ok
    }

    /**
     * 在持久持有的 [ProotConnection] 中执行命令（自动保持 `cd`、环境变量等上下文；若未连接则自动建立连接）。
     */
    fun execute(
        context: Context,
        command: String,
        bindMounts: List<String> = emptyList(),
        fakeRootId: Boolean = true,
        link2Symlink: Boolean = true,
        sysvipc: Boolean = true,
        ashmemMemfd: Boolean = true,
        disableSeccomp: Boolean = false,
        workingDir: String = "/root",
        shellExecutable: String = "/bin/sh",
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
    ): ShellCommandResult = synchronized(lock) {
        val prootFile = TermuxProotRuntime.getProotExecutable(context)
        if (customProcessStarterForTest == null && !prootFile.exists()) {
            return ShellCommandResult.failure("PRoot executable not found at ${prootFile.absolutePath}")
        }

        val connection = acquireConnection(
            context = context,
            bindMounts = bindMounts,
            fakeRootId = fakeRootId,
            link2Symlink = link2Symlink,
            sysvipc = sysvipc,
            ashmemMemfd = ashmemMemfd,
            disableSeccomp = disableSeccomp,
            workingDir = workingDir,
            shellExecutable = shellExecutable,
        )
        val result = connection.execute(
            command = command,
            timeoutMillis = timeoutMillis,
        )
        _status.value = connection.snapshotStatus()
        return result
    }

    /**
     * 主动断开并重新建立持久 `proot` 连接。
     */
    fun reconnect(
        context: Context,
        bindMounts: List<String> = emptyList(),
        fakeRootId: Boolean = true,
        link2Symlink: Boolean = true,
        sysvipc: Boolean = true,
        ashmemMemfd: Boolean = true,
        disableSeccomp: Boolean = false,
        workingDir: String = "/root",
        shellExecutable: String = "/bin/sh",
    ): ProotConnection = synchronized(lock) {
        disconnect()
        return acquireConnection(
            context = context,
            bindMounts = bindMounts,
            fakeRootId = fakeRootId,
            link2Symlink = link2Symlink,
            sysvipc = sysvipc,
            ashmemMemfd = ashmemMemfd,
            disableSeccomp = disableSeccomp,
            workingDir = workingDir,
            shellExecutable = shellExecutable,
        )
    }

    /**
     * 断开并关闭当前持有的 [ProotConnection] 进程连接。
     */
    fun disconnect() = synchronized(lock) {
        runCatching { connectionListenerHandle?.close() }
        connectionListenerHandle = null
        val existing = activeConnection
        activeConnection = null
        existing?.close()
        _status.value = ProotConnectionStatus(isConnected = false)
    }
}
