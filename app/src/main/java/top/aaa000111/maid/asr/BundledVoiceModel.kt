package top.aaa000111.maid.asr

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object BundledVoiceModel {
    private const val ASSET_DIRECTORY = "models/sensevoice-small-int8"
    private val modelFiles = listOf("model.int8.onnx", "tokens.txt")
    private val prepareMutex = Mutex()

    suspend fun prepare(context: Context): File = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            val parent = context.noBackupFilesDir
            val imported = parent.resolve("sense_voice")
            if (isComplete(imported)) return@withLock imported

            val directory = parent.resolve("sensevoice-small-int8-2024-07-17")
            if (isComplete(directory)) return@withLock directory

            val staging = Files.createTempDirectory(parent.toPath(), "sensevoice_staging_").toFile()
            try {
                val buffer = ByteArray(64 * 1024)
                for (name in modelFiles) {
                    context.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                        staging.resolve(name).outputStream().use { output ->
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                check(isComplete(staging)) { "Bundled SenseVoice model is incomplete" }
                ensureActive()
                Files.move(staging.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
                directory
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun isComplete(directory: File): Boolean = modelFiles.all { name ->
        val file = directory.resolve(name)
        file.isFile && file.length() > 0
    }
}
