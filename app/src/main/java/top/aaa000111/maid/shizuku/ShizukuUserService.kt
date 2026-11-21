package top.aaa000111.maid.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.InputEvent
import androidx.annotation.Keep
import androidx.core.os.BundleCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * 运行在 Shizuku 特权进程（UID 0 / Shell UID 2000）中的 UserService 实现。
 *
 * 除单命令 I/O 外，还通过反射在特权进程内持有 `IActivityManager`、`IActivityTaskManager`、
 * `IInputManager` 与 `IWindowManager` 代理能力，供主进程按需通过 Binder IPC 调用。
 */
class ShizukuUserService : IShizukuUserService.Stub {

    private val threadCounter = AtomicInteger(0)
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "shizuku-cmd-io-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private val systemManagers = PrivilegedSystemManagers()

    @Keep
    constructor()

    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context)

    override fun destroy() {
        ioExecutor.shutdownNow()
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun getProcessUid(): Int = Process.myUid()

    override fun getProcessPid(): Int = Process.myPid()

    @SuppressLint("PrivateApi")
    override fun getSELinuxContext(): String {
        val fromProc = runCatching {
            File("/proc/self/attr/current").readText().trim('\u0000', '\n', '\r', ' ')
        }.getOrNull()
        if (!fromProc.isNullOrBlank()) return fromProc

        val fromApi = runCatching {
            val clazz = Class.forName("android.os.SELinux")
            clazz.getMethod("getContext").invoke(null) as? String
        }.getOrNull()
        if (!fromApi.isNullOrBlank()) return fromApi

        val res = ShellCommandExecutor.executeShell("id -Z", executor = ioExecutor)
        return res.stdout.trim()
    }

    override fun executeCommand(
        command: Array<String>?,
        stdinData: String?,
        workingDir: String?,
        env: Array<String>?,
        timeoutMillis: Long,
    ): ShellCommandResult {
        if (command.isNullOrEmpty()) {
            return ShellCommandResult.failure("Command array must not be null or empty")
        }
        return ShellCommandExecutor.executeCommand(
            command = command,
            stdinData = stdinData,
            workingDir = workingDir,
            env = env,
            timeoutMillis = timeoutMillis,
            executor = ioExecutor,
        )
    }

    override fun executeShell(
        shellCommand: String?,
        stdinData: String?,
        workingDir: String?,
        env: Array<String>?,
        timeoutMillis: Long,
    ): ShellCommandResult {
        if (shellCommand.isNullOrBlank()) {
            return ShellCommandResult.failure("Shell command must not be blank")
        }
        return ShellCommandExecutor.executeShell(
            shellCommand = shellCommand,
            stdinData = stdinData,
            workingDir = workingDir,
            env = env,
            timeoutMillis = timeoutMillis,
            executor = ioExecutor,
        )
    }

    @SuppressLint("PrivateApi")
    override fun getSystemProperty(key: String?, defaultValue: String?): String? {
        if (key.isNullOrBlank()) return defaultValue
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, defaultValue) as? String
        }.getOrElse {
            val result = ShellCommandExecutor.executeCommand(
                command = arrayOf("/system/bin/getprop", key),
                executor = ioExecutor,
            )
            val value = result.stdout.trimEnd('\n', '\r')
            if (result.isSuccess && value.isNotEmpty()) value else defaultValue
        }
    }

    @SuppressLint("PrivateApi")
    override fun setSystemProperty(key: String?, value: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val safeValue = value.orEmpty()
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("set", String::class.java, String::class.java)
            method.invoke(null, key, safeValue)
            true
        }.getOrElse {
            ShellCommandExecutor.executeCommand(
                command = arrayOf("/system/bin/setprop", key, safeValue),
                executor = ioExecutor,
            ).isSuccess
        }
    }

    // region Reflected System Managers (ActivityManager / InputManager / WindowManager)

    override fun getSystemManagersStatus(): Bundle {
        return systemManagers.getManagersStatus()
    }

    override fun forceStopPackage(packageName: String?, userId: Int): Boolean {
        if (packageName.isNullOrBlank()) return false
        return systemManagers.forceStopPackage(packageName, userId)
    }

    override fun killBackgroundProcesses(packageName: String?, userId: Int): Boolean {
        if (packageName.isNullOrBlank()) return false
        return systemManagers.killBackgroundProcesses(packageName, userId)
    }

    override fun getRunningAppProcesses(): List<PrivilegedProcessInfo> {
        return systemManagers.getRunningAppProcesses()
    }

    override fun getTopActivity(): String? {
        return systemManagers.getTopActivity()
    }

    override fun startActivityAsUser(intent: Intent?, userId: Int): Int {
        if (intent == null) return -1
        return systemManagers.startActivityAsUser(intent, userId)
    }

    override fun injectInputEvent(eventBundle: Bundle?, mode: Int): Boolean {
        if (eventBundle == null) return false
        eventBundle.classLoader = InputEvent::class.java.classLoader
        val event = BundleCompat.getParcelable(
            eventBundle,
            PrivilegedSystemManagers.KEY_INPUT_EVENT,
            InputEvent::class.java,
        ) ?: return false
        return systemManagers.injectInputEvent(event, mode)
    }

    override fun injectTap(x: Float, y: Float, displayId: Int): Boolean {
        return systemManagers.injectTap(x, y, displayId)
    }

    override fun injectSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        displayId: Int,
    ): Boolean {
        return systemManagers.injectSwipe(startX, startY, endX, endY, durationMs, displayId)
    }

    override fun injectKeyEvent(
        keyCode: Int,
        longPress: Boolean,
        metaState: Int,
        displayId: Int,
    ): Boolean {
        return systemManagers.injectKeyEvent(keyCode, longPress, metaState, displayId)
    }

    override fun injectText(text: String?, displayId: Int): Boolean {
        if (text == null) return false
        return systemManagers.injectText(text, displayId)
    }

    override fun getDisplayInfo(displayId: Int): PrivilegedDisplayInfo? {
        return systemManagers.getDisplayInfo(displayId)
    }

    override fun setForcedDisplaySize(displayId: Int, width: Int, height: Int): Boolean {
        return systemManagers.setForcedDisplaySize(displayId, width, height)
    }

    override fun clearForcedDisplaySize(displayId: Int): Boolean {
        return systemManagers.clearForcedDisplaySize(displayId)
    }

    override fun setForcedDisplayDensity(displayId: Int, densityDpi: Int, userId: Int): Boolean {
        return systemManagers.setForcedDisplayDensity(displayId, densityDpi, userId)
    }

    override fun clearForcedDisplayDensity(displayId: Int, userId: Int): Boolean {
        return systemManagers.clearForcedDisplayDensity(displayId, userId)
    }

    override fun freezeRotation(displayId: Int, rotation: Int): Boolean {
        return systemManagers.freezeRotation(displayId, rotation)
    }

    override fun thawRotation(displayId: Int): Boolean {
        return systemManagers.thawRotation(displayId)
    }

    override fun isKeyguardLocked(): Boolean {
        return systemManagers.isKeyguardLocked()
    }

    override fun captureScreenshot(displayId: Int, outputFd: ParcelFileDescriptor?): Boolean {
        if (outputFd == null) return false
        var process: java.lang.Process? = null
        return try {
            val cmd = if (displayId > 0) {
                arrayOf("/system/bin/screencap", "-d", displayId.toString(), "-p")
            } else {
                arrayOf("/system/bin/screencap", "-p")
            }
            process = ProcessBuilder(*cmd).start()
            val startedProcess = process
            val stderrDrain = ioExecutor.submit {
                runCatching { startedProcess.errorStream.use { it.readBytes() } }
            }
            ParcelFileDescriptor.AutoCloseOutputStream(outputFd).use { out ->
                startedProcess.inputStream.use { input ->
                    input.copyTo(out, bufferSize = 32 * 1024)
                }
                out.flush()
            }
            val finished = startedProcess.waitFor(15_000L, TimeUnit.MILLISECONDS)
            if (!finished) {
                startedProcess.destroyForcibly()
                return false
            }
            runCatching { stderrDrain.get(1_000L, TimeUnit.MILLISECONDS) }
            startedProcess.exitValue() == 0
        } catch (_: Throwable) {
            process?.destroyForcibly()
            false
        } finally {
            runCatching { outputFd.close() }
        }
    }

    // endregion
}
