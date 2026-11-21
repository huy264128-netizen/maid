package top.aaa000111.maid.tinymodel

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class TinyModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun testSpec(
        supportsGpu: Boolean = true,
        isolateEnginePerConversation: Boolean = true,
    ): TinyModelSpec = TinyModelSpec(
        id = "lfm2.5-1.2b-test",
        displayName = "LFM2.5-1.2B Test",
        assetPath = "tiny_models/LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
        fileName = "LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
        supportsGpu = supportsGpu,
        maxContextTokens = 4096,
        defaultMaxNumTokens = 1024,
        isolateEnginePerConversation = isolateEnginePerConversation,
    )

    private class FakeAssetSource(
        initialAssets: Map<String, ByteArray> = emptyMap(),
    ) : TinyModelAssetSource {
        val assets = initialAssets.toMutableMap()
        val openCount = AtomicInteger(0)

        override fun list(dirPath: String): List<String> {
            val prefix = dirPath.trim().trim('/')
            return assets.keys.mapNotNull { key ->
                val normalized = key.trim().trimStart('/')
                if (prefix.isEmpty()) {
                    if ('/' !in normalized) normalized else null
                } else {
                    val expectedPrefix = "$prefix/"
                    if (normalized.startsWith(expectedPrefix)) {
                        val rest = normalized.removePrefix(expectedPrefix)
                        if ('/' !in rest) rest else null
                    } else {
                        null
                    }
                }
            }.distinct()
        }

        override fun exists(assetPath: String): Boolean = assets.containsKey(assetPath)

        override fun open(assetPath: String): InputStream {
            val bytes = assets[assetPath] ?: throw FileNotFoundException(assetPath)
            openCount.incrementAndGet()
            return ByteArrayInputStream(bytes)
        }

        override fun lengthOrNull(assetPath: String): Long? = assets[assetPath]?.size?.toLong()
    }

    private class FakeBackend(
        private val failGpuInit: Boolean = false,
        private val chunksProvider: (String, TinyModelConversationParams) -> List<String> = { prompt, _ ->
            listOf("ok:", prompt)
        },
        private val delayPerChunkMs: Long = 0L,
    ) : TinyModelRuntimeBackend {

        val initAttempts = CopyOnWriteArrayList<TinyModelEngineInitParams>()
        val initThreadIds = CopyOnWriteArrayList<Long>()
        val initThreadNames = CopyOnWriteArrayList<String>()
        val lifecycleEvents = CopyOnWriteArrayList<String>()
        val conversationParams = CopyOnWriteArrayList<TinyModelConversationParams>()
        val engineCreateCount = AtomicInteger(0)
        val cancelCount = AtomicInteger(0)

        private fun recordThread() {
            val t = Thread.currentThread()
            @Suppress("DEPRECATION")
            initThreadIds += t.id
            initThreadNames += t.name
        }

        override fun createAndInitializeEngine(params: TinyModelEngineInitParams): TinyModelEngineHandle {
            initAttempts += params
            recordThread()
            if (failGpuInit && params.backend == TinyModelActiveBackend.GPU) {
                lifecycleEvents += "engine_init_failed_gpu"
                throw IllegalStateException("Simulated OpenCL unavailable")
            }
            val id = engineCreateCount.incrementAndGet()
            lifecycleEvents += "engine_init_${id}_${params.backend.name}"
            return FakeEngineHandle(id, params)
        }

        private inner class FakeEngineHandle(
            private val engineId: Int,
            params: TinyModelEngineInitParams,
        ) : TinyModelEngineHandle {

            private val closedCount = AtomicInteger(0)
            private val convCounter = AtomicInteger(0)

            override val modelPath: String = params.modelPath
            override val activeBackend: TinyModelActiveBackend = params.backend

            override fun createConversation(params: TinyModelConversationParams): TinyModelConversationHandle {
                recordThread()
                conversationParams += params
                val convId = convCounter.incrementAndGet()
                lifecycleEvents += "conv_open_${engineId}_$convId"
                return FakeConversationHandle(engineId, convId, params)
            }

            override fun close() {
                recordThread()
                val count = closedCount.incrementAndGet()
                lifecycleEvents += "engine_close_${engineId}_#$count"
                if (count > 1) {
                    throw IllegalStateException("Engine closed twice!")
                }
            }
        }

        private inner class FakeConversationHandle(
            private val engineId: Int,
            private val convId: Int,
            private val params: TinyModelConversationParams,
        ) : TinyModelConversationHandle {

            private val closedCount = AtomicInteger(0)

            override fun sendMessageAsync(
                text: String,
                maxOutputTokens: Int?,
            ): Flow<String> = flow {
                val chunks = chunksProvider(text, params)
                for (chunk in chunks) {
                    if (delayPerChunkMs > 0L) delay(delayPerChunkMs)
                    emit(chunk)
                }
            }

            override fun cancelProcess() {
                recordThread()
                cancelCount.incrementAndGet()
                lifecycleEvents += "conv_cancel_${engineId}_$convId"
            }

            override fun close() {
                recordThread()
                val count = closedCount.incrementAndGet()
                lifecycleEvents += "conv_close_${engineId}_$convId-#$count"
                if (count > 1) {
                    throw IllegalStateException("Conversation closed twice!")
                }
            }
        }
    }

    @Test
    fun defaultCatalogUsesLfm25_350M_AbliteratedAssetSpec() {
        val spec = TinyModelCatalog.DEFAULT
        assertEquals(TinyModelCatalog.LFM2_5_350M_ABLITERATED_INT4, spec)
        assertEquals("models/LFM2.5-350M-abliterated_int4.litertlm", spec.assetPath)
        assertEquals("LFM2.5-350M-abliterated_int4.litertlm", spec.fileName)
        assertEquals("https://huggingface.co/s3nh/LFM2.5-350M-abliterated", spec.sourceUrl)
        assertTrue(spec.candidateAssetPaths.contains("models/LFM2.5-350M-abliterated_int4.litertlm"))
        assertTrue(spec.candidateAssetPaths.contains("tiny_models/LFM2.5-350M-abliterated_int4.litertlm"))
        assertTrue(spec.candidateAssetPaths.contains("LFM2.5-350M-abliterated_int4.litertlm"))
        assertFalse(spec.supportsGpu)
        assertTrue(spec.isolateEnginePerConversation)
    }

    @Test
    fun fileStore_discoversAssetsModels_importsLocalModels_andResolvesSelection() = runBlocking {
        val filesDir = tempFolder.newFolder("files_discover")
        val cacheDir = tempFolder.newFolder("cache_discover")
        val assetSource = FakeAssetSource(
            mapOf(
                "models/LFM2.5-350M-abliterated_int4.litertlm" to ByteArray(64) { 0x10 },
                "models/Custom-Qwen-0.6B_int4_gpu.litertlm" to ByteArray(48) { 0x20 },
                "models/README.txt" to "ignore".toByteArray(),
            ),
        )
        val store = TinyModelFileStore(filesDir, cacheDir, assetSource)

        val bundled = store.discoverAvailableModels()
        assertEquals(2, bundled.size)
        val lfm = bundled.first { it.spec.fileName == "LFM2.5-350M-abliterated_int4.litertlm" }
        assertEquals(TinyModelCatalog.LFM2_5_350M_ABLITERATED_INT4.id, lfm.spec.id)
        assertEquals(TinyModelSourceType.BUNDLED_ASSET, lfm.sourceType)
        assertEquals("assets/models/LFM2.5-350M-abliterated_int4.litertlm", lfm.sourceLocation)
        assertEquals(64L, lfm.sizeBytes)

        val customGpu = bundled.first { it.spec.fileName == "Custom-Qwen-0.6B_int4_gpu.litertlm" }
        assertTrue(customGpu.spec.supportsGpu)
        assertFalse(customGpu.spec.isolateEnginePerConversation)

        // 默认空选择优先命中内置 DEFAULT（LFM2.5-350M-abliterated）
        val defaultConfig = TinyModelConfigRepository.resolveEffectiveConfig(
            selection = TinyModelSelection(),
            availableOptions = bundled,
        )
        assertEquals(TinyModelCatalog.LFM2_5_350M_ABLITERATED_INT4.id, defaultConfig.spec.id)

        // 导入本机 .litertlm 模型并验证发现与选择解析
        val importedFile = store.importModelFile(
            fileName = "my_local_model.litertlm",
            inputStream = ByteArrayInputStream(ByteArray(32) { 0x30 }),
        )
        assertTrue(importedFile.exists())
        val allOptions = store.discoverAvailableModels()
        assertEquals(3, allOptions.size)
        val importedOption = allOptions.first { it.sourceType == TinyModelSourceType.IMPORTED_LOCAL }
        assertEquals(importedFile.absolutePath, importedOption.customModelPath)

        val importedSelection = TinyModelSelection(
            selectedModelId = importedOption.spec.id,
            selectedCustomModelPath = importedOption.customModelPath,
            backendPreference = TinyModelBackendPreference.CPU,
        )
        val resolvedImported = TinyModelConfigRepository.resolveEffectiveConfig(
            selection = importedSelection,
            availableOptions = allOptions,
        )
        assertEquals(importedFile.absolutePath, resolvedImported.customModelPath)
        assertEquals(TinyModelBackendPreference.CPU, resolvedImported.backendPreference)

        assertTrue(store.deleteImportedModel(importedFile.absolutePath))
        assertEquals(2, store.discoverAvailableModels().size)
    }

    @Test
    fun preloadAndGenerate_extractsFromAssetsOnce_fallsBackToCpu_andIsolatesLfm25() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val spec = testSpec(supportsGpu = true, isolateEnginePerConversation = true)
        val assetSource = FakeAssetSource(
            mapOf(spec.assetPath to ByteArray(24) { 0x42 }),
        )

        val backend = FakeBackend(failGpuInit = true)
        val tinyModel = TinyModel(
            config = TinyModelConfig(
                spec = spec,
                backendPreference = TinyModelBackendPreference.AUTO,
            ),
            fileStore = TinyModelFileStore(filesDir, cacheDir, assetSource),
            runtimeBackend = backend,
        )

        try {
            assertTrue(tinyModel.isModelAvailable())
            assertNull(tinyModel.resolveExtractedModelFile())

            // 预热：自动从 assets 释放一次，再优先尝试 GPU 并回退 CPU
            val preloadResult = tinyModel.preload()
            assertTrue(preloadResult.isSuccess)
            assertEquals(1, assetSource.openCount.get())
            assertNotNull(tinyModel.resolveExtractedModelFile())
            assertEquals(
                listOf(TinyModelActiveBackend.GPU, TinyModelActiveBackend.CPU),
                backend.initAttempts.map { it.backend },
            )
            assertTrue(tinyModel.state.value is TinyModelState.Ready)
            assertEquals(
                TinyModelActiveBackend.CPU,
                (tinyModel.state.value as TinyModelState.Ready).activeBackend,
            )

            // 首次任务直接复用已预热好的干净 Engine，不重复释放 assets
            val first = tinyModel.generate(prompt = "summarize-1", systemPrompt = "Be concise.")
            assertEquals("ok:summarize-1", first.text)
            assertEquals(TinyModelActiveBackend.CPU, first.activeBackend)
            assertEquals(1, backend.engineCreateCount.get())
            assertEquals(1, assetSource.openCount.get())
            assertEquals("Be concise.", backend.conversationParams.first().systemInstruction)

            // 第二次独立任务：重建 Engine，但仍直接复用已释放的本地文件（不再读 assets）
            val second = tinyModel.generate(prompt = "summarize-2")
            assertEquals("ok:summarize-2", second.text)
            assertEquals(2, backend.engineCreateCount.get())
            assertEquals(1, assetSource.openCount.get())
            assertEquals(
                TinyModelSpec.DEFAULT_INTERNAL_SYSTEM_INSTRUCTION,
                backend.conversationParams[1].systemInstruction,
            )

            // 所有 Engine/Conversation 操作都运行在同一个独占单线程执行器上
            assertTrue(backend.initThreadIds.isNotEmpty())
            assertEquals(1, backend.initThreadIds.toSet().size)
            assertTrue(backend.initThreadNames.all { it.startsWith("maid-tiny-model") })
        } finally {
            tinyModel.closeAndAwait()
        }

        assertEquals(TinyModelState.Closed, tinyModel.state.value)
        assertEquals(
            listOf(
                "engine_init_failed_gpu",
                "engine_init_1_CPU",
                "conv_open_1_1",
                "conv_close_1_1-#1",
                "engine_close_1_#1",
                "engine_init_2_CPU",
                "conv_open_2_1",
                "conv_close_2_1-#1",
                "engine_close_2_#1",
            ),
            backend.lifecycleEvents,
        )
    }

    @Test
    fun fileStore_supportsRootAssetFallback_andReExtractsWhenAssetSizeChanges() = runBlocking {
        val filesDir = tempFolder.newFolder("files_assets")
        val cacheDir = tempFolder.newFolder("cache_assets")
        val spec = testSpec()
        val assetSource = FakeAssetSource(
            // 直接放在 assets/<fileName> 根目录
            mapOf(spec.fileName to ByteArray(16) { 0x11 }),
        )
        val store = TinyModelFileStore(filesDir, cacheDir, assetSource)

        assertTrue(store.isModelAvailable(spec))
        val progress = mutableListOf<Pair<Long, Long>>()
        val firstFile = store.ensureModelFileReady(spec) { copied, total ->
            progress += copied to total
        }
        assertTrue(firstFile.exists())
        assertEquals(16L, firstFile.length())
        assertEquals(1, assetSource.openCount.get())
        assertTrue(progress.isNotEmpty())

        // 再次调用应命中本地缓存，不重复读取 assets
        val secondFile = store.ensureModelFileReady(spec)
        assertEquals(firstFile.absolutePath, secondFile.absolutePath)
        assertEquals(1, assetSource.openCount.get())

        // 模拟重新打包替换了 assets 中的模型（字节数变化） -> 应自动重新释放
        assetSource.assets[spec.fileName] = ByteArray(32) { 0x22 }
        val updatedFile = store.ensureModelFileReady(spec)
        assertEquals(32L, updatedFile.length())
        assertEquals(2, assetSource.openCount.get())

        assertTrue(store.deleteExtractedModel(spec))
        assertNull(store.resolveExtractedModelFile(spec))
    }

    @Test
    fun missingAssetAndLocalFile_setsModelMissingState() = runBlocking {
        val filesDir = tempFolder.newFolder("files_missing")
        val cacheDir = tempFolder.newFolder("cache_missing")
        val spec = testSpec()
        val backend = FakeBackend()
        val tinyModel = TinyModel(
            config = TinyModelConfig(spec = spec),
            fileStore = TinyModelFileStore(filesDir, cacheDir, FakeAssetSource()),
            runtimeBackend = backend,
        )

        try {
            assertFalse(tinyModel.isModelAvailable())
            val preload = tinyModel.preload()
            assertTrue(preload.isFailure)
            assertTrue(tinyModel.state.value is TinyModelState.ModelMissing)
            assertEquals(0, backend.initAttempts.size)
        } finally {
            tinyModel.closeAndAwait()
        }
    }

    @Test
    fun unloadAndReload_freesEngineAndAllowsSubsequentInference() = runBlocking {
        val filesDir = tempFolder.newFolder("files_unload")
        val cacheDir = tempFolder.newFolder("cache_unload")
        val spec = testSpec(isolateEnginePerConversation = false)
        val assetSource = FakeAssetSource(mapOf(spec.assetPath to ByteArray(16) { 0x33 }))

        val backend = FakeBackend()
        val tinyModel = TinyModel(
            config = TinyModelConfig(spec = spec),
            fileStore = TinyModelFileStore(filesDir, cacheDir, assetSource),
            runtimeBackend = backend,
        )

        try {
            assertTrue(tinyModel.preload().isSuccess)
            assertEquals(1, backend.engineCreateCount.get())
            tinyModel.unload()
            assertEquals(TinyModelState.Uninitialized, tinyModel.state.value)

            val res = tinyModel.generate("after-unload")
            assertEquals("ok:after-unload", res.text)
            assertEquals(2, backend.engineCreateCount.get())
        } finally {
            tinyModel.closeAndAwait()
        }
    }

    @Test
    fun koogPromptExecutorAdapter_executesBothSyncAndStreaming() = runBlocking {
        val filesDir = tempFolder.newFolder("files_koog")
        val cacheDir = tempFolder.newFolder("cache_koog")
        val spec = testSpec(isolateEnginePerConversation = false)
        val localDir = File(filesDir, "tiny_models").apply { mkdirs() }
        File(localDir, spec.fileName).writeBytes(ByteArray(16) { 0x55 })

        val backend = FakeBackend(
            chunksProvider = { text, _ -> listOf("part1-", "part2:", text) },
        )
        val tinyModel = TinyModel(
            config = TinyModelConfig(spec = spec),
            fileStore = TinyModelFileStore(filesDir, cacheDir, assetSource = null),
            runtimeBackend = backend,
        )

        try {
            val executor = tinyModel.asPromptExecutor()
            val koogModel = TinyModelCatalog.koogModel(spec)
            val testPrompt = prompt("internal-task") {
                system("Extract keywords.")
                user("Android LiteRT-LM on-device")
            }

            val responses = executor.execute(testPrompt, koogModel)
            assertEquals(1, responses.size)
            val assistant = responses.first() as Message.Assistant
            assertEquals("part1-part2:Android LiteRT-LM on-device", assistant.content)

            val frames = executor.executeStreaming(testPrompt, koogModel).toList()
            val streamedText = frames.filterIsInstance<StreamFrame.Append>().joinToString("") { it.text }
            assertEquals("part1-part2:Android LiteRT-LM on-device", streamedText)
            assertNotNull(frames.filterIsInstance<StreamFrame.End>().lastOrNull())
        } finally {
            tinyModel.closeAndAwait()
        }
    }
}
