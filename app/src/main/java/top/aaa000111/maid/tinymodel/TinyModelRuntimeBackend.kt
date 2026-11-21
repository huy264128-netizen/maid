package top.aaa000111.maid.tinymodel

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

internal data class TinyModelEngineInitParams(
    val modelPath: String,
    val backend: TinyModelActiveBackend,
    val maxNumTokens: Int,
    val cpuThreadCount: Int?,
    val cacheDir: String?,
)

internal data class TinyModelConversationParams(
    val systemInstruction: String,
    val sampler: TinyModelSamplerConfig,
    val maxOutputTokens: Int?,
)

internal interface TinyModelConversationHandle : AutoCloseable {
    fun sendMessageAsync(
        text: String,
        maxOutputTokens: Int? = null,
    ): Flow<String>

    fun cancelProcess()

    override fun close()
}

internal interface TinyModelEngineHandle : AutoCloseable {
    val modelPath: String
    val activeBackend: TinyModelActiveBackend

    fun createConversation(params: TinyModelConversationParams): TinyModelConversationHandle

    override fun close()
}

internal interface TinyModelRuntimeBackend {
    fun createAndInitializeEngine(params: TinyModelEngineInitParams): TinyModelEngineHandle
}

/**
 * 基于 `com.google.ai.edge.litertlm` 的真实端侧推理后端实现。
 *
 * 严格遵循 LiteRT-LM 约束：
 * - 仅在 `Engine.initialize()` 成功返回后才包装并交出句柄，初始化抛错时不调用 `close()`；
 * - [Engine] 与 [Conversation] 均使用幂等关闭保护，确保只 `close()` 一次；
 * - 始终传入非空 `systemInstruction`，且不使用 LFM2.5 不支持的受限解码 `ResponseFormat`。
 */
internal object LiteRtTinyModelBackend : TinyModelRuntimeBackend {

    override fun createAndInitializeEngine(params: TinyModelEngineInitParams): TinyModelEngineHandle {
        val litertBackend: Backend = when (params.backend) {
            TinyModelActiveBackend.GPU -> Backend.GPU()
            TinyModelActiveBackend.CPU -> Backend.CPU(threadCount = params.cpuThreadCount)
        }
        val engineConfig = EngineConfig(
            modelPath = params.modelPath,
            backend = litertBackend,
            maxNumTokens = params.maxNumTokens,
            cacheDir = params.cacheDir,
        )
        val engine = Engine(engineConfig)
        // 若 initialize() 抛出异常，绝不调用 engine.close()，直接向外传播异常。
        engine.initialize()
        return LiteRtEngineHandle(
            engine = engine,
            modelPath = params.modelPath,
            activeBackend = params.backend,
        )
    }

    private class LiteRtEngineHandle(
        private val engine: Engine,
        override val modelPath: String,
        override val activeBackend: TinyModelActiveBackend,
    ) : TinyModelEngineHandle {

        private val closed = AtomicBoolean(false)

        override fun createConversation(params: TinyModelConversationParams): TinyModelConversationHandle {
            check(!closed.get()) { "LiteRT-LM Engine has already been closed" }
            val safeSystemInstruction = params.systemInstruction
                .trim()
                .ifEmpty { TinyModelSpec.DEFAULT_INTERNAL_SYSTEM_INSTRUCTION }
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(safeSystemInstruction),
                samplerConfig = SamplerConfig(
                    topK = params.sampler.topK,
                    topP = params.sampler.topP,
                    temperature = params.sampler.temperature,
                    seed = params.sampler.seed,
                ),
                maxOutputToken = params.maxOutputTokens,
            )
            val conversation = engine.createConversation(conversationConfig)
            return LiteRtConversationHandle(conversation)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                engine.close()
            }
        }
    }

    private class LiteRtConversationHandle(
        private val conversation: Conversation,
    ) : TinyModelConversationHandle {

        private val closed = AtomicBoolean(false)

        override fun sendMessageAsync(
            text: String,
            maxOutputTokens: Int?,
        ): Flow<String> {
            check(!closed.get()) { "LiteRT-LM Conversation has already been closed" }
            return conversation.sendMessageAsync(
                text = text,
                maxOutputToken = maxOutputTokens,
            ).map { message -> message.toString() }
        }

        override fun cancelProcess() {
            if (!closed.get()) {
                runCatching { conversation.cancelProcess() }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                conversation.close()
            }
        }
    }
}
