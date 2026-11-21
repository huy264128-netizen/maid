package top.aaa000111.maid.androidtool

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import top.aaa000111.maid.shizuku.ShellCommandResult
import kotlin.math.roundToInt

/**
 * 系统设置读写工具集：
 * 支持通过普通 Android 修改系统设置权限（[AndroidPrivilege.WRITE_SETTINGS]，即 `android.permission.WRITE_SETTINGS`）、
 * [AndroidPrivilege.SHIZUKU] 或 [AndroidPrivilege.ROOT] 读取与调整屏幕亮度、自动亮度模式、息屏超时时间及各音频流音量。
 *
 * 当未授予 `WRITE_SETTINGS` 但持有 `SHIZUKU` 或 `ROOT` 时，直接通过 Shizuku / Root 执行 `settings put system ...` 与音量调整命令，不修改自身权限。
 *
 * 支持权限：`WRITE_SETTINGS`、`SHIZUKU`、`ROOT`（持有其中之一即可加载）。
 */
@Suppress("unused")
class SystemSettingsToolSet(
    context: Context,
    private val writeSettingsChecker: (() -> Boolean)? = null,
    private val privilegedShellRunner: (suspend (String) -> ShellCommandResult)? = null,
) : AndroidToolSet() {

    private val appContext by lazy { context.applicationContext }

    override val name: String = "system_settings"

    override fun requiredPrivileges(): Set<AndroidPrivilege> = setOf(
        AndroidPrivilege.WRITE_SETTINGS,
        AndroidPrivilege.SHIZUKU,
        AndroidPrivilege.ROOT,
    )

    private fun canWriteSettingsDirectly(): Boolean {
        writeSettingsChecker?.let { return it() }
        return runCatching { Settings.System.canWrite(appContext) }.getOrDefault(false)
    }

    private suspend fun execPrivileged(command: String): ShellCommandResult {
        privilegedShellRunner?.let { return it(command) }
        return PrivilegeBackend.execShizukuOrRootShell(command)
    }

    @Tool
    @LLMDescription(
        "Read current screen brightness (0..255), auto-brightness mode, screen-off timeout, and volume levels.",
    )
    suspend fun getDisplayAndSoundSettings(): String {
        val resolver = runCatching { appContext.contentResolver }.getOrNull()
        var brightness = runCatching {
            if (resolver != null) Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) else -1
        }.getOrDefault(-1)
        var brightnessMode = runCatching {
            if (resolver != null) Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) else -1
        }.getOrDefault(-1)
        var timeoutMs = runCatching {
            if (resolver != null) Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT) else -1
        }.getOrDefault(-1)

        if (brightness < 0 || brightnessMode < 0 || timeoutMs < 0) {
            val res = execPrivileged(
                "echo \"b=\$(settings get system screen_brightness);m=\$(settings get system screen_brightness_mode);t=\$(settings get system screen_off_timeout)\"",
            )
            if (res.isSuccess) {
                res.stdout.trim().split(";").forEach { part ->
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) {
                        val v = kv[1].trim().toIntOrNull()
                        if (v != null) {
                            when (kv[0].trim()) {
                                "b" -> if (brightness < 0) brightness = v
                                "m" -> if (brightnessMode < 0) brightnessMode = v
                                "t" -> if (timeoutMs < 0) timeoutMs = v
                            }
                        }
                    }
                }
            }
        }

        val autoBrightness = brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

        val audioManager = runCatching {
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
        val volumeSummary = if (audioManager != null) {
            listOf(
                "media" to AudioManager.STREAM_MUSIC,
                "ring" to AudioManager.STREAM_RING,
                "notification" to AudioManager.STREAM_NOTIFICATION,
                "alarm" to AudioManager.STREAM_ALARM,
            ).joinToString(", ") { (label, stream) ->
                val cur = runCatching { audioManager.getStreamVolume(stream) }.getOrDefault(0)
                val max = runCatching { audioManager.getStreamMaxVolume(stream) }.getOrDefault(1).coerceAtLeast(1)
                val pct = ((cur.toDouble() / max.toDouble()) * 100.0).roundToInt()
                "$label=$cur/$max($pct%)"
            }
        } else {
            "unavailable"
        }

        return buildString {
            appendLine("brightness=$brightness/255")
            appendLine("autoBrightness=$autoBrightness")
            appendLine("screenOffTimeoutMs=$timeoutMs (${if (timeoutMs > 0) "${timeoutMs / 1000}s" else "unknown"})")
            append("volumes: $volumeSummary")
        }
    }

    @Tool
    @LLMDescription("Set screen brightness (0..255) and/or enable or disable automatic brightness mode.")
    suspend fun setScreenBrightness(
        @LLMDescription("Target screen brightness value between 0 and 255.")
        brightness: Int,
        @LLMDescription("Whether to enable automatic brightness mode (default false for manual brightness).")
        autoBrightness: Boolean = false,
    ): String {
        val clamped = brightness.coerceIn(0, 255)
        val modeValue = if (autoBrightness) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }

        if (canWriteSettingsDirectly()) {
            val direct = runCatching {
                val resolver = appContext.contentResolver
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, modeValue)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
                "Updated screen brightness to $clamped/255 (autoBrightness=$autoBrightness)"
            }.getOrNull()
            if (direct != null) {
                return direct
            }
        }

        // 未授予 WRITE_SETTINGS 时，直接通过 Shizuku / Root 执行 settings put system
        val cmd = "settings put system screen_brightness_mode $modeValue && settings put system screen_brightness $clamped"
        val res = execPrivileged(cmd)
        return if (res.isSuccess) {
            "Updated screen brightness to $clamped/255 (autoBrightness=$autoBrightness) via Shizuku/Root"
        } else {
            "Failed to set screen brightness: ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Set the screen-off inactivity timeout in seconds (between 15 and 1800 seconds).")
    suspend fun setScreenOffTimeout(
        @LLMDescription("Screen-off timeout in seconds (e.g. 30, 60, 120, 300, 600).")
        timeoutSeconds: Int,
    ): String {
        val clampedSeconds = timeoutSeconds.coerceIn(15, 1800)
        val timeoutMillis = clampedSeconds * 1000

        if (canWriteSettingsDirectly()) {
            val direct = runCatching {
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    timeoutMillis,
                )
                "Updated screen-off timeout to ${clampedSeconds}s (${timeoutMillis}ms)"
            }.getOrNull()
            if (direct != null) {
                return direct
            }
        }

        // 未授予 WRITE_SETTINGS 时，直接通过 Shizuku / Root 执行 settings put system
        val res = execPrivileged("settings put system screen_off_timeout $timeoutMillis")
        return if (res.isSuccess) {
            "Updated screen-off timeout to ${clampedSeconds}s (${timeoutMillis}ms) via Shizuku/Root"
        } else {
            "Failed to set screen-off timeout: ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
        }
    }

    @Tool
    @LLMDescription("Adjust the audio volume percentage (0..100) for a given stream ('media', 'ring', 'notification', or 'alarm').")
    suspend fun setStreamVolume(
        @LLMDescription("Target audio stream: 'media', 'ring', 'notification', or 'alarm'.")
        streamType: String,
        @LLMDescription("Target volume percentage from 0 to 100.")
        volumePercent: Int,
    ): String {
        val stream = when (streamType.trim().lowercase()) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "notification", "notify" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            else -> return "Invalid streamType '$streamType'. Expected 'media', 'ring', 'notification', or 'alarm'."
        }

        val clampedPct = volumePercent.coerceIn(0, 100)
        val audioManager = runCatching {
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()

        if (audioManager != null) {
            val direct = runCatching {
                val maxVolume = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
                val targetIndex = ((clampedPct / 100.0) * maxVolume).roundToInt().coerceIn(0, maxVolume)
                audioManager.setStreamVolume(stream, targetIndex, 0)
                "Set '$streamType' volume to $targetIndex/$maxVolume ($clampedPct%)"
            }.getOrNull()
            if (direct != null) {
                return direct
            }
        }

        // 如果因勿扰策略或限制导致进程内调整失败，退回 Shizuku / Root 执行 cmd media_session volume
        val defaultMax = if (stream == AudioManager.STREAM_MUSIC) 15 else 7
        val targetIndex = ((clampedPct / 100.0) * defaultMax).roundToInt().coerceIn(0, defaultMax)
        val res = execPrivileged("cmd media_session volume --stream $stream --set $targetIndex")
        return if (res.isSuccess) {
            "Set '$streamType' volume to $targetIndex/$defaultMax ($clampedPct%) via Shizuku/Root"
        } else {
            "Failed to set '$streamType' volume: ${res.stderr.ifBlank { res.errorMessage.orEmpty() }}"
        }
    }
}
