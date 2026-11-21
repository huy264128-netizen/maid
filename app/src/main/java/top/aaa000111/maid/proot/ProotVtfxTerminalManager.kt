package top.aaa000111.maid.proot

import android.content.Context
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.gate.ClipboardAccess
import com.google.vtfx.gate.UrlOpener
import com.google.vtfx.gate.android.AndroidClipboard
import com.google.vtfx.gate.android.AndroidUrlOpener
import com.google.vtfx.session.TerminalSession
import com.google.vtfx.session.TerminalSessionListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PRoot `vtfx` 终端界面状态快照。
 */
data class ProotVtfxTerminalUiState(
    val title: String = "PRoot Terminal (files/rootfs)",
    val columns: Int = 80,
    val rows: Int = 24,
    val stickyModifiers: KeyModifiers = KeyModifiers.None,
    val directInput: Boolean = false,
    val isConnected: Boolean = false,
    val sessionGeneration: Long = 0L,
    val rootfsPath: String = "",
)

/**
 * 桥接 Google `vtfx` ([TerminalSession]) 与全局单一 `rootfs` ([ProotConnection] / [ProotConnectionManager]) 的终端会话控制器。
 *
 * 严格遵循 `vtfx` 线程与重入约束（见 `docs/usage.md` 与 `docs/decisions/0084-session-and-threads.md`）：
 * 1. `HostConnection.write` 回调绝不阻塞调用线程、绝不抛出异常、绝不在同一调用栈内同步调用 [TerminalSession.feed]；
 * 2. 所有终端键盘/粘贴/协议应答字节均通过独立单线程后台写入器异步写入 [ProotConnection.sendRawBytes]；
 * 3. [ProotConnection] 的 `stdout`/`stderr` 原始输出字节在后台读取线程直接调用 [TerminalSession.feed] 灌入 `vtfx` 终端模拟器。
 */
class ProotVtfxTerminalController(
    context: Context,
    clipboardOverride: ClipboardAccess? = null,
    urlOpenerOverride: UrlOpener? = null,
) : Closeable {

    private val appContext: Context = runCatching { context.applicationContext }.getOrNull() ?: context
    private val closed = AtomicBoolean(false)
    private val bootedBanner = AtomicBoolean(false)

    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "proot-vtfx-stdin-writer").apply {
            isDaemon = true
        }
    }

    private val _uiState = MutableStateFlow(ProotVtfxTerminalUiState())
    val uiState: StateFlow<ProotVtfxTerminalUiState> = _uiState.asStateFlow()

    private val sessionListener = object : TerminalSessionListener {
        override fun onTitleChanged(title: String) {
            _uiState.update { state ->
                state.copy(
                    title = title.ifBlank { "PRoot Terminal (files/rootfs)" },
                )
            }
        }

        override fun onStickyModifiersChanged(modifiers: KeyModifiers) {
            _uiState.update { it.copy(stickyModifiers = modifiers) }
        }

        override fun onResize(columns: Int, rows: Int) {
            _uiState.update { it.copy(columns = columns, rows = rows) }
        }
    }

    val session: TerminalSession = TerminalSession(
        connection = { bytes ->
            val payload = bytes.copyOf()
            if (!closed.get()) {
                runCatching {
                    writerExecutor.execute {
                        if (!closed.get()) {
                            val sent = ProotConnectionManager.sendTerminalInput(appContext, payload)
                            refreshConnectionStatus()
                            if (!sent) {
                                feedSystemNotice("\r\n\u001b[31m[PRoot] 未能发送输入，请检查 libproot.so 或点击重连。\u001b[0m\r\n")
                            }
                        }
                    }
                }
            }
        },
        clipboard = clipboardOverride ?: runCatching { AndroidClipboard(appContext) }.getOrNull(),
        urlOpener = urlOpenerOverride ?: runCatching { AndroidUrlOpener(appContext) }.getOrNull(),
        listener = sessionListener,
    )

    private val outputSubscription: Closeable = ProotConnectionManager.addRawOutputListener { bytes ->
        if (!closed.get() && bytes.isNotEmpty()) {
            runCatching {
                session.feed(bytes)
            }
        }
    }

    /**
     * 确保与全局单一 `rootfs` (`<filesDir>/rootfs`) 的持久 [ProotConnection] 已建立连接，并在首次打开时打印终端欢迎横幅。
     */
    fun ensureConnected() {
        if (closed.get()) return
        writerExecutor.execute {
            if (closed.get()) return@execute
            val rootfsDir = TermuxProotRuntime.ensureRootfsInitialized(appContext)
            val prootFile = TermuxProotRuntime.getProotExecutable(appContext)
            val canConnect = (ProotConnectionManager.customProcessStarterForTest != null) || prootFile.exists()

            if (!canConnect) {
                refreshConnectionStatus(fallbackRootfsPath = rootfsDir.absolutePath)
                if (bootedBanner.compareAndSet(false, true)) {
                    feedSystemNotice(
                        buildString {
                            append("\u001b[1;36m=== Maid vtfx Terminal (PRoot) ===\u001b[0m\r\n")
                            append("Rootfs: ${rootfsDir.absolutePath}\r\n")
                            append("\u001b[33m提示: 未检测到编译产物 ${prootFile.absolutePath}\u001b[0m\r\n")
                        },
                    )
                }
                return@execute
            }

            val conn = runCatching {
                ProotConnectionManager.acquireConnection(appContext)
            }.getOrNull()
            refreshConnectionStatus(fallbackRootfsPath = rootfsDir.absolutePath)

            if (bootedBanner.compareAndSet(false, true)) {
                val status = conn?.snapshotStatus()
                feedSystemNotice(
                    buildString {
                        append("\u001b[1;32m=== Maid vtfx Terminal · Termux PRoot ===\u001b[0m\r\n")
                        append("\u001b[90mRootfs : ${status?.rootfsPath ?: rootfsDir.absolutePath}\u001b[0m\r\n")
                        append("\u001b[90mSession: #${status?.sessionGeneration ?: 1L} (${status?.shellExecutable ?: "/bin/bash"})\u001b[0m\r\n\r\n")
                    },
                )
                // 主动发送一次空回车以刷新提示符，确保终端屏幕在横幅输出后立即出现干净的 root@maid-proot 提示符
                if (conn?.isAlive == true) {
                    conn.sendRawBytes("\n".toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
    }

    /**
     * 断开当前 `proot` 进程并重新建立新的持久 [ProotConnection]。
     */
    fun reconnectProot() {
        if (closed.get()) return
        writerExecutor.execute {
            if (closed.get()) return@execute
            feedSystemNotice("\r\n\u001b[33m[PRoot] 正在重新连接 files/rootfs ...\u001b[0m\r\n")
            val conn = runCatching {
                ProotConnectionManager.reconnect(appContext)
            }.getOrNull()
            refreshConnectionStatus()
            if (conn?.isAlive == true) {
                feedSystemNotice("\u001b[32m[PRoot] 已重新连接 (Session #${conn.generation})\u001b[0m\r\n")
            } else {
                feedSystemNotice("\u001b[31m[PRoot] 重连失败。\u001b[0m\r\n")
            }
        }
    }

    /**
     * 清空终端屏幕与历史回滚缓冲区。
     */
    fun clearTerminal() {
        if (closed.get()) return
        session.clearScrollback()
        // 发送 ANSI 清屏并将光标移至左上角
        feedSystemNotice("\u001b[2J\u001b[H")
    }

    fun sendKey(stroke: KeyStroke) {
        if (closed.get()) return
        session.sendKey(stroke)
    }

    fun toggleCtrl() {
        if (closed.get()) return
        val m = session.stickyModifiers
        session.stickyModifiers = KeyModifiers.of(
            shift = m.shift,
            alt = m.alt,
            ctrl = !m.ctrl,
            meta = m.meta,
        )
    }

    fun toggleAlt() {
        if (closed.get()) return
        val m = session.stickyModifiers
        session.stickyModifiers = KeyModifiers.of(
            shift = m.shift,
            alt = !m.alt,
            ctrl = m.ctrl,
            meta = m.meta,
        )
    }

    fun toggleDirectInput() {
        if (closed.get()) return
        val next = !session.directInput
        session.directInput = next
        _uiState.update { it.copy(directInput = next) }
    }

    internal fun feedSystemNotice(text: String) {
        if (closed.get()) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        runCatching { session.feed(bytes) }
    }

    private fun refreshConnectionStatus(fallbackRootfsPath: String = "") {
        val status = ProotConnectionManager.currentConnection?.snapshotStatus()
            ?: ProotConnectionManager.status.value
        _uiState.update { current ->
            current.copy(
                isConnected = status.isConnected,
                sessionGeneration = status.sessionGeneration,
                rootfsPath = status.rootfsPath.ifBlank { fallbackRootfsPath },
            )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { outputSubscription.close() }
            writerExecutor.shutdownNow()
        }
    }
}

/**
 * 全局共享的 `vtfx` PRoot 终端会话单例管理器：
 * 让用户在聊天界面与终端界面之间来回切换时保留当前 `vtfx` 终端屏幕内容与回滚历史。
 */
object ProotVtfxTerminalManager {

    private val lock = Any()

    @Volatile
    private var sharedController: ProotVtfxTerminalController? = null

    fun getOrCreate(context: Context): ProotVtfxTerminalController = synchronized(lock) {
        val existing = sharedController
        if (existing != null) {
            existing.ensureConnected()
            return existing
        }
        val created = ProotVtfxTerminalController(context.applicationContext ?: context)
        created.ensureConnected()
        sharedController = created
        return created
    }

    fun release() = synchronized(lock) {
        val existing = sharedController
        sharedController = null
        existing?.close()
    }
}
