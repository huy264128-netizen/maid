package top.aaa000111.maid.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object Asr {
    private val recognitionMutex = Mutex()
    private var loadedModel: Pair<File, OfflineRecognizer>? = null

    /**
     * Transcribes one clip of mono PCM, normalized to [-1, 1], at [sampleRate] Hz.
     * Keep [samples] unchanged until this call returns.
     * [modelDirectory] must contain model.int8.onnx and tokens.txt from the same SenseVoice package.
     * Clips shorter than one 25 ms analysis frame return an empty string.
     */
    suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        modelDirectory: File,
    ): String = withContext(Dispatchers.IO) {
        require(sampleRate > 0) { "Sample rate must be positive" }
        if (samples.size.toLong() * 1000 < sampleRate.toLong() * 25) {
            return@withContext ""
        }
        require(samples.all { it in -1f..1f }) { "Expected normalized mono PCM samples" }

        val directory = modelDirectory.canonicalFile
        recognitionMutex.withLock {
            ensureActive()
            val recognizer = recognizerFor(directory)
            ensureActive()
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    private fun recognizerFor(directory: File): OfflineRecognizer {
        loadedModel?.let { (loadedDirectory, recognizer) ->
            if (loadedDirectory == directory) return recognizer
        }

        val model = directory.resolve("model.int8.onnx")
        val tokens = directory.resolve("tokens.txt")
        check(model.isFile && model.canRead() && model.length() > 0) {
            "Missing or unreadable SenseVoice model: ${model.path}"
        }
        check(tokens.isFile && tokens.canRead() && tokens.length() > 0) {
            "Missing or unreadable SenseVoice tokens: ${tokens.path}"
        }

        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.path,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = tokens.path,
                numThreads = 2,
            ),
        )
        val previous = loadedModel
        loadedModel = null
        previous?.second?.release()
        return OfflineRecognizer(config = config).also { loadedModel = directory to it }
    }
}
