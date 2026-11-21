package top.aaa000111.maid.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.aaa000111.maid.proot.TermuxProotRuntime
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * PRoot 本机托管 SearXNG 的当前阶段。
 */
enum class ProotSearXngPhase {
    IDLE,
    INSTALLING,
    STARTING,
    RUNNING,
    STOPPING,
    UNINSTALLING,
    ERROR,
}

/**
 * PRoot 本机托管 SearXNG 状态快照（默认完全不常驻，仅按需查询/安装/启停）。
 */
data class ProotSearXngStatus(
    val isProotAvailable: Boolean = false,
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val port: Int = SearchSettingsConfig.DEFAULT_LOCAL_PROOT_PORT,
    val phase: ProotSearXngPhase = ProotSearXngPhase.IDLE,
    val message: String = "",
    val lastLog: String = "",
)

/**
 * 可选的 PRoot 本机 SearXNG 进程与安装管理器：
 * - 默认零驻留、零后台进程，不主动安装也不自动占用内存；
 * - 仅当用户在「设置 -> 联网调研与 SearXNG」中主动安装/启动，或开启「本机 PRoot 托管 + 按需拉起」时运行；
 * - 生成极简单机 `settings.yml`（关闭 `limiter` 免 Redis/Valkey、开启 `html` 与 `json`、绑定 `127.0.0.1:<port>`），
 *   通过纯 Python `waitress-serve` 托管，并持有宿主机 [Process] 句柄以便随时可靠停止与释放内存。
 */
object ProotSearXngManager {

    const val INSTALL_DIR_POSIX = "/opt/searxng"
    private const val VENV_WAITRESS_REL = "opt/searxng/venv/bin/waitress-serve"
    private const val SETTINGS_YML_REL = "opt/searxng/settings.yml"
    private const val INSTALL_TIMEOUT_MS = 10L * 60L * 1000L
    private const val STARTUP_WAIT_MS = 25_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var managedProcess: Process? = null

    @Volatile
    private var managedPort: Int = SearchSettingsConfig.DEFAULT_LOCAL_PROOT_PORT

    @Volatile
    private var resetListenerRegistration: Closeable? = null

    private val lifecycleMutex = Mutex()
    private val _status = MutableStateFlow(ProotSearXngStatus())
    val status: StateFlow<ProotSearXngStatus> = _status.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (resetListenerRegistration == null) {
            synchronized(this) {
                if (resetListenerRegistration == null) {
                    resetListenerRegistration = TermuxProotRuntime.registerBeforeRootfsResetListener {
                        stopImmediate()
                    }
                }
            }
        }
    }

    /**
     * 快速检查安装状态（直接查宿主 rootfs 路径，不启动 PRoot 进程）与本地端口监听状态。
     */
    suspend fun refreshStatus(
        port: Int = SearchConfigRepository.cachedConfig.value.safeLocalProotPort,
    ): ProotSearXngStatus = withContext(Dispatchers.IO) {
        val safePort = port.coerceIn(SearchSettingsConfig.MIN_PORT, SearchSettingsConfig.MAX_PORT)
        val ctx = appContext
        if (ctx == null) {
            val empty = ProotSearXngStatus(
                isProotAvailable = false,
                isInstalled = false,
                isRunning = false,
                port = safePort,
                phase = ProotSearXngPhase.IDLE,
                message = "运行时尚未初始化",
            )
            _status.value = empty
            return@withContext empty
        }

        val prootReady = TermuxProotRuntime.isAvailable(ctx)
        val installed = isInstalledOnHost(ctx)
        val aliveManaged = managedProcess?.isAlive == true && managedPort == safePort
        val listening = isLocalPortOpen(safePort)
        val running = aliveManaged || listening

        val current = _status.value
        val nextPhase = when {
            current.phase == ProotSearXngPhase.INSTALLING ||
                current.phase == ProotSearXngPhase.STARTING ||
                current.phase == ProotSearXngPhase.STOPPING ||
                current.phase == ProotSearXngPhase.UNINSTALLING -> current.phase
            running -> ProotSearXngPhase.RUNNING
            else -> ProotSearXngPhase.IDLE
        }

        val next = current.copy(
            isProotAvailable = prootReady,
            isInstalled = installed,
            isRunning = running,
            port = safePort,
            phase = nextPhase,
            message = when {
                !prootReady -> "PRoot 运行时不可用"
                running -> "运行中（http://127.0.0.1:$safePort）"
                installed -> "已安装，当前未运行（零内存占用）"
                else -> "未安装（约需 150~250MB 空间）"
            },
        )
        _status.value = next
        next
    }

    /** 直接检查 rootfs 内是否已安装 SearXNG（零进程开销）。 */
    fun isInstalledQuick(): Boolean {
        val ctx = appContext ?: return false
        return isInstalledOnHost(ctx)
    }

    /** 检查本地托管端口是否已就绪。 */
    fun isRunningQuick(
        port: Int = SearchConfigRepository.cachedConfig.value.safeLocalProotPort,
    ): Boolean {
        val safePort = port.coerceIn(SearchSettingsConfig.MIN_PORT, SearchSettingsConfig.MAX_PORT)
        return (managedProcess?.isAlive == true && managedPort == safePort) || isLocalPortOpen(safePort)
    }

    /**
     * 在 PRoot Linux 容器中一键安装 SearXNG + Waitress，并写入关闭 limiter、开启 json 的精简 `settings.yml`。
     */
    suspend fun install(
        port: Int = SearchConfigRepository.cachedConfig.value.safeLocalProotPort,
    ): Result<ProotSearXngStatus> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("AppContext 未初始化"))
        val safePort = port.coerceIn(SearchSettingsConfig.MIN_PORT, SearchSettingsConfig.MAX_PORT)

        lifecycleMutex.withLock {
            if (!TermuxProotRuntime.isAvailable(ctx)) {
                val err = "PRoot 运行时不可用，无法在容器内安装 SearXNG"
                _status.value = _status.value.copy(
                    isProotAvailable = false,
                    phase = ProotSearXngPhase.ERROR,
                    message = err,
                )
                return@withLock Result.failure(IllegalStateException(err))
            }

            _status.value = _status.value.copy(
                isProotAvailable = true,
                port = safePort,
                phase = ProotSearXngPhase.INSTALLING,
                message = "正在 PRoot 容器中安装 Python 依赖与 SearXNG（首次约需 2~5 分钟）…",
                lastLog = "",
            )

            val secretKey = generateHexSecret()
            val installScript = buildInstallCommand(port = safePort, secretKey = secretKey)
            val cmdResult = runCatching {
                TermuxProotRuntime.executeInProot(
                    context = ctx,
                    command = installScript,
                    persistentSession = false,
                    timeoutMillis = INSTALL_TIMEOUT_MS,
                )
            }.getOrElse { error ->
                val msg = "安装异常：${error.message ?: error::class.simpleName}"
                _status.value = _status.value.copy(
                    phase = ProotSearXngPhase.ERROR,
                    message = msg,
                    lastLog = msg,
                )
                return@withLock Result.failure(error)
            }

            val combinedLog = buildString {
                if (cmdResult.stdout.isNotBlank()) appendLine(cmdResult.stdout.trim())
                if (cmdResult.stderr.isNotBlank()) appendLine(cmdResult.stderr.trim())
            }.trim().takeLast(4_000)

            val installed = isInstalledOnHost(ctx)
            if (cmdResult.isSuccess && installed) {
                val next = ProotSearXngStatus(
                    isProotAvailable = true,
                    isInstalled = true,
                    isRunning = isLocalPortOpen(safePort),
                    port = safePort,
                    phase = if (isLocalPortOpen(safePort)) ProotSearXngPhase.RUNNING else ProotSearXngPhase.IDLE,
                    message = "SearXNG 已安装完成，可随时启动",
                    lastLog = combinedLog,
                )
                _status.value = next
                Result.success(next)
            } else {
                val reason = "安装失败（exitCode=${cmdResult.exitCode}）"
                val next = _status.value.copy(
                    isInstalled = installed,
                    phase = ProotSearXngPhase.ERROR,
                    message = reason,
                    lastLog = combinedLog.ifBlank { reason },
                )
                _status.value = next
                Result.failure(IllegalStateException("$reason\n${combinedLog.takeLast(500)}"))
            }
        }
    }

    /**
     * 启动 PRoot 本机托管 SearXNG 服务（若已在目标端口运行则直接复用）。
     */
    suspend fun start(
        port: Int = SearchConfigRepository.cachedConfig.value.safeLocalProotPort,
    ): Result<ProotSearXngStatus> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("AppContext 未初始化"))
        val safePort = port.coerceIn(SearchSettingsConfig.MIN_PORT, SearchSettingsConfig.MAX_PORT)

        lifecycleMutex.withLock {
            if (!isInstalledOnHost(ctx)) {
                val msg = "PRoot 内尚未安装 SearXNG，请先在设置中点击安装"
                _status.value = _status.value.copy(
                    isInstalled = false,
                    isRunning = false,
                    port = safePort,
                    phase = ProotSearXngPhase.IDLE,
                    message = msg,
                )
                return@withLock Result.failure(IllegalStateException(msg))
            }

            if ((managedProcess?.isAlive == true && managedPort == safePort) && isLocalPortOpen(safePort)) {
                val running = _status.value.copy(
                    isProotAvailable = true,
                    isInstalled = true,
                    isRunning = true,
                    port = safePort,
                    phase = ProotSearXngPhase.RUNNING,
                    message = "运行中（http://127.0.0.1:$safePort）",
                )
                _status.value = running
                return@withLock Result.success(running)
            }

            // 若端口变更或旧进程已退出，先清理旧进程
            stopManagedProcessLocked()

            _status.value = _status.value.copy(
                isProotAvailable = true,
                isInstalled = true,
                port = safePort,
                phase = ProotSearXngPhase.STARTING,
                message = "正在启动本机 SearXNG（127.0.0.1:$safePort）…",
            )

            // 同步更新 settings.yml 中的端口
            syncPortInHostSettingsYml(ctx, safePort)

            val launchCmd = buildLaunchCommand(port = safePort)
            val prootArgs = TermuxProotRuntime.buildProotCommandArgs(
                context = ctx,
                command = launchCmd,
                workingDir = INSTALL_DIR_POSIX,
            )

            val process = try {
                ProcessBuilder(prootArgs).apply {
                    val env = environment()
                    env.putAll(TermuxProotRuntime.buildDefaultEnvironment(ctx))
                    redirectErrorStream(true)
                }.start()
            } catch (error: Throwable) {
                val msg = "启动 SearXNG 进程失败：${error.message ?: error::class.simpleName}"
                _status.value = _status.value.copy(
                    isRunning = false,
                    phase = ProotSearXngPhase.ERROR,
                    message = msg,
                    lastLog = msg,
                )
                return@withLock Result.failure(error)
            }

            managedProcess = process
            managedPort = safePort

            val logBuffer = StringBuilder()
            val readerThread = Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(logBuffer) {
                                if (logBuffer.length > 6_000) {
                                    logBuffer.delete(0, logBuffer.length - 4_000)
                                }
                                logBuffer.appendLine(line)
                            }
                        }
                    }
                }
            }, "maid-proot-searxng-log").apply {
                isDaemon = true
                start()
            }

            val deadline = System.currentTimeMillis() + STARTUP_WAIT_MS
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) break
                if (isLocalPortOpen(safePort)) {
                    ready = true
                    break
                }
                delay(350L)
            }

            val logSnapshot = synchronized(logBuffer) { logBuffer.toString().trim() }
            if (ready && process.isAlive) {
                val next = ProotSearXngStatus(
                    isProotAvailable = true,
                    isInstalled = true,
                    isRunning = true,
                    port = safePort,
                    phase = ProotSearXngPhase.RUNNING,
                    message = "运行中（http://127.0.0.1:$safePort）",
                    lastLog = logSnapshot,
                )
                _status.value = next
                Result.success(next)
            } else {
                stopManagedProcessLocked()
                readerThread.interrupt()
                val exitInfo = runCatching { process.exitValue().toString() }.getOrDefault("timeout")
                val msg = "本机 SearXNG 启动超时或提前退出（$exitInfo）"
                val next = ProotSearXngStatus(
                    isProotAvailable = true,
                    isInstalled = true,
                    isRunning = false,
                    port = safePort,
                    phase = ProotSearXngPhase.ERROR,
                    message = msg,
                    lastLog = logSnapshot.ifBlank { msg },
                )
                _status.value = next
                Result.failure(IllegalStateException(msg))
            }
        }
    }

    /**
     * 按需拉起钩子：仅在已安装且尚未运行时启动，不会触发任何自动安装。
     */
    suspend fun ensureRunningIfInstalled(
        port: Int = SearchConfigRepository.cachedConfig.value.safeLocalProotPort,
    ): Boolean {
        val safePort = port.coerceIn(SearchSettingsConfig.MIN_PORT, SearchSettingsConfig.MAX_PORT)
        if (!isInstalledQuick()) return false
        if (isRunningQuick(safePort)) return true
        return start(safePort).isSuccess
    }

    /** 停止托管的本机 SearXNG 进程以释放内存。 */
    suspend fun stop(): ProotSearXngStatus = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            _status.value = _status.value.copy(
                phase = ProotSearXngPhase.STOPPING,
                message = "正在停止本机 SearXNG…",
            )
            stopManagedProcessLocked()
            val ctx = appContext
            val installed = ctx != null && isInstalledOnHost(ctx)
            val next = _status.value.copy(
                isInstalled = installed,
                isRunning = false,
                phase = ProotSearXngPhase.IDLE,
                message = if (installed) "已停止（内存已释放）" else "未安装",
            )
            _status.value = next
            next
        }
    }

    /** 停止并彻底删除 `/opt/searxng` 目录以释放存储空间。 */
    suspend fun uninstall(): Result<ProotSearXngStatus> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("AppContext 未初始化"))
        lifecycleMutex.withLock {
            _status.value = _status.value.copy(
                phase = ProotSearXngPhase.UNINSTALLING,
                message = "正在卸载本机 SearXNG 并清理文件…",
            )
            stopManagedProcessLocked()
            val hostDir = File(TermuxProotRuntime.getRootfsDir(ctx), "opt/searxng")
            val deleted = runCatching {
                if (hostDir.exists()) hostDir.deleteRecursively() else true
            }.getOrDefault(false)

            val next = ProotSearXngStatus(
                isProotAvailable = TermuxProotRuntime.isAvailable(ctx),
                isInstalled = isInstalledOnHost(ctx),
                isRunning = false,
                port = _status.value.port,
                phase = if (deleted) ProotSearXngPhase.IDLE else ProotSearXngPhase.ERROR,
                message = if (deleted) "已卸载并释放存储空间" else "清理 /opt/searxng 失败",
                lastLog = "",
            )
            _status.value = next
            if (deleted) Result.success(next) else Result.failure(IllegalStateException(next.message))
        }
    }

    /** 同步立即停止托管进程（供 `AppRuntime.release()` 或 rootfs 重置前回调使用）。 */
    fun stopImmediate() {
        stopManagedProcessLocked()
        _status.value = _status.value.copy(
            isRunning = false,
            phase = ProotSearXngPhase.IDLE,
        )
    }

    private fun stopManagedProcessLocked() {
        val process = managedProcess ?: return
        managedProcess = null
        runCatching {
            process.destroy()
            if (process.isAlive) {
                Thread.sleep(250L)
            }
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun isInstalledOnHost(context: Context): Boolean {
        val rootfs = TermuxProotRuntime.getRootfsDir(context)
        return File(rootfs, VENV_WAITRESS_REL).exists() &&
            File(rootfs, SETTINGS_YML_REL).exists()
    }

    private fun syncPortInHostSettingsYml(context: Context, port: Int) {
        runCatching {
            val settingsFile = File(TermuxProotRuntime.getRootfsDir(context), SETTINGS_YML_REL)
            if (!settingsFile.isFile) return
            val content = settingsFile.readText(Charsets.UTF_8)
            val updated = content.replace(Regex("(?m)^(\\s*port:\\s*)\\d+"), "$1$port")
            if (updated != content) {
                settingsFile.writeText(updated, Charsets.UTF_8)
            }
        }
    }

    private fun isLocalPortOpen(port: Int, timeoutMs: Int = 300): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    private fun generateHexSecret(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun buildSettingsYaml(port: Int, secretKey: String): String = """
        use_default_settings: true
        general:
          instance_name: "Maid Local SearXNG"
          enable_metrics: false
        search:
          safe_search: 0
          autocomplete: ""
          formats:
            - html
            - json
        server:
          port: $port
          bind_address: "127.0.0.1"
          secret_key: "$secretKey"
          limiter: false
          image_proxy: false
          method: "GET"
    """.trimIndent() + "\n"

    internal fun buildInstallCommand(port: Int, secretKey: String): String {
        val yaml = buildSettingsYaml(port = port, secretKey = secretKey)
        return """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            if command -v apt-get >/dev/null 2>&1; then
              apt-get update -y
              apt-get install -y --no-install-recommends python3 python3-venv python3-pip git ca-certificates build-essential python3-dev libxml2-dev libxslt1-dev zlib1g-dev libffi-dev
            fi
            mkdir -p $INSTALL_DIR_POSIX
            if [ ! -d "$INSTALL_DIR_POSIX/venv" ]; then
              python3 -m venv "$INSTALL_DIR_POSIX/venv"
            fi
            "$INSTALL_DIR_POSIX/venv/bin/pip" install --upgrade pip setuptools wheel
            "$INSTALL_DIR_POSIX/venv/bin/pip" install waitress
            "$INSTALL_DIR_POSIX/venv/bin/pip" install --no-build-isolation "git+https://github.com/searxng/searxng.git@master"
            cat << 'MAID_SEARXNG_EOF' > "$INSTALL_DIR_POSIX/settings.yml"
            $yaml
            MAID_SEARXNG_EOF
            echo "MAID_SEARXNG_INSTALL_OK"
        """.trimIndent()
    }

    internal fun buildLaunchCommand(port: Int): String =
        "export SEARXNG_SETTINGS_PATH=\"$INSTALL_DIR_POSIX/settings.yml\" && " +
            "exec \"$INSTALL_DIR_POSIX/venv/bin/waitress-serve\" --host=127.0.0.1 --port=$port --threads=4 searx.webapp:app"
}
