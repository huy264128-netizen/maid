package top.aaa000111.maid.tinymodel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 面向 `AssetManager` 的抽象接口，便于在 JVM 单测中模拟 `assets/` 模型文件。
 */
internal interface TinyModelAssetSource {
    fun list(dirPath: String): List<String>
    fun exists(assetPath: String): Boolean
    fun open(assetPath: String): InputStream
    fun lengthOrNull(assetPath: String): Long?
}

internal class AndroidTinyModelAssetSource(
    context: Context,
) : TinyModelAssetSource {

    private val assetManager = context.applicationContext.assets

    override fun list(dirPath: String): List<String> {
        val normalized = dirPath.trim().trim('/')
        return runCatching { assetManager.list(normalized)?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    override fun exists(assetPath: String): Boolean {
        val normalized = assetPath.trim().trimStart('/')
        if (normalized.isEmpty()) return false
        val slashIndex = normalized.lastIndexOf('/')
        val parent = if (slashIndex >= 0) normalized.substring(0, slashIndex) else ""
        val name = if (slashIndex >= 0) normalized.substring(slashIndex + 1) else normalized
        val entries = runCatching { assetManager.list(parent) }.getOrNull()
        if (entries != null && name in entries) {
            return true
        }
        return runCatching {
            assetManager.open(normalized).use { true }
        }.getOrDefault(false)
    }

    override fun open(assetPath: String): InputStream {
        return assetManager.open(assetPath.trim().trimStart('/'))
    }

    override fun lengthOrNull(assetPath: String): Long? {
        return runCatching {
            assetManager.openFd(assetPath.trim().trimStart('/')).use { fd ->
                fd.length.takeIf { it > 0L }
            }
        }.getOrNull()
    }
}

/**
 * 端侧 `.litertlm` 模型文件扫描、本地导入与 `assets/` 释放管理器。
 *
 * LiteRT-LM 原生 `EngineConfig(modelPath = ...)` 需要本地文件系统绝对路径：
 * 首次加载时自动将 `assets/models/<fileName>`（亦兼容 `assets/tiny_models/` 与 `assets/` 根目录）
 * 原子释放到 `<filesDir>/tiny_models/<fileName>`，后续直接复用已释放文件；
 * 若后续重新打包替换了 `assets/` 中的模型且大小发生变化，会自动重新释放覆盖。
 */
internal class TinyModelFileStore(
    private val filesDir: File,
    private val cacheRootDir: File,
    private val assetSource: TinyModelAssetSource? = null,
) {
    constructor(context: Context) : this(
        filesDir = context.applicationContext.filesDir,
        cacheRootDir = context.applicationContext.cacheDir,
        assetSource = AndroidTinyModelAssetSource(context.applicationContext),
    )

    val modelsDir: File
        get() = File(filesDir, MODELS_SUBDIR)

    val importedModelsDir: File
        get() = File(modelsDir, IMPORTED_SUBDIR)

    val engineCacheDir: File
        get() = File(cacheRootDir, ENGINE_CACHE_SUBDIR)

    fun targetFileFor(spec: TinyModelSpec): File = File(modelsDir, spec.fileName)

    fun ensureCacheDir(): File {
        val dir = engineCacheDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 扫描 `assets/models/`（优先）、`assets/tiny_models/`、`assets/` 根目录与本机导入目录中的所有 `.litertlm` 模型。
     */
    fun discoverAvailableModels(): List<TinyModelOption> {
        val discovered = mutableListOf<TinyModelOption>()
        val seenBundledNames = LinkedHashSet<String>()

        val source = assetSource
        if (source != null) {
            val scanDirs = listOf(
                TinyModelCatalog.MODELS_ASSETS_SUBDIR,
                TinyModelCatalog.LEGACY_ASSETS_SUBDIR,
                "",
            )
            for (dir in scanDirs) {
                val entries = source.list(dir)
                    .filter { it.endsWith(TinyModelCatalog.MODEL_FILE_EXTENSION, ignoreCase = true) }
                    .sortedBy { it.lowercase() }
                for (name in entries) {
                    val dedupKey = name.lowercase()
                    if (!seenBundledNames.add(dedupKey)) continue
                    val assetPath = if (dir.isEmpty()) name else "$dir/$name"
                    val spec = TinyModelCatalog.specForDiscoveredFile(
                        fileName = name,
                        assetPath = assetPath,
                    )
                    val assetSize = source.lengthOrNull(assetPath)
                    val extractedFile = targetFileFor(spec)
                    val isExtracted = extractedFile.exists() &&
                        extractedFile.isFile &&
                        extractedFile.length() > 0L &&
                        (assetSize == null || extractedFile.length() == assetSize)
                    discovered += TinyModelOption(
                        spec = spec,
                        sourceType = TinyModelSourceType.BUNDLED_ASSET,
                        sourceLocation = "assets/$assetPath",
                        sizeBytes = assetSize ?: extractedFile.takeIf { it.isFile && it.length() > 0L }?.length(),
                        isExtracted = isExtracted,
                        customModelPath = null,
                    )
                }
            }
        }

        val importedFiles = importedModelsDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.length() > 0L &&
                    it.name.endsWith(TinyModelCatalog.MODEL_FILE_EXTENSION, ignoreCase = true)
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        for (file in importedFiles) {
            val baseSpec = TinyModelCatalog.specForDiscoveredFile(
                fileName = file.name,
                assetPath = "${TinyModelCatalog.MODELS_ASSETS_SUBDIR}/${file.name}",
            )
            val localSpec = baseSpec.copy(id = "imported:${baseSpec.id}")
            discovered += TinyModelOption(
                spec = localSpec,
                sourceType = TinyModelSourceType.IMPORTED_LOCAL,
                sourceLocation = file.absolutePath,
                sizeBytes = file.length(),
                isExtracted = true,
                customModelPath = file.absolutePath,
            )
        }

        return discovered
    }

    /**
     * 将外部输入流（如 SAF 文件选择器返回的 `.litertlm`）原子复制到应用私有导入目录。
     */
    suspend fun importModelFile(
        fileName: String,
        inputStream: InputStream,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val sanitized = sanitizeImportedFileName(fileName)
        val dir = importedModelsDir
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create imported tiny model directory: ${dir.absolutePath}")
        }
        val targetFile = File(dir, sanitized)
        val partFile = File(dir, "$sanitized.part")
        if (partFile.exists()) {
            partFile.delete()
        }

        var copied = 0L
        try {
            inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress?.invoke(copied, -1L)
                    }
                    output.flush()
                }
            }
            if (copied <= 0L) {
                throw IOException("Imported TinyModel file is empty: $sanitized")
            }
            moveAtomicallyOrReplace(partFile, targetFile)
            targetFile
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            throw t
        }
    }

    /**
     * 删除一个位于本机导入目录内的 `.litertlm` 文件。
     */
    fun deleteImportedModel(filePath: String): Boolean {
        val target = File(filePath)
        val canonicalDir = runCatching { importedModelsDir.canonicalFile }.getOrNull() ?: return false
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return false
        if (canonicalTarget.parentFile != canonicalDir) {
            return false
        }
        return canonicalTarget.exists() && canonicalTarget.delete()
    }

    /**
     * 仅检查已落盘的本地文件（不触发从 `assets/` 释放）。
     */
    fun resolveExtractedModelFile(
        spec: TinyModelSpec,
        customModelPath: String? = null,
    ): File? {
        val customTrimmed = customModelPath?.trim()?.takeIf { it.isNotEmpty() }
        if (customTrimmed != null) {
            val customFile = File(customTrimmed)
            return customFile.takeIf { it.exists() && it.isFile && it.length() > 0L }
        }

        val candidates = listOf(
            targetFileFor(spec),
            File(filesDir, spec.fileName),
        )
        return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0L }
    }

    /**
     * 返回在 `assets/` 中命中的候选路径，未打包对应模型时返回 `null`。
     */
    fun findBundledAssetPath(spec: TinyModelSpec): String? {
        val source = assetSource ?: return null
        return spec.candidateAssetPaths.firstOrNull { path -> source.exists(path) }
    }

    /**
     * 检查模型是否可用（已释放到本地，或已内置在 `assets/` 中等待首次释放）。
     */
    fun isModelAvailable(
        spec: TinyModelSpec,
        customModelPath: String? = null,
    ): Boolean {
        return resolveExtractedModelFile(spec, customModelPath) != null ||
            findBundledAssetPath(spec) != null
    }

    /**
     * 确保可供 LiteRT-LM 加载的本地 `.litertlm` 文件已就绪。
     */
    suspend fun ensureModelFileReady(
        spec: TinyModelSpec,
        customModelPath: String? = null,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val customTrimmed = customModelPath?.trim()?.takeIf { it.isNotEmpty() }
        if (customTrimmed != null) {
            val customFile = File(customTrimmed)
            if (customFile.exists() && customFile.isFile && customFile.length() > 0L) {
                return@withContext customFile
            }
            throw FileNotFoundException("Custom TinyModel file not found or empty: $customTrimmed")
        }

        val targetFile = targetFileFor(spec)
        val matchedAssetPath = findBundledAssetPath(spec)
        if (matchedAssetPath != null) {
            val source = requireNotNull(assetSource)
            val assetLength = source.lengthOrNull(matchedAssetPath)
            val canReuseExtracted = targetFile.exists() &&
                targetFile.isFile &&
                targetFile.length() > 0L &&
                (assetLength == null || targetFile.length() == assetLength)
            if (canReuseExtracted) {
                return@withContext targetFile
            }
            return@withContext extractAssetAtomically(
                source = source,
                assetPath = matchedAssetPath,
                expectedLength = assetLength ?: -1L,
                targetFile = targetFile,
                onProgress = onProgress,
            )
        }

        val localExisting = resolveExtractedModelFile(spec, customModelPath = null)
        if (localExisting != null) {
            return@withContext localExisting
        }

        throw FileNotFoundException(
            "TinyModel asset not found. Expected one of assets/${spec.candidateAssetPaths.joinToString(" or assets/")} " +
                "(spec=${spec.id})",
        )
    }

    fun deleteExtractedModel(spec: TinyModelSpec): Boolean {
        var deleted = false
        val primary = targetFileFor(spec)
        if (primary.exists()) {
            deleted = primary.delete() || deleted
        }
        val part = File(modelsDir, "${spec.fileName}.part")
        if (part.exists()) {
            part.delete()
        }
        return deleted
    }

    fun clearCache(): Boolean {
        val dir = engineCacheDir
        return !dir.exists() || dir.deleteRecursively()
    }

    private suspend fun extractAssetAtomically(
        source: TinyModelAssetSource,
        assetPath: String,
        expectedLength: Long,
        targetFile: File,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?,
    ): File {
        val dir = targetFile.parentFile ?: modelsDir
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create tiny model directory: ${dir.absolutePath}")
        }

        val partFile = File(dir, "${targetFile.name}.part")
        if (partFile.exists()) {
            partFile.delete()
        }

        var copied = 0L
        try {
            source.open(assetPath).use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress?.invoke(copied, expectedLength)
                    }
                    output.flush()
                }
            }

            if (copied <= 0L) {
                throw IOException("Extracted TinyModel asset is empty: $assetPath")
            }

            moveAtomicallyOrReplace(partFile, targetFile)
            return targetFile
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            throw t
        }
    }

    private fun moveAtomicallyOrReplace(partFile: File, targetFile: File) {
        try {
            Files.move(
                partFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(
                partFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun sanitizeImportedFileName(rawName: String): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('.')
        val withName = cleaned.ifEmpty { "imported_tiny_model" }
        return if (withName.endsWith(TinyModelCatalog.MODEL_FILE_EXTENSION, ignoreCase = true)) {
            withName
        } else {
            "$withName${TinyModelCatalog.MODEL_FILE_EXTENSION}"
        }
    }

    companion object {
        private const val MODELS_SUBDIR = "tiny_models"
        private const val IMPORTED_SUBDIR = "imported"
        private const val ENGINE_CACHE_SUBDIR = "tiny_models_cache"
        private const val COPY_BUFFER_SIZE = 128 * 1024
    }
}
