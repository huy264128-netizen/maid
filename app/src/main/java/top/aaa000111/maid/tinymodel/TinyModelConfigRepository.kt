package top.aaa000111.maid.tinymodel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.tinyModelConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "maid_tiny_model_configs",
)

/**
 * 持久化保存的端侧 TinyModel 选择项与硬件后端偏好。
 */
data class TinyModelSelection(
    val selectedModelId: String? = null,
    val selectedAssetPath: String? = null,
    val selectedCustomModelPath: String? = null,
    val backendPreference: TinyModelBackendPreference = TinyModelBackendPreference.AUTO,
)

/**
 * 持久化与观察端侧 TinyModel 选择配置的全局单例仓库。
 */
object TinyModelConfigRepository {

    private val KEY_MODEL_ID = stringPreferencesKey("tiny_model_id")
    private val KEY_ASSET_PATH = stringPreferencesKey("tiny_model_asset_path")
    private val KEY_CUSTOM_PATH = stringPreferencesKey("tiny_model_custom_path")
    private val KEY_BACKEND_PREFERENCE = stringPreferencesKey("tiny_model_backend_preference")

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
        return appContext ?: error("TinyModelConfigRepository must be initialized via init(context) first")
    }

    fun observeSelection(): Flow<TinyModelSelection> {
        return requireContext().tinyModelConfigDataStore.data
            .map { prefs -> readFromPreferences(prefs) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun getSelection(): TinyModelSelection = withContext(Dispatchers.IO) {
        readFromPreferences(requireContext().tinyModelConfigDataStore.data.first())
    }

    suspend fun saveSelection(selection: TinyModelSelection) {
        withContext(Dispatchers.IO) {
            requireContext().tinyModelConfigDataStore.edit { prefs ->
                putOrRemove(prefs, KEY_MODEL_ID, selection.selectedModelId)
                putOrRemove(prefs, KEY_ASSET_PATH, selection.selectedAssetPath)
                putOrRemove(prefs, KEY_CUSTOM_PATH, selection.selectedCustomModelPath)
                prefs[KEY_BACKEND_PREFERENCE] = selection.backendPreference.name
            }
        }
    }

    suspend fun selectOption(option: TinyModelOption) {
        val current = getSelection()
        saveSelection(
            current.copy(
                selectedModelId = option.spec.id,
                selectedAssetPath = if (option.sourceType == TinyModelSourceType.BUNDLED_ASSET) {
                    option.spec.assetPath
                } else {
                    null
                },
                selectedCustomModelPath = option.customModelPath,
            ),
        )
    }

    suspend fun saveBackendPreference(preference: TinyModelBackendPreference) {
        val current = getSelection()
        saveSelection(current.copy(backendPreference = preference))
    }

    suspend fun resetToDefault() {
        saveSelection(TinyModelSelection())
    }

    /**
     * 根据持久化选择与当前扫描到的可用模型列表，定位当前生效的 [TinyModelOption]。
     */
    fun resolveSelectedOption(
        selection: TinyModelSelection,
        availableOptions: List<TinyModelOption>,
    ): TinyModelOption? {
        if (availableOptions.isEmpty()) return null

        val customPath = selection.selectedCustomModelPath?.trim()?.takeIf { it.isNotEmpty() }
        if (customPath != null) {
            availableOptions.firstOrNull { it.customModelPath == customPath }?.let { return it }
        }

        val assetPath = selection.selectedAssetPath?.trim()?.takeIf { it.isNotEmpty() }
        if (assetPath != null) {
            availableOptions.firstOrNull {
                it.sourceType == TinyModelSourceType.BUNDLED_ASSET &&
                    it.spec.assetPath.equals(assetPath, ignoreCase = true)
            }?.let { return it }
        }

        val modelId = selection.selectedModelId?.trim()?.takeIf { it.isNotEmpty() }
        if (modelId != null) {
            availableOptions.firstOrNull { it.spec.id.equals(modelId, ignoreCase = true) }?.let { return it }
        }

        return availableOptions.firstOrNull {
            it.sourceType == TinyModelSourceType.BUNDLED_ASSET &&
                it.spec.id == TinyModelCatalog.DEFAULT.id
        } ?: availableOptions.first()
    }

    /**
     * 将持久化选择解析为可供 [TinyModel] 直接使用的 [TinyModelConfig]。
     */
    fun resolveEffectiveConfig(
        selection: TinyModelSelection,
        availableOptions: List<TinyModelOption>,
    ): TinyModelConfig {
        val matched = resolveSelectedOption(selection, availableOptions)
        if (matched != null) {
            return TinyModelConfig(
                spec = matched.spec,
                backendPreference = selection.backendPreference,
                customModelPath = matched.customModelPath,
            )
        }

        val customPath = selection.selectedCustomModelPath?.trim()?.takeIf { it.isNotEmpty() }
        if (customPath != null) {
            val file = File(customPath)
            if (file.isFile && file.length() > 0L) {
                val spec = TinyModelCatalog.specForDiscoveredFile(fileName = file.name)
                return TinyModelConfig(
                    spec = spec,
                    backendPreference = selection.backendPreference,
                    customModelPath = file.absolutePath,
                )
            }
        }

        return TinyModelConfig(
            spec = TinyModelCatalog.DEFAULT,
            backendPreference = selection.backendPreference,
        )
    }

    private fun readFromPreferences(prefs: Preferences): TinyModelSelection {
        val backend = prefs[KEY_BACKEND_PREFERENCE]
            ?.let { raw -> TinyModelBackendPreference.entries.firstOrNull { it.name == raw } }
            ?: TinyModelBackendPreference.AUTO
        return TinyModelSelection(
            selectedModelId = prefs[KEY_MODEL_ID]?.trim()?.takeIf { it.isNotEmpty() },
            selectedAssetPath = prefs[KEY_ASSET_PATH]?.trim()?.takeIf { it.isNotEmpty() },
            selectedCustomModelPath = prefs[KEY_CUSTOM_PATH]?.trim()?.takeIf { it.isNotEmpty() },
            backendPreference = backend,
        )
    }

    private fun putOrRemove(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String?,
    ) {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) {
            prefs[key] = trimmed
        } else {
            prefs.remove(key)
        }
    }
}
