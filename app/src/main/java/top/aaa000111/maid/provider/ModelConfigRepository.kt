package top.aaa000111.maid.provider

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val Context.modelConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "maid_model_configs",
)

internal object CustomProviderProfileSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(profiles: List<CustomProviderProfile>): String {
        val array = buildJsonArray {
            profiles.forEach { profile ->
                val id = profile.normalizedId
                if (id.isEmpty()) return@forEach
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("name", JsonPrimitive(profile.normalizedName.ifEmpty { id }))
                        put("base_url", JsonPrimitive(profile.normalizedBaseUrl))
                    },
                )
            }
        }
        return json.encodeToString(JsonArray.serializer(), array)
    }

    fun decode(raw: String?): List<CustomProviderProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(raw).jsonArray
            val seen = LinkedHashSet<String>()
            val result = ArrayList<CustomProviderProfile>(array.size)
            for (elem in array) {
                val obj = elem as? JsonObject ?: continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (id.isEmpty() || !seen.add(id)) continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: id
                val baseUrl = obj["base_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                result += CustomProviderProfile(id = id, name = name, baseUrl = baseUrl)
            }
            result
        }.getOrDefault(emptyList())
    }
}

/**
 * 持久化存储四种能力模型角色（快速廉价、复杂规划、快速视觉、复杂视觉）的 Provider、Model、
 * 模型能力元数据快照，以及多自定义 OpenAI 兼容端点档案（全局单例）。
 */
object ModelConfigRepository {

    const val DEFAULT_CUSTOM_PROFILE_NAME = "默认自定义端点"

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
        return appContext ?: error("ModelConfigRepository must be initialized via init(context) first")
    }

    fun observeAllConfigs(): Flow<Map<ModelRole, RoleModelSelection>> {
        return requireContext().modelConfigDataStore.data
            .map { prefs -> readConfigsFromPreferences(prefs) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun getAllConfigs(): Map<ModelRole, RoleModelSelection> = withContext(Dispatchers.IO) {
        readConfigsFromPreferences(requireContext().modelConfigDataStore.data.first())
    }

    fun observeCustomBaseUrl(): Flow<String> {
        return requireContext().modelConfigDataStore.data
            .map { prefs -> resolveBaseUrlFromPrefs(prefs, CustomProviderProfile.DEFAULT_ID) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun getCustomBaseUrl(): String = withContext(Dispatchers.IO) {
        resolveBaseUrlFromPrefs(
            requireContext().modelConfigDataStore.data.first(),
            CustomProviderProfile.DEFAULT_ID,
        )
    }

    /**
     * 观察所有自定义端点档案（始终保证默认档案位于首位，且向后兼容旧版 `custom_provider_base_url`）。
     */
    fun observeCustomProfiles(): Flow<List<CustomProviderProfile>> {
        return requireContext().modelConfigDataStore.data
            .map { prefs -> readCustomProfilesFromPrefs(prefs) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun getCustomProfiles(): List<CustomProviderProfile> = withContext(Dispatchers.IO) {
        readCustomProfilesFromPrefs(requireContext().modelConfigDataStore.data.first())
    }

    suspend fun resolveCustomBaseUrl(customProviderId: String? = null): String = withContext(Dispatchers.IO) {
        resolveBaseUrlFromPrefs(
            requireContext().modelConfigDataStore.data.first(),
            customProviderId,
        )
    }

    suspend fun saveCustomBaseUrl(rawUrl: String) {
        val trimmed = rawUrl.trim()
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                if (trimmed.isEmpty()) {
                    prefs.remove(customBaseUrlKey())
                } else {
                    prefs[customBaseUrlKey()] = trimmed
                }
                val current = readCustomProfilesFromPrefs(prefs).toMutableList()
                val idx = current.indexOfFirst { it.normalizedId == CustomProviderProfile.DEFAULT_ID }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(baseUrl = trimmed)
                } else {
                    current.add(
                        0,
                        CustomProviderProfile(
                            id = CustomProviderProfile.DEFAULT_ID,
                            name = DEFAULT_CUSTOM_PROFILE_NAME,
                            baseUrl = trimmed,
                        ),
                    )
                }
                prefs[customProfilesJsonKey()] = CustomProviderProfileSerializer.encode(current)
            }
        }
    }

    suspend fun upsertCustomProfile(profile: CustomProviderProfile) {
        val normalizedId = profile.normalizedId
        require(normalizedId.isNotEmpty()) { "Custom provider profile id cannot be blank" }
        val normalized = CustomProviderProfile(
            id = normalizedId,
            name = profile.normalizedName.ifEmpty {
                if (normalizedId == CustomProviderProfile.DEFAULT_ID) {
                    DEFAULT_CUSTOM_PROFILE_NAME
                } else {
                    normalizedId
                }
            },
            baseUrl = profile.normalizedBaseUrl,
        )
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                val current = readCustomProfilesFromPrefs(prefs).toMutableList()
                val idx = current.indexOfFirst { it.normalizedId == normalizedId }
                if (idx >= 0) {
                    current[idx] = normalized
                } else {
                    current += normalized
                }
                prefs[customProfilesJsonKey()] = CustomProviderProfileSerializer.encode(current)
                if (normalizedId == CustomProviderProfile.DEFAULT_ID) {
                    if (normalized.baseUrl.isEmpty()) {
                        prefs.remove(customBaseUrlKey())
                    } else {
                        prefs[customBaseUrlKey()] = normalized.baseUrl
                    }
                }
            }
        }
    }

    suspend fun deleteCustomProfile(profileId: String) {
        val normalizedId = profileId.trim()
        if (normalizedId.isEmpty()) return
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                if (normalizedId == CustomProviderProfile.DEFAULT_ID) {
                    prefs.remove(customBaseUrlKey())
                    val current = readCustomProfilesFromPrefs(prefs).map {
                        if (it.normalizedId == CustomProviderProfile.DEFAULT_ID) {
                            it.copy(name = DEFAULT_CUSTOM_PROFILE_NAME, baseUrl = "")
                        } else {
                            it
                        }
                    }
                    prefs[customProfilesJsonKey()] = CustomProviderProfileSerializer.encode(current)
                } else {
                    val remaining = readCustomProfilesFromPrefs(prefs)
                        .filterNot { it.normalizedId == normalizedId }
                    prefs[customProfilesJsonKey()] = CustomProviderProfileSerializer.encode(remaining)
                }
            }
        }
    }

    suspend fun clearAllCustomProfiles() {
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                prefs.remove(customBaseUrlKey())
                prefs.remove(customProfilesJsonKey())
            }
        }
    }

    suspend fun saveAllConfigs(configs: Map<ModelRole, RoleModelSelection>) {
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                ModelRole.entries.forEach { role ->
                    val selection = configs[role] ?: RoleModelSelection()
                    val pKey = providerPrefKey(role)
                    val mKey = modelPrefKey(role)
                    val cKey = customProviderIdPrefKey(role)
                    val ctxKey = metaContextLengthPrefKey(role)
                    val visionKey = metaVisionPrefKey(role)
                    val toolsKey = metaToolsPrefKey(role)
                    val reasoningKey = metaReasoningPrefKey(role)

                    val provider = selection.provider
                    val modelId = selection.normalizedModelId
                    if (provider != null && modelId != null) {
                        prefs[pKey] = provider.id
                        prefs[mKey] = modelId
                        val customId = selection.normalizedCustomProviderId
                        if (customId != null && customId != CustomProviderProfile.DEFAULT_ID) {
                            prefs[cKey] = customId
                        } else {
                            prefs.remove(cKey)
                        }
                        val meta = selection.modelMetadata
                        if (meta?.contextLength != null && meta.contextLength > 0L) {
                            prefs[ctxKey] = meta.contextLength
                        } else {
                            prefs.remove(ctxKey)
                        }
                        if (meta?.supportsVision != null) {
                            prefs[visionKey] = meta.supportsVision
                        } else {
                            prefs.remove(visionKey)
                        }
                        if (meta?.supportsTools != null) {
                            prefs[toolsKey] = meta.supportsTools
                        } else {
                            prefs.remove(toolsKey)
                        }
                        if (meta?.supportsReasoning != null) {
                            prefs[reasoningKey] = meta.supportsReasoning
                        } else {
                            prefs.remove(reasoningKey)
                        }
                    } else {
                        prefs.remove(pKey)
                        prefs.remove(mKey)
                        prefs.remove(cKey)
                        prefs.remove(ctxKey)
                        prefs.remove(visionKey)
                        prefs.remove(toolsKey)
                        prefs.remove(reasoningKey)
                    }
                }
            }
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                ModelRole.entries.forEach { role ->
                    prefs.remove(providerPrefKey(role))
                    prefs.remove(modelPrefKey(role))
                    prefs.remove(customProviderIdPrefKey(role))
                    prefs.remove(metaContextLengthPrefKey(role))
                    prefs.remove(metaVisionPrefKey(role))
                    prefs.remove(metaToolsPrefKey(role))
                    prefs.remove(metaReasoningPrefKey(role))
                }
            }
        }
    }

    private fun readConfigsFromPreferences(prefs: Preferences): Map<ModelRole, RoleModelSelection> {
        return ModelRole.entries.associateWith { role ->
            val rawProvider = prefs[providerPrefKey(role)]
            val provider = rawProvider?.let { id ->
                LlmProvider.entries.firstOrNull { it.id == id }
            }
            val modelId = prefs[modelPrefKey(role)]?.trim()?.takeIf { it.isNotEmpty() }
            if (provider != null && modelId != null) {
                val customProviderId = if (provider == LlmProvider.CUSTOM) {
                    prefs[customProviderIdPrefKey(role)]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: CustomProviderProfile.DEFAULT_ID
                } else {
                    null
                }
                val meta = LlmModelMetadata(
                    contextLength = prefs[metaContextLengthPrefKey(role)]?.takeIf { it > 0L },
                    supportsVision = prefs[metaVisionPrefKey(role)],
                    supportsTools = prefs[metaToolsPrefKey(role)],
                    supportsReasoning = prefs[metaReasoningPrefKey(role)],
                ).takeUnless { it.isEmpty }
                RoleModelSelection(
                    provider = provider,
                    modelId = modelId,
                    customProviderId = customProviderId,
                    modelMetadata = meta,
                )
            } else {
                RoleModelSelection()
            }
        }
    }

    private fun readCustomProfilesFromPrefs(prefs: Preferences): List<CustomProviderProfile> {
        val legacyBaseUrl = prefs[customBaseUrlKey()].orEmpty().trim()
        val decoded = CustomProviderProfileSerializer.decode(prefs[customProfilesJsonKey()])
        if (decoded.isEmpty()) {
            return listOf(
                CustomProviderProfile(
                    id = CustomProviderProfile.DEFAULT_ID,
                    name = DEFAULT_CUSTOM_PROFILE_NAME,
                    baseUrl = legacyBaseUrl,
                ),
            )
        }
        val hasDefault = decoded.any { it.normalizedId == CustomProviderProfile.DEFAULT_ID }
        return if (hasDefault) {
            decoded.map { profile ->
                if (profile.normalizedId == CustomProviderProfile.DEFAULT_ID &&
                    profile.baseUrl.isBlank() &&
                    legacyBaseUrl.isNotBlank()
                ) {
                    profile.copy(baseUrl = legacyBaseUrl)
                } else {
                    profile
                }
            }
        } else {
            listOf(
                CustomProviderProfile(
                    id = CustomProviderProfile.DEFAULT_ID,
                    name = DEFAULT_CUSTOM_PROFILE_NAME,
                    baseUrl = legacyBaseUrl,
                ),
            ) + decoded
        }
    }

    private fun resolveBaseUrlFromPrefs(prefs: Preferences, customProviderId: String?): String {
        val targetId = customProviderId?.trim()?.takeIf { it.isNotEmpty() }
            ?: CustomProviderProfile.DEFAULT_ID
        val profiles = readCustomProfilesFromPrefs(prefs)
        val matched = profiles.firstOrNull { it.normalizedId == targetId }?.normalizedBaseUrl
        if (!matched.isNullOrBlank()) return matched
        return if (targetId == CustomProviderProfile.DEFAULT_ID) {
            prefs[customBaseUrlKey()].orEmpty().trim()
        } else {
            ""
        }
    }

    private fun providerPrefKey(role: ModelRole) =
        stringPreferencesKey("${role.keyPrefix}_provider")

    private fun modelPrefKey(role: ModelRole) =
        stringPreferencesKey("${role.keyPrefix}_model")

    private fun customProviderIdPrefKey(role: ModelRole) =
        stringPreferencesKey("${role.keyPrefix}_custom_provider_id")

    private fun metaContextLengthPrefKey(role: ModelRole) =
        longPreferencesKey("${role.keyPrefix}_meta_context_length")

    private fun metaVisionPrefKey(role: ModelRole) =
        booleanPreferencesKey("${role.keyPrefix}_meta_vision")

    private fun metaToolsPrefKey(role: ModelRole) =
        booleanPreferencesKey("${role.keyPrefix}_meta_tools")

    private fun metaReasoningPrefKey(role: ModelRole) =
        booleanPreferencesKey("${role.keyPrefix}_meta_reasoning")

    private fun customBaseUrlKey() =
        stringPreferencesKey("custom_provider_base_url")

    private fun customProfilesJsonKey() =
        stringPreferencesKey("custom_provider_profiles_json")
}
