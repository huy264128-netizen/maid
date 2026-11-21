package top.aaa000111.maid.androidtool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.os.IBinder
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import com.rosan.dhizuku.api.Dhizuku
import top.aaa000111.maid.accessibility.MaidAccessibilityService
import top.aaa000111.maid.shizuku.ShellCommandExecutor
import top.aaa000111.maid.shizuku.ShellCommandResult
import top.aaa000111.maid.shizuku.ShizukuManager
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 屏幕截图结果信息。
 */
data class ScreenCaptureResult(
    val filePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val backend: String,
)

/**
 * 各 [AndroidToolSet] 共用的权限路由与跨后端执行辅助工具。
 */
internal object PrivilegeBackend {

    fun isShizukuReady(): Boolean {
        return ShizukuManager.refreshStatus().isReady
    }

    fun isRootShizukuReady(): Boolean {
        val status = ShizukuManager.refreshStatus()
        return status.isReady && (status.serverInfo?.isRoot == true)
    }

    fun getAccessibilityService(): MaidAccessibilityService? {
        return MaidAccessibilityService.instance
    }

    suspend fun isRootAvailable(timeoutMillis: Long = 1_500L): Boolean = withContext(Dispatchers.IO) {
        val result = ShellCommandExecutor.executeCommand(
            command = arrayOf("su", "-c", "id"),
            timeoutMillis = timeoutMillis,
        )
        result.isSuccess && result.stdout.contains("uid=0")
    }

    suspend fun execRootShell(
        shellCommand: String,
        workingDir: String? = null,
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
    ): ShellCommandResult = withContext(Dispatchers.IO) {
        if (shellCommand.isBlank()) {
            return@withContext ShellCommandResult.failure("Shell command must not be blank")
        }
        ShellCommandExecutor.executeCommand(
            command = arrayOf("su", "-c", shellCommand),
            workingDir = workingDir,
            timeoutMillis = timeoutMillis,
        )
    }

    suspend fun execShizukuOrRootShell(
        shellCommand: String,
        workingDir: String? = null,
        timeoutMillis: Long = ShellCommandExecutor.DEFAULT_TIMEOUT_MS,
        preferRoot: Boolean = false,
    ): ShellCommandResult {
        if (preferRoot && isRootAvailable()) {
            return execRootShell(shellCommand, workingDir, timeoutMillis)
        }
        if (isShizukuReady()) {
            return ShizukuManager.execShell(
                shellCommand = shellCommand,
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
            )
        }
        if (isRootAvailable()) {
            return execRootShell(shellCommand, workingDir, timeoutMillis)
        }
        return ShellCommandResult.failure("Neither Shizuku nor Root permission is available")
    }

    fun formatShellResult(result: ShellCommandResult, backend: String): String {
        return buildString {
            appendLine("backend=$backend")
            appendLine("success=${result.isSuccess} exitCode=${result.exitCode} timedOut=${result.timedOut} durationMs=${result.durationMs}")
            result.errorMessage?.let { appendLine("error=$it") }
            if (result.stdout.isNotEmpty()) {
                appendLine("stdout:")
                appendLine(result.stdout.trimEnd())
                if (result.stdoutTruncated) appendLine("[stdout truncated]")
            }
            if (result.stderr.isNotEmpty()) {
                appendLine("stderr:")
                appendLine(result.stderr.trimEnd())
                if (result.stderrTruncated) appendLine("[stderr truncated]")
            }
        }.trimEnd()
    }

    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    suspend fun dispatchAccessibilityGesture(
        service: AccessibilityService,
        path: Path,
        durationMs: Long,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val safeDuration = durationMs.coerceIn(1L, 60_000L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(value = true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            null,
        )
        if (!dispatched && continuation.isActive) {
            continuation.resume(false)
        }
    }

    data class UiDumpNode(
        val text: String,
        val contentDesc: String,
        val resourceId: String,
        val className: String,
        val packageName: String,
        val bounds: Rect,
        val clickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
        val checked: Boolean,
        val enabled: Boolean,
    )

    fun parseUiAutomatorXml(xml: String): List<UiDumpNode> {
        val trimmed = xml.trim()
        val xmlStart = trimmed.indexOf('<')
        if (xmlStart < 0) return emptyList()
        val cleanXml = trimmed.substring(xmlStart)

        val nodes = mutableListOf<UiDumpNode>()
        runCatching {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(cleanXml))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    val boundsStr = parser.getAttributeValue(null, "bounds").orEmpty()
                    val bounds = parseBoundsString(boundsStr) ?: Rect()
                    nodes.add(
                        UiDumpNode(
                            text = parser.getAttributeValue(null, "text").orEmpty(),
                            contentDesc = parser.getAttributeValue(null, "content-desc").orEmpty(),
                            resourceId = parser.getAttributeValue(null, "resource-id").orEmpty(),
                            className = parser.getAttributeValue(null, "class").orEmpty(),
                            packageName = parser.getAttributeValue(null, "package").orEmpty(),
                            bounds = bounds,
                            clickable = parser.getAttributeValue(null, "clickable") == "true",
                            scrollable = parser.getAttributeValue(null, "scrollable") == "true",
                            editable = parser.getAttributeValue(null, "focused") == "true" ||
                                parser.getAttributeValue(null, "class")?.contains("EditText") == true,
                            checked = parser.getAttributeValue(null, "checked") == "true",
                            enabled = parser.getAttributeValue(null, "enabled") != "false",
                        ),
                    )
                }
                eventType = parser.next()
            }
        }
        return nodes
    }

    private val boundsRegex = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun parseBoundsString(raw: String): Rect? {
        val match = boundsRegex.find(raw) ?: return null
        val (l, t, r, b) = match.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    suspend fun captureScreenshot(
        context: Context,
        displayId: Int = Display.DEFAULT_DISPLAY,
        format: String = "jpeg",
        maxDimension: Int = 1280,
        quality: Int = 85,
    ): Result<ScreenCaptureResult> = withContext(Dispatchers.IO) {
        val normalizedFormat = format.trim().lowercase()
        val usePng = normalizedFormat == "png"
        val compressFormat = if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mimeType = if (usePng) "image/png" else "image/jpeg"
        val ext = if (usePng) "png" else "jpg"
        val safeQuality = quality.coerceIn(10, 100)
        val safeMaxDim = maxDimension.coerceAtLeast(0)

        val outDir = File(context.applicationContext.cacheDir, "screenshots").apply { mkdirs() }
        val outFile = File(outDir, "shot_${System.currentTimeMillis()}.$ext")

        // 1. 优先通过 AccessibilityService.takeScreenshot（无需文件跨进程共享）
        val accessibility = getAccessibilityService()
        if (accessibility != null) {
            val bitmap = captureBitmapViaAccessibility(accessibility, displayId)
            if (bitmap != null) {
                return@withContext saveBitmapToCaptureResult(
                    bitmap = bitmap,
                    outFile = outFile,
                    compressFormat = compressFormat,
                    mimeType = mimeType,
                    maxDimension = safeMaxDim,
                    quality = safeQuality,
                    backend = "accessibility",
                )
            }
        }

        // 2. 其次通过 Shizuku UserService（ParcelFileDescriptor 流式写入应用私有缓存）
        if (isShizukuReady()) {
            val tempPng = File(outDir, "tmp_shizuku_${System.currentTimeMillis()}.png")
            val ok = ShizukuManager.captureScreenshot(tempPng, displayId).getOrDefault(false)
            if (ok && tempPng.exists() && tempPng.length() > 8L) {
                return@withContext finalizeRawPngCapture(
                    rawPngFile = tempPng,
                    outFile = outFile,
                    usePng = usePng,
                    compressFormat = compressFormat,
                    mimeType = mimeType,
                    maxDimension = safeMaxDim,
                    quality = safeQuality,
                    backend = if (isRootShizukuReady()) "root_shizuku" else "shizuku",
                )
            }
            tempPng.delete()
        }

        // 3. 最后尝试直接 Root (su -c screencap -p)
        if (isRootAvailable()) {
            val tempPng = File(outDir, "tmp_root_${System.currentTimeMillis()}.png")
            val ok = captureRawPngViaRoot(tempPng, displayId)
            if (ok && tempPng.exists() && tempPng.length() > 8L) {
                return@withContext finalizeRawPngCapture(
                    rawPngFile = tempPng,
                    outFile = outFile,
                    usePng = usePng,
                    compressFormat = compressFormat,
                    mimeType = mimeType,
                    maxDimension = safeMaxDim,
                    quality = safeQuality,
                    backend = "root",
                )
            }
            tempPng.delete()
        }

        Result.failure(
            IllegalStateException(
                "Screenshot unavailable: please grant Accessibility, Shizuku, or Root privilege",
            ),
        )
    }

    private suspend fun captureBitmapViaAccessibility(
        service: AccessibilityService,
        displayId: Int,
    ): Bitmap? = withTimeoutOrNull(10_000L) {
        suspendCancellableCoroutine { continuation ->
            val executor = Dispatchers.IO.asExecutor()
            runCatching {
                service.takeScreenshot(
                    displayId,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val softwareBitmap = runCatching {
                                val hwBuffer = screenshot.hardwareBuffer
                                try {
                                    val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                        ?: return@runCatching null
                                    try {
                                        hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    } finally {
                                        hwBitmap.recycle()
                                    }
                                } finally {
                                    hwBuffer.close()
                                }
                            }.getOrNull()
                            if (continuation.isActive) {
                                continuation.resume(softwareBitmap)
                            } else {
                                softwareBitmap?.recycle()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    },
                )
            }.onFailure {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun captureRawPngViaRoot(
        destFile: File,
        displayId: Int,
    ): Boolean {
        val cmd = if (displayId > 0) "screencap -d $displayId -p" else "screencap -p"
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", cmd).start()
            val started = process
            val stderrThread = Thread {
                runCatching { started.errorStream.use { it.readBytes() } }
            }.apply {
                isDaemon = true
                start()
            }
            FileOutputStream(destFile).use { out ->
                started.inputStream.use { input ->
                    input.copyTo(out, bufferSize = 32 * 1024)
                }
                out.flush()
            }
            val finished = started.waitFor(15_000L, TimeUnit.MILLISECONDS)
            if (!finished) {
                started.destroyForcibly()
                return false
            }
            stderrThread.join(1_000L)
            started.exitValue() == 0
        } catch (_: Throwable) {
            process?.destroyForcibly()
            false
        }
    }

    private fun finalizeRawPngCapture(
        rawPngFile: File,
        outFile: File,
        usePng: Boolean,
        compressFormat: Bitmap.CompressFormat,
        mimeType: String,
        maxDimension: Int,
        quality: Int,
        backend: String,
    ): Result<ScreenCaptureResult> {
        return try {
            if (usePng && maxDimension == 0) {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(rawPngFile.absolutePath, boundsOpts)
                if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                    if (rawPngFile.renameTo(outFile)) {
                        return Result.success(
                            ScreenCaptureResult(
                                filePath = outFile.absolutePath,
                                mimeType = mimeType,
                                width = boundsOpts.outWidth,
                                height = boundsOpts.outHeight,
                                byteSize = outFile.length(),
                                backend = backend,
                            ),
                        )
                    }
                }
            }

            val decoded = BitmapFactory.decodeFile(rawPngFile.absolutePath)
                ?: return Result.failure(IllegalStateException("Failed to decode captured PNG screenshot"))
            saveBitmapToCaptureResult(
                bitmap = decoded,
                outFile = outFile,
                compressFormat = compressFormat,
                mimeType = mimeType,
                maxDimension = maxDimension,
                quality = quality,
                backend = backend,
            )
        } finally {
            rawPngFile.delete()
        }
    }

    private fun saveBitmapToCaptureResult(
        bitmap: Bitmap,
        outFile: File,
        compressFormat: Bitmap.CompressFormat,
        mimeType: String,
        maxDimension: Int,
        quality: Int,
        backend: String,
    ): Result<ScreenCaptureResult> {
        var workingBitmap = bitmap
        return try {
            val origW = workingBitmap.width
            val origH = workingBitmap.height
            val longest = maxOf(origW, origH)
            if (maxDimension in 1..<longest) {
                val scale = maxDimension.toFloat() / longest.toFloat()
                val targetW = (origW * scale).toInt().coerceAtLeast(1)
                val targetH = (origH * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(workingBitmap, targetW, targetH, true)
                if (scaled !== workingBitmap) {
                    workingBitmap.recycle()
                    workingBitmap = scaled
                }
            }

            FileOutputStream(outFile).use { out ->
                val ok = workingBitmap.compress(compressFormat, quality, out)
                if (!ok) {
                    return Result.failure(IllegalStateException("Failed to compress screenshot bitmap"))
                }
                out.flush()
            }

            Result.success(
                ScreenCaptureResult(
                    filePath = outFile.absolutePath,
                    mimeType = mimeType,
                    width = workingBitmap.width,
                    height = workingBitmap.height,
                    byteSize = outFile.length(),
                    backend = backend,
                ),
            )
        } catch (t: Throwable) {
            outFile.delete()
            Result.failure(t)
        } finally {
            if (!workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
    }
}

/**
 * 基于 [Dhizuku] 代理构建具有 Device Owner 权限的 [DevicePolicyManager] 包装器。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object DhizukuDevicePolicyHelper {

    data class DhizukuContext(
        val devicePolicyManager: DevicePolicyManager,
        val ownerComponent: ComponentName,
    )

    fun acquire(context: Context): Result<DhizukuContext> {
        val appContext = context.applicationContext
        val initialized = runCatching { Dhizuku.init(appContext) }.getOrDefault(false)
        if (!initialized) {
            return Result.failure(IllegalStateException("Dhizuku is not installed or not active"))
        }
        val granted = runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
        if (!granted) {
            return Result.failure(SecurityException("Dhizuku permission is not granted"))
        }
        val ownerComponent = runCatching { Dhizuku.getOwnerComponent(appContext) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Dhizuku Device/Profile Owner component not found"))

        val wrappedDpm = createWrappedDevicePolicyManager(appContext, ownerComponent)
            ?: return Result.failure(IllegalStateException("Failed to wrap DevicePolicyManager via Dhizuku"))

        return Result.success(
            DhizukuContext(
                devicePolicyManager = wrappedDpm,
                ownerComponent = ownerComponent,
            ),
        )
    }

    private fun createWrappedDevicePolicyManager(
        context: Context,
        ownerComponent: ComponentName,
    ): DevicePolicyManager? {
        return runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val rawBinder = getService.invoke(null, Context.DEVICE_POLICY_SERVICE) as? IBinder
                ?: return@runCatching null

            val wrappedBinder = Dhizuku.binderWrapper(rawBinder)
            val idpmInterfaceClass = Class.forName("android.app.admin.IDevicePolicyManager")
            val stubClass = Class.forName($$"android.app.admin.IDevicePolicyManager$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val wrappedService = asInterface.invoke(null, wrappedBinder)
                ?: return@runCatching null

            val dhizukuPackageContext = object : ContextWrapper(context) {
                override fun getPackageName(): String = ownerComponent.packageName
                override fun getOpPackageName(): String = ownerComponent.packageName
            }

            val constructor = DevicePolicyManager::class.java.getDeclaredConstructor(
                Context::class.java,
                idpmInterfaceClass,
            )
            constructor.isAccessible = true
            constructor.newInstance(dhizukuPackageContext, wrappedService)
        }.getOrNull()
    }
}
