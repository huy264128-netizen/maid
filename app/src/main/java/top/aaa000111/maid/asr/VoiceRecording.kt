package top.aaa000111.maid.asr

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object VoiceRecording {
    const val SAMPLE_RATE = 16_000
    private const val MAX_SECONDS = 30

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun capture(stop: AtomicBoolean): FloatArray = withContext(Dispatchers.IO) {
        if (stop.get()) return@withContext FloatArray(0)
        val channels = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channels, encoding)
        check(bufferSize > 0) { "Unsupported microphone format: $bufferSize" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channels, encoding, bufferSize,
        )
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            ensureActive()
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone start failed" }

            val samples = FloatArray(SAMPLE_RATE * MAX_SECONDS)
            val buffer = ShortArray(SAMPLE_RATE / 50)
            val deadline = SystemClock.elapsedRealtime() + MAX_SECONDS * 1000L
            var offset = 0
            while (!stop.get() && offset < samples.size && SystemClock.elapsedRealtime() < deadline) {
                ensureActive()
                val count = recorder.read(
                    buffer, 0, minOf(buffer.size, samples.size - offset), AudioRecord.READ_NON_BLOCKING,
                )
                check(count >= 0) { "Microphone read failed: $count" }
                if (count == 0) {
                    delay(10)
                } else {
                    for (index in 0 until count) {
                        samples[offset + index] = buffer[index] / 32768f
                    }
                    offset += count
                }
            }
            samples.copyOf(offset)
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
            }
        }
    }
}
