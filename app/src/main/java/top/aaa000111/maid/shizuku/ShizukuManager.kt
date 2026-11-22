package top.aaa000111.maid.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.InputEvent
import android.view.Surface
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.ShizukuSystemProperties
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Shizuku 能力统一管理器（全局单例）。
 *
 * 覆盖能力：
 * 1. Shizuku Binder 生命周期监听（Received / Dead）与管理端安装状态检测。
 * 2. 权限检测、异步与挂起式权限申请（[checkPermission] / [requestPermission] / [awaitPermission]）。
 * 3. Shizuku 服务端信息查询（UID、API 版本、SELinux 上下文、是否 pre-v11）。
 * 4. 服务端远程权限校验（[checkRemotePermission]）与系统属性读写（[getSystemProperty] / [setSystemProperty]）。
 * 5. 系统 Binder 代理（[ShizukuSystemServiceHelper]）。
 * 6. 基于 AIDL UserService（[ShizukuUserService]）的持久化绑定、可靠单命令 I/O（[execShell] / [execCommand]），
 *    以及反射代理的 ActivityManager、InputManager、WindowManager 特权能力。
 */
object ShizukuManager {

    private const val USER_SERVICE_PROCESS_SUFFIX = "shizuku_service"
    private const val USER_SERVICE_TAG = "MaidShizukuUserService"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var cachedUserServiceArgs: Shizuku.UserServiceArgs? = null

    private val bindMutex = Mutex()
    private val requestCodeCounter = AtomicInteger(1000)

    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var userService: IShizukuUserService? = null

    @Volatile
    private var pendingBindDeferred: CompletableDeferred<Result<IShizukuUserService>>? = null

    private val pendingPermissionCallbacks = mutableMapOf<Int, (Boolean) -> Unit>()

    private fun requireContext(): Context {
        return appContext ?: error("ShizukuManager must be initialized via init(context) first")
    }

    private fun getUserServiceArgs(): Shizuku.UserServiceArgs {
        cachedUserServiceArgs?.let { return it }
        val ctx = requireContext()
        val packageManager = ctx.packageManager
        val appInfo = runCatching {
            packageManager.getApplicationInfo(ctx.packageName, 0)
        }.getOrNull()
        val versionCode = runCatching {
            val pkgInfo = packageManager.getPackageInfo(ctx.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pkgInfo).toInt()
        }.getOrDefault(1)
        val isDebuggable = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        return Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, ShizukuUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix(USER_SERVICE_PROCESS_SUFFIX)
            .debuggable(isDebuggable)
            .version(versionCode * 10 + 2) // IPC revision for trusted display ownership.
            .tag(USER_SERVICE_TAG)
            .also { cachedUserServiceArgs = it }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null || !service.pingBinder()) {
                val failure = Result.failure<IShizukuUserService>(
                    IllegalStateException("Received null or dead UserService binder"),
                )
                pendingBindDeferred?.complete(failure)
                pendingBindDeferred = null
                refreshStatus()
                return
            }

            val proxy = IShizukuUserService.Stub.asInterface(service)
            userService = proxy
            pendingBindDeferred?.complete(Result.success(proxy))
            pendingBindDeferred = null
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            pendingBindDeferred?.complete(
                Result.failure(IllegalStateException("Shizuku UserService disconnected")),
            )
            pendingBindDeferred = null
            refreshStatus()
        }

        override fun onBindingDied(name: ComponentName?) {
            userService = null
            pendingBindDeferred?.complete(
                Result.failure(IllegalStateException("Shizuku UserService binding died")),
            )
            pendingBindDeferred = null
            refreshStatus()
        }

        override fun onNullBinding(name: ComponentName?) {
            userService = null
            pendingBindDeferred?.complete(
                Result.failure(IllegalStateException("Shizuku UserService returned null binding")),
            )
            pendingBindDeferred = null
            refreshStatus()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        pendingBindDeferred?.complete(
            Result.failure(IllegalStateException("Shizuku binder died")),
        )
        pendingBindDeferred = null
        refreshStatus()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            val callback = synchronized(pendingPermissionCallbacks) {
                pendingPermissionCallbacks.remove(requestCode)
            }
            refreshStatus()
            callback?.invoke(granted)
        }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            cachedUserServiceArgs = null
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            initialized = true
        }
        refreshStatus()
    }

    /**
     * 释放监听并可选终止远程 UserService。
     */
    fun release(stopUserService: Boolean = false) {
        if (!initialized) return
        synchronized(this) {
            if (!initialized) return
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            synchronized(pendingPermissionCallbacks) {
                pendingPermissionCallbacks.clear()
            }
            unbindUserService(remove = stopUserService)
            cachedUserServiceArgs = null
            initialized = false
        }
    }

    /**
     * 刷新 Shizuku 连接、权限、服务端信息与 UserService 绑定状态。
     */
    fun refreshStatus(): ShizukuStatus {
        val managerInstalled = isShizukuInstalled()
        val binderAlive = Shizuku.pingBinder()
        val granted = binderAlive && checkPermission()
        val rationale = binderAlive && !granted && shouldShowRequestPermissionRationale()
        val serverInfo = if (binderAlive) getServerInfo() else null

        val activeService = userService?.takeIf { it.asBinder().pingBinder() }
        if (activeService == null) {
            userService = null
        }

        val serviceUid = activeService?.let { runCatching { it.processUid }.getOrNull() }
        val servicePid = activeService?.let { runCatching { it.processPid }.getOrNull() }
        val serviceSeContext = activeService?.let { runCatching { it.seLinuxContext }.getOrNull() }

        val updated = ShizukuStatus(
            isManagerInstalled = managerInstalled,
            isBinderAlive = binderAlive,
            permissionGranted = granted,
            shouldShowRationale = rationale,
            serverInfo = serverInfo,
            isUserServiceBound = activeService != null,
            userServiceUid = serviceUid,
            userServicePid = servicePid,
            userServiceSeLinuxContext = serviceSeContext,
        )
        _status.value = updated
        return updated
    }

    fun isShizukuInstalled(): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            ctx.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0)
            true
        }.getOrDefault(false)
    }

    fun openShizukuApp(): Boolean {
        val ctx = appContext ?: return false
        val intent = ctx.packageManager
            .getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun isBinderAlive(): Boolean = Shizuku.pingBinder()

    fun checkPermission(): Boolean {
        if (!Shizuku.pingBinder()) return false
        return if (Shizuku.isPreV11()) {
            false
        } else {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
    }

    fun shouldShowRequestPermissionRationale(): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
        return runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
    }

    fun requestPermission(requestCode: Int = requestCodeCounter.incrementAndGet()): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
        if (checkPermission()) return true
        return runCatching {
            Shizuku.requestPermission(requestCode)
            true
        }.getOrDefault(false)
    }

    suspend fun awaitPermission(timeoutMillis: Long = 60_000L): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
        if (checkPermission()) return true

        val requestCode = requestCodeCounter.incrementAndGet()
        val result = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                synchronized(pendingPermissionCallbacks) {
                    pendingPermissionCallbacks[requestCode] = { granted ->
                        if (continuation.isActive) {
                            continuation.resume(granted)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    synchronized(pendingPermissionCallbacks) {
                        pendingPermissionCallbacks.remove(requestCode)
                    }
                }

                val launched = runCatching {
                    Shizuku.requestPermission(requestCode)
                    true
                }.getOrDefault(false)

                if (!launched) {
                    synchronized(pendingPermissionCallbacks) {
                        pendingPermissionCallbacks.remove(requestCode)
                    }
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        }
        return result ?: checkPermission()
    }

    fun getServerInfo(): ShizukuServerInfo? {
        if (!Shizuku.pingBinder()) return null
        return runCatching {
            ShizukuServerInfo(
                uid = Shizuku.getUid(),
                apiVersion = Shizuku.getVersion(),
                latestSupportedVersion = Shizuku.getLatestServiceVersion(),
                seLinuxContext = Shizuku.getSELinuxContext(),
                isPreV11 = Shizuku.isPreV11(),
            )
        }.getOrNull()
    }

    fun checkRemotePermission(permission: String): Boolean {
        if (!Shizuku.pingBinder()) return false
        return runCatching {
            Shizuku.checkRemotePermission(permission) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun getSystemProperty(key: String, defaultValue: String? = null): String? {
        if (!Shizuku.pingBinder()) return defaultValue
        return runCatching {
            ShizukuSystemProperties.get(key, defaultValue)
        }.getOrDefault(defaultValue)
    }

    fun setSystemProperty(key: String, value: String): Boolean {
        if (!Shizuku.pingBinder() || !checkPermission()) return false
        return runCatching {
            ShizukuSystemProperties.set(key, value)
            true
        }.getOrDefault(false)
    }

    fun peekUserService(): Int {
        if (!Shizuku.pingBinder() || !checkPermission()) return -1
        return runCatching {
            Shizuku.peekUserService(getUserServiceArgs(), userServiceConnection)
        }.getOrDefault(-1)
    }

    suspend fun bindUserService(timeoutMillis: Long = 10_000L): Result<IShizukuUserService> {
        if (!Shizuku.pingBinder()) {
            return Result.failure(IllegalStateException("Shizuku service is not running"))
        }
        if (!checkPermission()) {
            return Result.failure(SecurityException("Shizuku permission is not granted"))
        }

        userService?.takeIf { it.asBinder().pingBinder() }?.let { alive ->
            return Result.success(alive)
        }

        val deferred = bindMutex.withLock {
            userService?.takeIf { it.asBinder().pingBinder() }?.let { alive ->
                return Result.success(alive)
            }

            pendingBindDeferred?.takeIf { !it.isCompleted }
                ?: CompletableDeferred<Result<IShizukuUserService>>().also { newDeferred ->
                    pendingBindDeferred = newDeferred
                    val bindStarted = runCatching {
                        Shizuku.bindUserService(getUserServiceArgs(), userServiceConnection)
                        true
                    }
                    if (bindStarted.isFailure) {
                        val error = bindStarted.exceptionOrNull()
                            ?: IllegalStateException("Failed to call Shizuku.bindUserService")
                        newDeferred.complete(Result.failure(error))
                        pendingBindDeferred = null
                    }
                }
        }

        val result = withTimeoutOrNull(timeoutMillis) { deferred.await() }
        if (result == null) {
            if (pendingBindDeferred === deferred) {
                pendingBindDeferred = null
            }
            return Result.failure(
                IllegalStateException("Timed out binding Shizuku UserService after ${timeoutMillis}ms"),
            )
        }
        return result
    }

    fun unbindUserService(remove: Boolean = true) {
        if (Shizuku.pingBinder() && appContext != null) {
            runCatching {
                Shizuku.unbindUserService(getUserServiceArgs(), userServiceConnection, remove)
            }
        }
        userService = null
        pendingBindDeferred?.complete(
            Result.failure(IllegalStateException("Shizuku UserService unbound")),
        )
        pendingBindDeferred = null
        refreshStatus()
    }

    private suspend fun <T> withUserService(
        block: (IShizukuUserService) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val service = bindUserService().getOrElse { error ->
            return@withContext Result.failure(error)
        }
        runCatching {
            block(service)
        }.onFailure {
            if (!service.asBinder().pingBinder()) {
                userService = null
                refreshStatus()
            }
        }
    }

    // region Single-command I/O execution via UserService

    suspend fun execShell(
        shellCommand: String,
        stdin: String? = null,
        workingDir: String? = null,
        env: Map<String, String>? = null,
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
    ): ShellCommandResult = withContext(Dispatchers.IO) {
        if (shellCommand.isBlank()) {
            return@withContext ShellCommandResult.failure("Shell command must not be blank")
        }
        val envArray = env?.entries?.map { (k, v) -> "$k=$v" }?.toTypedArray()
        withUserService { service ->
            service.executeShell(shellCommand, stdin, workingDir, envArray, timeoutMillis)
        }.getOrElse { error ->
            ShellCommandResult.failure(error.message ?: "Remote executeShell failed")
        }
    }

    suspend fun execCommand(
        command: List<String>,
        stdin: String? = null,
        workingDir: String? = null,
        env: Map<String, String>? = null,
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
    ): ShellCommandResult = withContext(Dispatchers.IO) {
        if (command.isEmpty() || command.all { it.isBlank() }) {
            return@withContext ShellCommandResult.failure("Command must not be empty")
        }
        val envArray = env?.entries?.map { (k, v) -> "$k=$v" }?.toTypedArray()
        withUserService { service ->
            service.executeCommand(command.toTypedArray(), stdin, workingDir, envArray, timeoutMillis)
        }.getOrElse { error ->
            ShellCommandResult.failure(error.message ?: "Remote executeCommand failed")
        }
    }

    // endregion

    // region Reflected System Managers status & capabilities

    /**
     * 检查 UserService 进程内反射获取各系统服务（ActivityManager / InputManager / WindowManager）的可用状态。
     */
    suspend fun getSystemManagersStatus(): Result<Map<String, Boolean>> = withUserService { service ->
        val bundle = service.systemManagersStatus
        mapOf(
            PrivilegedSystemManagers.KEY_ACTIVITY_MANAGER to bundle.getBoolean(PrivilegedSystemManagers.KEY_ACTIVITY_MANAGER),
            PrivilegedSystemManagers.KEY_ACTIVITY_TASK_MANAGER to bundle.getBoolean(PrivilegedSystemManagers.KEY_ACTIVITY_TASK_MANAGER),
            PrivilegedSystemManagers.KEY_INPUT_MANAGER to bundle.getBoolean(PrivilegedSystemManagers.KEY_INPUT_MANAGER),
            PrivilegedSystemManagers.KEY_WINDOW_MANAGER to bundle.getBoolean(PrivilegedSystemManagers.KEY_WINDOW_MANAGER),
        )
    }

    // endregion

    // region ActivityManager calling methods (via UserService reflection)

    suspend fun forceStopPackage(packageName: String, userId: Int = 0): Result<Boolean> =
        withUserService { service ->
            service.forceStopPackage(packageName, userId)
        }

    suspend fun killBackgroundProcesses(packageName: String, userId: Int = 0): Result<Boolean> =
        withUserService { service ->
            service.killBackgroundProcesses(packageName, userId)
        }

    suspend fun getRunningAppProcesses(): Result<List<PrivilegedProcessInfo>> =
        withUserService { service ->
            service.runningAppProcesses
        }

    suspend fun getTopActivity(): Result<String?> =
        withUserService { service ->
            service.topActivity
        }

    suspend fun startActivity(
        packageName: String,
        className: String,
        action: String? = Intent.ACTION_MAIN,
        flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK,
        userId: Int = 0,
    ): Result<Int> {
        val intent = Intent().apply {
            if (!action.isNullOrBlank()) this.action = action
            component = ComponentName(packageName, className)
            addFlags(flags)
        }
        return startActivity(intent, userId)
    }

    suspend fun startActivity(intent: Intent, userId: Int = 0): Result<Int> =
        withUserService { service ->
            service.startActivityAsUser(intent, userId)
        }

    // endregion

    // region InputManager calling methods (via UserService reflection)

    suspend fun injectInputEvent(
        event: InputEvent,
        mode: Int = PrivilegedSystemManagers.INJECT_MODE_WAIT_FOR_FINISH,
    ): Result<Boolean> = withUserService { service ->
        val bundle = Bundle(1).apply {
            putParcelable(PrivilegedSystemManagers.KEY_INPUT_EVENT, event)
        }
        service.injectInputEvent(bundle, mode)
    }

    internal class TrustedDisplayLease(
        val displayId: Int,
        private val owner: IBinder,
        private val service: IShizukuUserService,
    ) {
        suspend fun release() = withContext(Dispatchers.IO) {
            if (service.asBinder().isBinderAlive) service.releaseTrustedVirtualDisplay(owner)
        }
    }

    internal suspend fun createTrustedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Result<TrustedDisplayLease> = withUserService { service ->
        val owner = Binder()
        val displayId = service.createTrustedVirtualDisplay(owner, name, width, height, densityDpi, surface)
        TrustedDisplayLease(displayId, owner, service)
    }

    internal suspend fun dumpDisplayUi(displayId: Int, maxDepth: Int): Result<String> = withUserService { service ->
        service.dumpDisplayUi(displayId, maxDepth)
    }

    suspend fun injectTap(x: Float, y: Float, displayId: Int = 0): Result<Boolean> =
        withUserService { service ->
            service.injectTap(x, y, displayId)
        }

    suspend fun injectSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L,
        displayId: Int = 0,
    ): Result<Boolean> = withUserService { service ->
        service.injectSwipe(startX, startY, endX, endY, durationMs, displayId)
    }

    suspend fun injectKeyEvent(
        keyCode: Int,
        longPress: Boolean = false,
        metaState: Int = 0,
        displayId: Int = 0,
    ): Result<Boolean> = withUserService { service ->
        service.injectKeyEvent(keyCode, longPress, metaState, displayId)
    }

    suspend fun injectText(text: String, displayId: Int = 0): Result<Boolean> =
        withUserService { service ->
            service.injectText(text, displayId)
        }

    // endregion

    // region WindowManager calling methods (via UserService reflection)

    suspend fun getDisplayInfo(displayId: Int = 0): Result<PrivilegedDisplayInfo?> =
        withUserService { service ->
            service.getDisplayInfo(displayId)
        }

    suspend fun setForcedDisplaySize(
        width: Int,
        height: Int,
        displayId: Int = 0,
    ): Result<Boolean> = withUserService { service ->
        service.setForcedDisplaySize(displayId, width, height)
    }

    suspend fun clearForcedDisplaySize(displayId: Int = 0): Result<Boolean> =
        withUserService { service ->
            service.clearForcedDisplaySize(displayId)
        }

    suspend fun setForcedDisplayDensity(
        densityDpi: Int,
        displayId: Int = 0,
        userId: Int = 0,
    ): Result<Boolean> = withUserService { service ->
        service.setForcedDisplayDensity(displayId, densityDpi, userId)
    }

    suspend fun clearForcedDisplayDensity(
        displayId: Int = 0,
        userId: Int = 0,
    ): Result<Boolean> = withUserService { service ->
        service.clearForcedDisplayDensity(displayId, userId)
    }

    suspend fun freezeRotation(rotation: Int, displayId: Int = 0): Result<Boolean> =
        withUserService { service ->
            service.freezeRotation(displayId, rotation)
        }

    suspend fun thawRotation(displayId: Int = 0): Result<Boolean> =
        withUserService { service ->
            service.thawRotation(displayId)
        }

    suspend fun isKeyguardLocked(): Result<Boolean> =
        withUserService { service ->
            service.isKeyguardLocked
        }

    // endregion

    // region Screen capture

    suspend fun captureScreenshot(
        outputFile: File,
        displayId: Int = 0,
    ): Result<Boolean> = withUserService { service ->
        outputFile.parentFile?.mkdirs()
        val pfd = ParcelFileDescriptor.open(
            outputFile,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
        pfd.use { descriptor ->
            service.captureScreenshot(displayId, descriptor)
        }
    }

    // endregion
}
