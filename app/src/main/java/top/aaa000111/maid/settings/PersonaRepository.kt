package top.aaa000111.maid.settings

import android.content.Context
import android.content.res.AssetManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import java.io.InputStream

private val Context.personaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "maid_persona_configs",
)

/**
 * 从 `assets/persona/` 或应用本地目录扫描得到的 Persona 预设。
 */
data class PersonaPreset(
    val id: String,
    val name: String,
    val assetPath: String,
    val content: String,
    val isCustom: Boolean = false,
)

/**
 * 当前保存的 Persona 配置（同时控制助手的语气与性格）。
 */
data class PersonaConfig(
    val selectedPresetId: String? = null,
    val personaText: String = "",
) {
    val isConfigured: Boolean
        get() = personaText.trim().isNotEmpty()
}

/**
 * 持久化并管理助手 Persona（语气与性格设定），并自动扫描 `assets/persona/` 下的预设文件（全局单例）。
 */
object PersonaRepository {

    const val ASSETS_PERSONA_DIR = "persona"
    const val CUSTOM_PERSONA_ID_PREFIX = "custom/"
    const val DEFAULT_PRESET_ID = "默认严谨.md"

    private val KEY_SELECTED_PRESET_ID = stringPreferencesKey("selected_preset_id")
    private val KEY_PERSONA_TEXT = stringPreferencesKey("persona_text")
    private val KEY_EXPLICITLY_CLEARED = booleanPreferencesKey("persona_explicitly_cleared")

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
        return appContext ?: error("PersonaRepository must be initialized via init(context) first")
    }

    fun observePersonaConfig(): Flow<PersonaConfig> {
        return requireContext().personaDataStore.data
            .map { prefs -> readConfigFromPreferences(prefs) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun getPersonaConfig(): PersonaConfig = withContext(Dispatchers.IO) {
        readConfigFromPreferences(requireContext().personaDataStore.data.first())
    }

    suspend fun getEffectivePersonaText(): String = getPersonaConfig().personaText.trim()

    suspend fun savePersona(
        personaText: String,
        selectedPresetId: String? = null,
    ) {
        val normalizedText = personaText.trim()
        val normalizedPresetId = selectedPresetId?.trim()?.takeIf {
            it.isNotEmpty() && normalizedText.isNotEmpty()
        }
        withContext(Dispatchers.IO) {
            requireContext().personaDataStore.edit { prefs ->
                if (normalizedText.isEmpty()) {
                    prefs.remove(KEY_PERSONA_TEXT)
                    prefs.remove(KEY_SELECTED_PRESET_ID)
                    prefs[KEY_EXPLICITLY_CLEARED] = true
                } else {
                    prefs.remove(KEY_EXPLICITLY_CLEARED)
                    prefs[KEY_PERSONA_TEXT] = normalizedText
                    if (normalizedPresetId != null) {
                        prefs[KEY_SELECTED_PRESET_ID] = normalizedPresetId
                    } else {
                        prefs.remove(KEY_SELECTED_PRESET_ID)
                    }
                }
            }
        }
    }

    suspend fun selectAndSavePreset(preset: PersonaPreset) {
        savePersona(
            personaText = preset.content,
            selectedPresetId = preset.id,
        )
    }

    suspend fun clearPersona() {
        withContext(Dispatchers.IO) {
            requireContext().personaDataStore.edit { prefs ->
                prefs.remove(KEY_PERSONA_TEXT)
                prefs.remove(KEY_SELECTED_PRESET_ID)
                prefs[KEY_EXPLICITLY_CLEARED] = true
            }
        }
    }

    /**
     * 自动扫描应用 `assets/persona/` 内置预设以及用户添加的自定义预设文件。
     */
    suspend fun scanBuiltInPersonas(): List<PersonaPreset> = withContext(Dispatchers.IO) {
        val context = requireContext()
        val builtIn = scanFromAssetManager(context.assets)
        val custom = scanCustomPersonaDir(customPersonaDir(context))
        builtIn + custom
    }

    /**
     * 新增一条用户自定义 Persona 预设并持久化到本地目录。
     */
    suspend fun addCustomPersona(
        name: String,
        content: String,
    ): PersonaPreset = withContext(Dispatchers.IO) {
        val dir = customPersonaDir(requireContext())
        createCustomPersonaInDir(
            customDir = dir,
            name = name,
            content = content,
        )
    }

    /**
     * 删除指定 ID 的用户自定义 Persona 预设（内置预设不可删除）。
     */
    suspend fun deleteCustomPersona(presetId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = customPersonaDir(requireContext())
        deleteCustomPersonaInDir(
            customDir = dir,
            presetId = presetId,
        )
    }

    private fun customPersonaDir(context: Context): File = File(context.filesDir, ASSETS_PERSONA_DIR)

    internal fun createCustomPersonaInDir(
        customDir: File,
        name: String,
        content: String,
    ): PersonaPreset {
        val trimmedName = name.trim()
        val trimmedContent = content.trim()
        require(trimmedName.isNotEmpty()) { "Persona name must not be blank" }
        require(trimmedContent.isNotEmpty()) { "Persona content must not be blank" }

        if (!customDir.exists()) {
            customDir.mkdirs()
        }

        val sanitizedBase = trimmedName
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
            .trim('.', ' ')
            .ifEmpty { "persona_${System.currentTimeMillis()}" }

        var candidateFile = File(customDir, "$sanitizedBase.md")
        var suffix = 2
        while (candidateFile.exists()) {
            candidateFile = File(customDir, "${sanitizedBase}_$suffix.md")
            suffix += 1
        }
        candidateFile.writeText(trimmedContent, Charsets.UTF_8)

        return PersonaPreset(
            id = "$CUSTOM_PERSONA_ID_PREFIX${candidateFile.name}",
            name = trimmedName,
            assetPath = candidateFile.absolutePath,
            content = trimmedContent,
            isCustom = true,
        )
    }

    internal fun deleteCustomPersonaInDir(
        customDir: File,
        presetId: String,
    ): Boolean {
        if (!presetId.startsWith(CUSTOM_PERSONA_ID_PREFIX)) return false
        val relativeName = presetId.removePrefix(CUSTOM_PERSONA_ID_PREFIX).trim()
        if (relativeName.isEmpty() || !customDir.exists()) return false

        val dirCanonical = runCatching { customDir.canonicalFile }.getOrNull() ?: return false
        val targetFile = runCatching { File(dirCanonical, relativeName).canonicalFile }.getOrNull()
            ?: return false
        if (targetFile.parentFile != dirCanonical || !targetFile.isFile) {
            return false
        }
        return targetFile.delete()
    }

    internal fun scanCustomPersonaDir(customDir: File): List<PersonaPreset> {
        if (!customDir.exists() || !customDir.isDirectory) return emptyList()
        val files = customDir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile && it.canRead() }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { file ->
                val content = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
                if (content.isNullOrBlank()) {
                    null
                } else {
                    PersonaPreset(
                        id = "$CUSTOM_PERSONA_ID_PREFIX${file.name}",
                        name = deriveDisplayName(file.name),
                        assetPath = file.absolutePath,
                        content = content,
                        isCustom = true,
                    )
                }
            }
            .toList()
    }

    internal fun scanFromAssetManager(
        assetManager: AssetManager,
        rootDir: String = ASSETS_PERSONA_DIR,
    ): List<PersonaPreset> {
        return scanPersonaAssets(
            rootDir = rootDir,
            listEntries = { path -> assetManager.list(path) },
            openEntry = { path -> assetManager.open(path) },
        )
    }

    internal fun scanPersonaAssets(
        rootDir: String = ASSETS_PERSONA_DIR,
        listEntries: (String) -> Array<String>?,
        openEntry: (String) -> InputStream,
    ): List<PersonaPreset> {
        val normalizedRoot = rootDir.trim('/').ifEmpty { ASSETS_PERSONA_DIR }
        val presets = mutableListOf<PersonaPreset>()

        fun visit(currentPath: String, relativePrefix: String) {
            val children = runCatching { listEntries(currentPath) }.getOrNull() ?: return
            for (childName in children.sorted()) {
                if (childName.isBlank()) continue
                val childAssetPath = "$currentPath/$childName"
                val childRelativeId = if (relativePrefix.isEmpty()) {
                    childName
                } else {
                    "$relativePrefix/$childName"
                }

                val subEntries = runCatching { listEntries(childAssetPath) }.getOrNull()
                if (!subEntries.isNullOrEmpty()) {
                    visit(currentPath = childAssetPath, relativePrefix = childRelativeId)
                } else {
                    val content = runCatching {
                        openEntry(childAssetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                            reader.readText().trim()
                        }
                    }.getOrNull()
                    if (!content.isNullOrBlank()) {
                        presets += PersonaPreset(
                            id = childRelativeId,
                            name = deriveDisplayName(childRelativeId),
                            assetPath = childAssetPath,
                            content = content,
                        )
                    }
                }
            }
        }

        visit(currentPath = normalizedRoot, relativePrefix = "")
        return presets.sortedBy { it.id.lowercase() }
    }

    internal fun deriveDisplayName(relativePath: String): String {
        val lastSlash = relativePath.lastIndexOf('/')
        val lastDot = relativePath.lastIndexOf('.')
        return if (lastDot > lastSlash) {
            relativePath.substring(0, lastDot)
        } else {
            relativePath
        }
    }

    private fun readConfigFromPreferences(prefs: Preferences): PersonaConfig {
        val text = prefs[KEY_PERSONA_TEXT]?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val presetId = prefs[KEY_SELECTED_PRESET_ID]?.trim()?.takeIf { it.isNotEmpty() }
            return PersonaConfig(
                selectedPresetId = presetId,
                personaText = text,
            )
        }
        val explicitlyCleared = prefs[KEY_EXPLICITLY_CLEARED] == true
        if (explicitlyCleared) {
            return PersonaConfig()
        }
        return resolveDefaultPersonaConfig(
            presets = scanFromAssetManager(requireContext().assets),
        )
    }

    internal fun resolveDefaultPersonaConfig(presets: List<PersonaPreset>): PersonaConfig {
        val defaultPreset = presets.firstOrNull { it.id == DEFAULT_PRESET_ID }
            ?: presets.firstOrNull()
            ?: return PersonaConfig()
        return PersonaConfig(
            selectedPresetId = defaultPreset.id,
            personaText = defaultPreset.content,
        )
    }
}
