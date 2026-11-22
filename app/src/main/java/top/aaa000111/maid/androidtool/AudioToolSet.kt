package top.aaa000111.maid.androidtool

import android.content.Context
import android.media.AudioManager
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import kotlin.math.roundToInt

class AudioToolSet(private val context: Context) : AndroidToolSet() {
    override val name = "audio"

    // MODIFY_AUDIO_SETTINGS is a normal manifest permission.
    override fun requiredPrivileges(): Set<AndroidPrivilege> = emptySet()

    @Tool
    @LLMDescription("Read current and maximum volume for media, ring, notification, and alarm.")
    fun getStreamVolumes(): String {
        val audio = context.getSystemService(AudioManager::class.java) ?: return "Audio service unavailable"
        return streams.entries.joinToString("\n") { (name, stream) ->
            "$name=${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}"
        }
    }

    @Tool
    @LLMDescription("Set and verify the volume of an audio stream.")
    fun setStreamVolume(
        @LLMDescription("Audio stream: media, ring, notification, or alarm.") streamType: String,
        @LLMDescription("Target volume percentage from 0 to 100.") volumePercent: Int,
    ): String {
        val name = streamType.trim().lowercase()
        val stream = streams[when (name) {
            "music" -> "media"
            "ringtone" -> "ring"
            "notify" -> "notification"
            else -> name
        }] ?: return "Invalid audio stream: $streamType"
        val audio = context.getSystemService(AudioManager::class.java) ?: return "Audio service unavailable"
        if (audio.isVolumeFixed) return "This device uses fixed volume"
        val min = audio.getStreamMinVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val target = (min + (max - min) * (volumePercent.coerceIn(0, 100) / 100.0)).roundToInt()
        try {
            audio.setStreamVolume(stream, target, 0)
        } catch (_: SecurityException) {
            return "Android denied the volume change. Check the device's Do Not Disturb policy."
        }
        val actual = audio.getStreamVolume(stream)
        return if (actual == target) "Set '$streamType' volume to $actual/$max"
        else "Volume is $actual/$max after requesting $target/$max"
    }

    private companion object {
        val streams = mapOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
        )
    }
}
