package top.aaa000111.maid.provider

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
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

    fun observeModelHistory(): Flow<Map<ModelRole, List<RoleModelSelection>>> {
        return requireContext().modelConfigDataStore.data.map { prefs ->
            ModelRole.entries.associateWith { role ->
                RoleModelHistory.decode(prefs[historyPrefKey(role)])
            }
        }.flowOn(Dispatchers.IO)
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

    fun observeModelGroups(): Flow<ModelGroups> =
        requireContext().modelConfigDataStore.data.map(::readModelGroups).flowOn(Dispatchers.IO)

    fun observeImageGenModelSelection(): Flow<ImageGenModelSelection> =
        requireContext().modelConfigDataStore.data.map(::readImageGenModelSelection).flowOn(Dispatchers.IO)

    suspend fun getImageGenModelSelection(): ImageGenModelSelection = withContext(Dispatchers.IO) {
        readImageGenModelSelection(requireContext().modelConfigDataStore.data.first())
    }

    suspend fun saveImageGenModelSelection(selection: ImageGenModelSelection) {
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                val provider = selection.provider?.takeIf { it in imageGenProviders }
                if (provider == null) {
                    prefs.remove(imageGenProviderKey)
                    prefs.remove(imageGenModelKey)
                    prefs.remove(imageGenCustomProviderIdKey)
                } else {
                    prefs[imageGenProviderKey] = provider.id
                    selection.normalizedModelId.takeIf { it.isNotEmpty() }?.let {
                        prefs[imageGenModelKey] = it
                    } ?: prefs.remove(imageGenModelKey)
                    selection.normalizedCustomProviderId?.takeIf { it != CustomProviderProfile.DEFAULT_ID }?.let {
                        prefs[imageGenCustomProviderIdKey] = it
                    } ?: prefs.remove(imageGenCustomProviderIdKey)
                }
            }
        }
    }

    private fun readImageGenModelSelection(prefs: Preferences): ImageGenModelSelection {
        val provider = prefs[imageGenProviderKey]?.let { id -> imageGenProviders.firstOrNull { it.id == id } }
            ?: return ImageGenModelSelection()
        return ImageGenModelSelection(
            provider = provider,
            modelId = prefs[imageGenModelKey].orEmpty(),
            customProviderId = if (provider == LlmProvider.CUSTOM) {
                prefs[imageGenCustomProviderIdKey] ?: CustomProviderProfile.DEFAULT_ID
            } else null,
        )
    }

    suspend fun activateModelGroup(id: String) {
        requireContext().modelConfigDataStore.edit { prefs ->
            val state = readModelGroups(prefs)
            if (id == state.activeId) return@edit
            val group = state.groups.firstOrNull { it.id == id } ?: error("Unknown model group")
            ModelRole.entries.forEach { role ->
                writeRoleConfig(prefs, role, group.selections[role] ?: RoleModelSelection())
            }
            prefs[groupsKey] = ModelGroupJson.encode(state.groups)
            prefs[activeGroupKey] = id
        }
    }

    private fun readModelGroups(prefs: Preferences): ModelGroups {
        val activeId = prefs[activeGroupKey] ?: ModelConfigGroup.DEFAULT_ID
        val groups = ModelGroupJson.decode(prefs[groupsKey])
        val active = ModelConfigGroup(
            id = activeId,
            name = groups.firstOrNull { it.id == activeId }?.name.orEmpty(),
            selections = readConfigsFromPreferences(prefs),
        )
        return ModelGroups(groups.replaceGroup(active), activeId)
    }

    suspend fun saveAllConfigs(
        configs: Map<ModelRole, RoleModelSelection>,
        histories: Map<ModelRole, List<RoleModelSelection>> = emptyMap(),
        newGroupName: String? = null,
        expectedActiveGroupId: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                val state = readModelGroups(prefs)
                check(expectedActiveGroupId == null || expectedActiveGroupId == state.activeId)
                val group = if (newGroupName != null) {
                    val name = newGroupName.trim()
                    require(name.isNotEmpty() && state.groups.none { it.name.equals(name, ignoreCase = true) })
                    ModelConfigGroup(java.util.UUID.randomUUID().toString(), name, configs)
                } else {
                    state.groups.first { it.id == state.activeId }.copy(selections = configs)
                }
                ModelRole.entries.forEach { role ->
                    writeRoleConfig(prefs, role, configs[role] ?: RoleModelSelection(), histories[role].orEmpty())
                }
                prefs[groupsKey] = ModelGroupJson.encode(state.groups.replaceGroup(group))
                prefs[activeGroupKey] = group.id
            }
        }
    }

    internal fun writeRoleConfig(
        prefs: MutablePreferences,
        role: ModelRole,
        selection: RoleModelSelection,
        historyOverrides: List<RoleModelSelection> = emptyList(),
    ) {
        val previous = readConfigsFromPreferences(prefs).getValue(role)
        var history = RoleModelHistory.remember(RoleModelHistory.decode(prefs[historyPrefKey(role)]), previous)
        historyOverrides.asReversed().forEach { history = RoleModelHistory.remember(history, it) }
        history = RoleModelHistory.remember(history, selection)
        prefs[historyPrefKey(role)] = RoleModelHistory.encode(history)
        val provider = selection.provider
        if (provider != null) prefs[providerPrefKey(role)] = provider.id
        else prefs.remove(providerPrefKey(role))
        selection.normalizedModelId?.takeIf { provider != null }?.let {
            prefs[modelPrefKey(role)] = it
        } ?: prefs.remove(modelPrefKey(role))
        val customId = selection.normalizedCustomProviderId
        if (customId != null && customId != CustomProviderProfile.DEFAULT_ID) {
            prefs[customProviderIdPrefKey(role)] = customId
        } else prefs.remove(customProviderIdPrefKey(role))
        if (selection.isComplete && selection.reasoningEffort != ModelReasoningEffort.DEFAULT) {
            prefs[reasoningEffortPrefKey(role)] = selection.reasoningEffort.id
        } else {
            prefs.remove(reasoningEffortPrefKey(role))
        }
        val meta = selection.modelMetadata?.takeIf { selection.isComplete }
        meta?.contextLength?.takeIf { it > 0 }?.let { prefs[metaContextLengthPrefKey(role)] = it }
            ?: prefs.remove(metaContextLengthPrefKey(role))
        meta?.supportsVision?.let { prefs[metaVisionPrefKey(role)] = it }
            ?: prefs.remove(metaVisionPrefKey(role))
        meta?.supportsTools?.let { prefs[metaToolsPrefKey(role)] = it }
            ?: prefs.remove(metaToolsPrefKey(role))
        meta?.supportsReasoning?.let { prefs[metaReasoningPrefKey(role)] = it }
            ?: prefs.remove(metaReasoningPrefKey(role))
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            requireContext().modelConfigDataStore.edit { prefs ->
                prefs.remove(groupsKey)
                prefs.remove(activeGroupKey)
                ModelRole.entries.forEach { role ->
                    prefs.remove(providerPrefKey(role))
                    prefs.remove(modelPrefKey(role))
                    prefs.remove(customProviderIdPrefKey(role))
                    prefs.remove(reasoningEffortPrefKey(role))
                    prefs.remove(historyPrefKey(role))
                    prefs.remove(metaContextLengthPrefKey(role))
                    prefs.remove(metaVisionPrefKey(role))
                    prefs.remove(metaToolsPrefKey(role))
                    prefs.remove(metaReasoningPrefKey(role))
                }
            }
        }
    }

    internal fun readConfigsFromPreferences(prefs: Preferences): Map<ModelRole, RoleModelSelection> {
        return ModelRole.entries.associateWith { role ->
            val rawProvider = prefs[providerPrefKey(role)]
            val provider = rawProvider?.let { id ->
                LlmProvider.entries.firstOrNull { it.id == id }
            }
            val modelId = prefs[modelPrefKey(role)]?.trim()?.takeIf { it.isNotEmpty() }
            if (provider != null) {
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
                    reasoningEffort = ModelReasoningEffort.fromId(prefs[reasoningEffortPrefKey(role)]),
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

    private val groupsKey = stringPreferencesKey("model_groups_json")
    private val activeGroupKey = stringPreferencesKey("active_model_group_id")
    private val imageGenProviderKey = stringPreferencesKey("image_gen_provider")
    private val imageGenModelKey = stringPreferencesKey("image_gen_model")
    private val imageGenCustomProviderIdKey = stringPreferencesKey("image_gen_custom_provider_id")
    private val imageGenProviders = setOf(
        LlmProvider.OPENAI,
        LlmProvider.GEMINI,
        LlmProvider.XAI,
        LlmProvider.OPENROUTER,
        LlmProvider.CUSTOM,
    )

    private fun historyPrefKey(role: ModelRole) =
        stringPreferencesKey("${role.keyPrefix}_selection_history")

    private fun reasoningEffortPrefKey(role: ModelRole) =
        stringPreferencesKey("${role.keyPrefix}_reasoning_effort")

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
