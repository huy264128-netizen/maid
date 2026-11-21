package top.aaa000111.maid.tinymodel

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * 端侧轻量模型（[TinyModel]）的硬件后端偏好。
 */
enum class TinyModelBackendPreference {
    /** 若当前模型规格支持 GPU，则优先尝试 GPU（OpenCL），初始化失败时自动回退 CPU。 */
    AUTO,

    /** 强制仅使用 GPU（OpenCL）后端。 */
    GPU,

    /** 强制仅使用 CPU 后端。 */
    CPU,
}

/**
 * 实际激活运行的 LiteRT-LM 后端。
 */
enum class TinyModelActiveBackend {
    GPU,
    CPU,
}

/**
 * 可选 TinyModel 的文件来源。
 */
enum class TinyModelSourceType {
    /** 打包在 APK `assets/models/`（或 `assets/tiny_models/`）中的内置模型。 */
    BUNDLED_ASSET,

    /** 用户从本机存储导入到应用私有目录的 `.litertlm` 模型。 */
    IMPORTED_LOCAL,
}

/**
 * 采样参数配置。
 *
 * 面向内部事务（分类、摘要、标题生成、结构提取等）默认偏向低温以提升稳定性。
 */
data class TinyModelSamplerConfig(
    val topK: Int = 40,
    val topP: Double = 0.95,
    val temperature: Double = 0.3,
    val seed: Int = 0,
) {
    init {
        require(topK > 0) { "topK must be > 0, got $topK" }
        require(topP in 0.0..1.0) { "topP must be in [0.0, 1.0], got $topP" }
        require(temperature >= 0.0) { "temperature must be >= 0.0, got $temperature" }
    }
}

/**
 * 单个端侧 `.litertlm` 内部模型的元信息规格。
 *
 * 抽象为独立规格对象，便于在设置界面切换或新增 `assets/models/` 中的端侧模型。
 */
data class TinyModelSpec(
    val id: String,
    val displayName: String,
    /** 默认打包在 `app/src/main/assets/` 中的相对路径（优先 `models/<fileName>`）。 */
    val assetPath: String,
    val fileName: String,
    val supportsGpu: Boolean,
    val maxContextTokens: Int = 4096,
    val defaultMaxNumTokens: Int = 2048,
    val defaultSampler: TinyModelSamplerConfig = TinyModelSamplerConfig(),
    /**
     * 在 LiteRT-LM 0.19.1 上，LFM2.5 混合短卷积架构在同一 Engine 上开启第二个独立 Conversation
     * 时可能残留短卷积状态（LiteRT-LM #3165）。开启该标记后，不同内部任务之间会重建干净的 Engine。
     */
    val isolateEnginePerConversation: Boolean = false,
    val defaultSystemInstruction: String = DEFAULT_INTERNAL_SYSTEM_INSTRUCTION,
    val sourceUrl: String? = null,
) {
    val candidateAssetPaths: List<String>
        get() = listOf(
            assetPath,
            "${TinyModelCatalog.MODELS_ASSETS_SUBDIR}/$fileName",
            "${TinyModelCatalog.LEGACY_ASSETS_SUBDIR}/$fileName",
            fileName,
        ).map { it.trim().trimStart('/') }.filter { it.isNotEmpty() }.distinct()

    companion object {
        const val DEFAULT_INTERNAL_SYSTEM_INSTRUCTION: String =
            "You are an internal on-device assistant module. Follow instructions strictly, be concise, and output only the requested result."
    }
}

/**
 * 在 `assets/models/` 或本机导入目录中发现的可选端侧 TinyModel 条目。
 */
data class TinyModelOption(
    val spec: TinyModelSpec,
    val sourceType: TinyModelSourceType,
    val sourceLocation: String,
    val sizeBytes: Long? = null,
    val isExtracted: Boolean = false,
    val customModelPath: String? = null,
) {
    val optionKey: String
        get() = when (sourceType) {
            TinyModelSourceType.BUNDLED_ASSET -> "asset:${spec.assetPath}"
            TinyModelSourceType.IMPORTED_LOCAL -> "local:${customModelPath.orEmpty()}"
        }
}

/**
 * 内置端侧 TinyModel 模型目录及动态发现规则。
 *
 * 默认扫描 `app/src/main/assets/models/` 中的 `.litertlm` 文件（同时兼容旧路径 `assets/tiny_models/`），
 * 运行时按需释放到应用私有目录 `filesDir/tiny_models/` 供 LiteRT-LM 原生引擎加载。
 */
object TinyModelCatalog {

    const val MODELS_ASSETS_SUBDIR = "models"
    const val LEGACY_ASSETS_SUBDIR = "tiny_models"
    const val MODEL_FILE_EXTENSION = ".litertlm"

    /**
     * LFM2.5-350M-abliterated INT4（基于 `s3nh/LFM2.5-350M-abliterated` 转换的 LiteRT-LM 模型）。
     *
     * 默认放置路径：`app/src/main/assets/models/LFM2.5-350M-abliterated_int4.litertlm`。
     */
    val LFM2_5_350M_ABLITERATED_INT4: TinyModelSpec = TinyModelSpec(
        id = "lfm2.5-350m-abliterated-int4",
        displayName = "LFM2.5-350M-abliterated (INT4)",
        assetPath = "$MODELS_ASSETS_SUBDIR/LFM2.5-350M-abliterated_int4.litertlm",
        fileName = "LFM2.5-350M-abliterated_int4.litertlm",
        supportsGpu = false,
        maxContextTokens = 4096,
        defaultMaxNumTokens = 2048,
        isolateEnginePerConversation = true,
        sourceUrl = "https://huggingface.co/s3nh/LFM2.5-350M-abliterated",
    )

    /**
     * LFM2.5-1.2B-Instruct INT4（GPU + CPU 双后端通用版）。
     */
    val LFM2_5_1_2B_INSTRUCT_INT4_GPU: TinyModelSpec = TinyModelSpec(
        id = "lfm2.5-1.2b-instruct-int4-gpu",
        displayName = "LFM2.5-1.2B-Instruct (INT4 GPU/CPU)",
        assetPath = "$MODELS_ASSETS_SUBDIR/LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
        fileName = "LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
        supportsGpu = true,
        maxContextTokens = 4096,
        defaultMaxNumTokens = 2048,
        isolateEnginePerConversation = true,
    )

    /**
     * LFM2.5-1.2B-Instruct INT4（CPU 专用版）。
     */
    val LFM2_5_1_2B_INSTRUCT_INT4_CPU: TinyModelSpec = TinyModelSpec(
        id = "lfm2.5-1.2b-instruct-int4-cpu",
        displayName = "LFM2.5-1.2B-Instruct (INT4 CPU)",
        assetPath = "$MODELS_ASSETS_SUBDIR/LFM2.5-1.2B-Instruct_int4.litertlm",
        fileName = "LFM2.5-1.2B-Instruct_int4.litertlm",
        supportsGpu = false,
        maxContextTokens = 4096,
        defaultMaxNumTokens = 2048,
        isolateEnginePerConversation = true,
    )

    /**
     * LFM2.5-1.2B-Instruct INT8（CPU 高精度版）。
     */
    val LFM2_5_1_2B_INSTRUCT_INT8_CPU: TinyModelSpec = TinyModelSpec(
        id = "lfm2.5-1.2b-instruct-int8-cpu",
        displayName = "LFM2.5-1.2B-Instruct (INT8 CPU)",
        assetPath = "$MODELS_ASSETS_SUBDIR/LFM2.5-1.2B-Instruct_int8.litertlm",
        fileName = "LFM2.5-1.2B-Instruct_int8.litertlm",
        supportsGpu = false,
        maxContextTokens = 4096,
        defaultMaxNumTokens = 2048,
        isolateEnginePerConversation = true,
    )

    val KNOWN_SPECS: List<TinyModelSpec> = listOf(
        LFM2_5_350M_ABLITERATED_INT4,
        LFM2_5_1_2B_INSTRUCT_INT4_GPU,
        LFM2_5_1_2B_INSTRUCT_INT4_CPU,
        LFM2_5_1_2B_INSTRUCT_INT8_CPU,
    )

    /** 当前默认启用的内部 TinyModel 规格。 */
    val DEFAULT: TinyModelSpec = LFM2_5_350M_ABLITERATED_INT4

    /**
     * 根据扫描到的 `.litertlm` 文件名与相对路径匹配已知规格，或动态合成一个合理的 [TinyModelSpec]。
     */
    fun specForDiscoveredFile(
        fileName: String,
        assetPath: String = "$MODELS_ASSETS_SUBDIR/$fileName",
    ): TinyModelSpec {
        val cleanName = fileName.trim()
        val normalizedAssetPath = assetPath.trim().trimStart('/')
        val known = KNOWN_SPECS.firstOrNull { it.fileName.equals(cleanName, ignoreCase = true) }
        if (known != null) {
            return known.copy(
                assetPath = normalizedAssetPath.ifEmpty { known.assetPath },
                fileName = cleanName,
            )
        }
        val baseName = cleanName.removeSuffix(MODEL_FILE_EXTENSION)
            .removeSuffix(MODEL_FILE_EXTENSION.uppercase())
        val lower = baseName.lowercase()
        val id = lower.replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "custom-tiny-model" }
        val supportsGpu = lower.contains("gpu")
        val isLfm2 = lower.contains("lfm2")
        val prettyDisplayName = baseName.replace('_', ' ').trim().ifEmpty { cleanName }
        return TinyModelSpec(
            id = id,
            displayName = prettyDisplayName,
            assetPath = normalizedAssetPath.ifEmpty { "$MODELS_ASSETS_SUBDIR/$cleanName" },
            fileName = cleanName,
            supportsGpu = supportsGpu,
            maxContextTokens = 4096,
            defaultMaxNumTokens = 2048,
            isolateEnginePerConversation = isLfm2,
        )
    }

    /**
     * 供内部 Koog 流程通过 [TinyModelPromptExecutor] 调用时使用的模型描述符。
     */
    fun koogModel(spec: TinyModelSpec = DEFAULT): LLModel = LLModel(
        provider = LLMProvider.Google,
        id = "litert-lm:${spec.id}",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
        ),
        contextLength = spec.maxContextTokens.toLong(),
    )
}

/**
 * [TinyModel] 运行配置。
 */
data class TinyModelConfig(
    val spec: TinyModelSpec = TinyModelCatalog.DEFAULT,
    val backendPreference: TinyModelBackendPreference = TinyModelBackendPreference.AUTO,
    val maxNumTokens: Int = spec.defaultMaxNumTokens,
    val cpuThreadCount: Int? = null,
    val defaultSampler: TinyModelSamplerConfig = spec.defaultSampler,
    val customModelPath: String? = null,
) {
    val effectiveMaxNumTokens: Int
        get() = maxNumTokens.coerceIn(1, spec.maxContextTokens)
}

/**
 * [TinyModel] 内部生命周期状态。
 */
sealed interface TinyModelState {
    data object Uninitialized : TinyModelState

    data class ModelMissing(
        val spec: TinyModelSpec,
        val expectedAssetPaths: List<String>,
    ) : TinyModelState

    /** 首次运行时将 `assets/` 中的 `.litertlm` 释放到应用私有目录以便 LiteRT-LM 加载。 */
    data class Preparing(
        val bytesCopied: Long,
        val totalBytes: Long,
    ) : TinyModelState

    data class Loading(
        val modelPath: String,
        val backend: TinyModelActiveBackend,
    ) : TinyModelState

    data class Ready(
        val modelPath: String,
        val activeBackend: TinyModelActiveBackend,
    ) : TinyModelState

    data class Busy(
        val modelPath: String,
        val activeBackend: TinyModelActiveBackend,
    ) : TinyModelState

    data class Error(
        val message: String,
    ) : TinyModelState

    data object Closed : TinyModelState
}

/**
 * 一次内部推理调用的结果。
 */
data class TinyModelResult(
    val text: String,
    val activeBackend: TinyModelActiveBackend,
    val modelId: String,
    val durationMs: Long,
)
