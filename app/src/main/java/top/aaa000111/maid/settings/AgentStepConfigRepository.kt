package top.aaa000111.maid.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.agentStepDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "maid_agent_step_configs",
)

/**
 * 聊天界面中单轮模型工具调用状态输出（[top.aaa000111.maid.chat.ChatItem.ToolStatusItem]）的显示模式；
 * 分段 `output` 消息、最终正式回复、向用户提问、权限请求卡片与系统提示始终保留展示。
 */
enum class ModelOutputDisplayMode(val storageKey: String) {
    /** 展示所有：完整展示单轮内全部工具调用状态卡片 */
    SHOW_ALL("show_all"),

    /** 最后一条：单轮内仅展示最后一条工具调用状态卡片 */
    LAST_ONLY("last_only"),

    /** 不显示：隐藏单轮内全部工具调用状态卡片 */
    HIDE("hide"),
    ;

    companion object {
        fun fromStorageKey(raw: String?): ModelOutputDisplayMode {
            val normalized = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.storageKey.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: SHOW_ALL
        }
    }
}

/**
 * 各智能体的最大执行步数（Koog Strategy 节点步数 / 规划检查点数）、跨聊天记忆开关与模型输出显示配置。
 */
data class AgentStepConfig(
    val chatMaxIterations: Int = DEFAULT_CHAT_MAX_ITERATIONS,
    val plannerMaxIterationsPerCheckpoint: Int = DEFAULT_PLANNER_MAX_ITERATIONS_PER_CHECKPOINT,
    val plannerMaxCheckpoints: Int = DEFAULT_PLANNER_MAX_CHECKPOINTS,
    val deviceActionMaxIterations: Int = DEFAULT_DEVICE_ACTION_MAX_ITERATIONS,
    val enableCrossChatMemory: Boolean = DEFAULT_ENABLE_CROSS_CHAT_MEMORY,
    val modelOutputDisplayMode: ModelOutputDisplayMode = DEFAULT_MODEL_OUTPUT_DISPLAY_MODE,
) {
    fun normalized(): AgentStepConfig = AgentStepConfig(
        chatMaxIterations = chatMaxIterations.coerceAtLeast(MIN_AGENT_ITERATIONS),
        plannerMaxIterationsPerCheckpoint = plannerMaxIterationsPerCheckpoint.coerceAtLeast(
            MIN_AGENT_ITERATIONS,
        ),
        plannerMaxCheckpoints = plannerMaxCheckpoints.coerceAtLeast(MIN_CHECKPOINTS),
        deviceActionMaxIterations = deviceActionMaxIterations.coerceAtLeast(
            MIN_AGENT_ITERATIONS,
        ),
        enableCrossChatMemory = enableCrossChatMemory,
        modelOutputDisplayMode = modelOutputDisplayMode,
    )

    companion object {
        const val MIN_AGENT_ITERATIONS = 1
        const val MIN_CHECKPOINTS = 1

        const val DEFAULT_CHAT_MAX_ITERATIONS = 64
        const val DEFAULT_PLANNER_MAX_ITERATIONS_PER_CHECKPOINT = 64
        const val DEFAULT_PLANNER_MAX_CHECKPOINTS = 16
        const val DEFAULT_DEVICE_ACTION_MAX_ITERATIONS = 96
        const val DEFAULT_ENABLE_CROSS_CHAT_MEMORY = true
        val DEFAULT_MODEL_OUTPUT_DISPLAY_MODE = ModelOutputDisplayMode.SHOW_ALL

        val DEFAULT = AgentStepConfig()

        fun estimateChatToolCalls(iterations: Int): Int =
            ((iterations - 5).coerceAtLeast(0)) / 2

        fun estimatePlannerToolCallsPerCheckpoint(iterations: Int): Int =
            ((iterations - 4).coerceAtLeast(0)) / 2

        fun estimateDeviceActionToolCalls(iterations: Int): Int =
            ((iterations - 3).coerceAtLeast(0)) / 3

        fun estimateResearchToolCalls(iterations: Int): Int =
            ((iterations - 3).coerceAtLeast(0)) / 2
    }
}

/**
 * 持久化与观察各 Agent 步数限制、跨聊天记忆开关与模型输出显示模式的全局单例仓库。
 */
object AgentStepConfigRepository {

    private val KEY_CHAT_MAX_ITERATIONS = intPreferencesKey("chat_max_iterations")
    private val KEY_PLANNER_MAX_ITERATIONS_PER_CHECKPOINT = intPreferencesKey("planner_max_iterations_per_checkpoint")
    private val KEY_PLANNER_MAX_CHECKPOINTS = intPreferencesKey("planner_max_checkpoints")
    private val KEY_DEVICE_ACTION_MAX_ITERATIONS = intPreferencesKey("device_action_max_iterations")
    private val KEY_ENABLE_CROSS_CHAT_MEMORY = booleanPreferencesKey("enable_cross_chat_memory")
    private val KEY_MODEL_OUTPUT_DISPLAY_MODE = stringPreferencesKey("model_output_display_mode")

    @Volatile
    private var appContext: Context? = null

    val isReady: Boolean
        get() = appContext != null

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }
    }

    private fun requireContext(): Context {
        return appContext ?: error("AgentStepConfigRepository must be initialized via init(context) first")
    }

    fun observeStepConfig(): Flow<AgentStepConfig> {
        return requireContext().agentStepDataStore.data
            .map { prefs -> readFromPreferences(prefs) }
            .flowOn(Dispatchers.IO)
    }

    fun observeModelOutputDisplayMode(): Flow<ModelOutputDisplayMode> {
        if (!isReady) return flowOf(AgentStepConfig.DEFAULT_MODEL_OUTPUT_DISPLAY_MODE)
        return observeStepConfig().map { it.modelOutputDisplayMode }
    }

    suspend fun getStepConfig(): AgentStepConfig = withContext(Dispatchers.IO) {
        readFromPreferences(requireContext().agentStepDataStore.data.first())
    }

    suspend fun isCrossChatMemoryEnabled(): Boolean = getStepConfig().enableCrossChatMemory

    suspend fun setCrossChatMemoryEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            requireContext().agentStepDataStore.edit { prefs ->
                prefs[KEY_ENABLE_CROSS_CHAT_MEMORY] = enabled
            }
        }
    }

    suspend fun getModelOutputDisplayMode(): ModelOutputDisplayMode =
        getStepConfig().modelOutputDisplayMode

    suspend fun setModelOutputDisplayMode(mode: ModelOutputDisplayMode) {
        withContext(Dispatchers.IO) {
            requireContext().agentStepDataStore.edit { prefs ->
                prefs[KEY_MODEL_OUTPUT_DISPLAY_MODE] = mode.storageKey
            }
        }
    }

    suspend fun saveStepConfig(config: AgentStepConfig): AgentStepConfig {
        val normalized = config.normalized()
        withContext(Dispatchers.IO) {
            requireContext().agentStepDataStore.edit { prefs ->
                prefs[KEY_CHAT_MAX_ITERATIONS] = normalized.chatMaxIterations
                prefs[KEY_PLANNER_MAX_ITERATIONS_PER_CHECKPOINT] = normalized.plannerMaxIterationsPerCheckpoint
                prefs[KEY_PLANNER_MAX_CHECKPOINTS] = normalized.plannerMaxCheckpoints
                prefs[KEY_DEVICE_ACTION_MAX_ITERATIONS] = normalized.deviceActionMaxIterations
                prefs[KEY_ENABLE_CROSS_CHAT_MEMORY] = normalized.enableCrossChatMemory
                prefs[KEY_MODEL_OUTPUT_DISPLAY_MODE] = normalized.modelOutputDisplayMode.storageKey
            }
        }
        return normalized
    }

    suspend fun resetToDefault() {
        saveStepConfig(AgentStepConfig.DEFAULT)
    }

    private fun readFromPreferences(prefs: Preferences): AgentStepConfig {
        val defaults = AgentStepConfig.DEFAULT
        return AgentStepConfig(
            chatMaxIterations = prefs[KEY_CHAT_MAX_ITERATIONS] ?: defaults.chatMaxIterations,
            plannerMaxIterationsPerCheckpoint = prefs[KEY_PLANNER_MAX_ITERATIONS_PER_CHECKPOINT]
                ?: defaults.plannerMaxIterationsPerCheckpoint,
            plannerMaxCheckpoints = prefs[KEY_PLANNER_MAX_CHECKPOINTS] ?: defaults.plannerMaxCheckpoints,
            deviceActionMaxIterations = prefs[KEY_DEVICE_ACTION_MAX_ITERATIONS] ?: defaults.deviceActionMaxIterations,
            enableCrossChatMemory = prefs[KEY_ENABLE_CROSS_CHAT_MEMORY] ?: defaults.enableCrossChatMemory,
            modelOutputDisplayMode = ModelOutputDisplayMode.fromStorageKey(
                prefs[KEY_MODEL_OUTPUT_DISPLAY_MODE],
            ),
        ).normalized()
    }
}
