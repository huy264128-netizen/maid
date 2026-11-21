package top.aaa000111.maid.shizuku

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 可靠的单命令输入/输出执行器。
 *
 * 特性：
 * 1. 并发读取 stdout 与 stderr，避免管道缓冲区满导致的子进程死锁。
 * 2. 独立线程写入 stdin 并在完成后关闭流（发送 EOF），防止交互命令无限等待输入。
 * 3. 支持超时强制终止子进程树，并保留超时前已采集的部分输出。
 * 4. 限制单流最大采集字节数（防止 Binder 事务超限），同时持续排空剩余管道避免 SIGPIPE/阻塞。
 */
internal object ShellCommandExecutor {

    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024 // 256 KB per stream (safe for Binder IPC)

    private data class StreamCapture(
        val text: String,
        val truncated: Boolean,
    )

    fun executeShell(
        shellCommand: String,
        stdinData: String? = null,
        workingDir: String? = null,
        env: Array<String>? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        executor: ExecutorService? = null,
    ): ShellCommandResult {
        if (shellCommand.isBlank()) {
            return ShellCommandResult.failure("Shell command must not be blank")
        }
        return executeCommand(
            command = arrayOf("/system/bin/sh", "-c", shellCommand),
            stdinData = stdinData,
            workingDir = workingDir,
            env = env,
            timeoutMillis = timeoutMillis,
            maxOutputBytes = maxOutputBytes,
            executor = executor,
        )
    }

    fun executeCommand(
        command: Array<String>,
        stdinData: String? = null,
        workingDir: String? = null,
        env: Array<String>? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        executor: ExecutorService? = null,
    ): ShellCommandResult {
        if (command.isEmpty() || command.all { it.isBlank() }) {
            return ShellCommandResult.failure("Command array must not be empty")
        }

        val startNs = System.nanoTime()
        val effectiveTimeoutMs = if (timeoutMillis <= 0L) DEFAULT_TIMEOUT_MS else timeoutMillis

        val ownExecutor = executor == null
        val workerPool = executor ?: Executors.newFixedThreadPool(3)

        var process: Process? = null
        try {
            val builder = ProcessBuilder(*command)
            if (!workingDir.isNullOrBlank()) {
                val dir = File(workingDir)
                if (!dir.exists() || !dir.isDirectory) {
                    return ShellCommandResult.failure(
                        message = "Working directory does not exist: $workingDir",
                        durationMs = elapsedMillis(startNs),
                    )
                }
                builder.directory(dir)
            }

            if (!env.isNullOrEmpty()) {
                val envMap = builder.environment()
                for (entry in env) {
                    val separatorIndex = entry.indexOf('=')
                    if (separatorIndex > 0) {
                        val key = entry.substring(0, separatorIndex)
                        val value = entry.substring(separatorIndex + 1)
                        envMap[key] = value
                    }
                }
            }

            process = builder.start()

            val stdoutFuture: Future<StreamCapture> = workerPool.submit(
                Callable { readStreamBounded(process.inputStream, maxOutputBytes) },
            )
            val stderrFuture: Future<StreamCapture> = workerPool.submit(
                Callable { readStreamBounded(process.errorStream, maxOutputBytes) },
            )
            val stdinFuture: Future<Unit> = workerPool.submit(
                Callable {
                    writeAndCloseStdin(process, stdinData)
                },
            )

            val finished = process.waitFor(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1_000L, TimeUnit.MILLISECONDS)
            }

            // Ensure stdin writer finishes cleanly
            runCatching { stdinFuture.get(1_000L, TimeUnit.MILLISECONDS) }

            val stdout = runCatching { stdoutFuture.get(2_000L, TimeUnit.MILLISECONDS) }
                .getOrElse { StreamCapture("", false) }
            val stderr = runCatching { stderrFuture.get(2_000L, TimeUnit.MILLISECONDS) }
                .getOrElse { StreamCapture("", false) }

            val exitCode = if (finished) process.exitValue() else -1

            return ShellCommandResult(
                exitCode = exitCode,
                stdout = stdout.text,
                stderr = stderr.text,
                timedOut = !finished,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated,
                durationMs = elapsedMillis(startNs),
                errorMessage = if (!finished) "Command timed out after ${effectiveTimeoutMs}ms" else null,
            )
        } catch (t: Throwable) {
            process?.destroyForcibly()
            return ShellCommandResult.failure(
                message = t.message ?: t::class.java.simpleName,
                durationMs = elapsedMillis(startNs),
            )
        } finally {
            if (ownExecutor) {
                workerPool.shutdownNow()
            }
        }
    }

    private fun writeAndCloseStdin(process: Process, stdinData: String?) {
        try {
            process.outputStream.use { out ->
                if (!stdinData.isNullOrEmpty()) {
                    out.write(stdinData.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            }
        } catch (_: IOException) {
            // Child process may exit early without reading stdin (broken pipe); safe to ignore.
        }
    }

    private fun readStreamBounded(inputStream: InputStream, maxBytes: Int): StreamCapture {
        val captured = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalCaptured = 0
        var truncated = false

        inputStream.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break

                if (totalCaptured < maxBytes) {
                    val remaining = maxBytes - totalCaptured
                    val toWrite = minOf(read, remaining)
                    captured.write(buffer, 0, toWrite)
                    totalCaptured += toWrite
                    if (read > remaining) {
                        truncated = true
                    }
                } else {
                    truncated = true
                }
            }
        }

        val text = String(captured.toByteArray(), StandardCharsets.UTF_8)
        return StreamCapture(text = text, truncated = truncated)
    }

    private fun elapsedMillis(startNs: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
    }
}
