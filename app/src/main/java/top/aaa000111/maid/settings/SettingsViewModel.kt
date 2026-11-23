package top.aaa000111.maid.settings

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.aaa000111.maid.R
import top.aaa000111.maid.chat.ChatBackgroundRuntime
import top.aaa000111.maid.chat.ChatSessionRepository
import top.aaa000111.maid.memory.MaidMemoryManager
import top.aaa000111.maid.provider.ChatGptAuthRepository
import top.aaa000111.maid.provider.ChatGptAuthState
import top.aaa000111.maid.provider.CustomProviderProfile
import top.aaa000111.maid.provider.LlmKeyRepository
import top.aaa000111.maid.provider.LlmModelOption
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.LlmProviderRegistry
import top.aaa000111.maid.provider.ModelGroups
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.ImageGenModelSelection
import top.aaa000111.maid.provider.ModelProviderTarget
import top.aaa000111.maid.provider.ModelReasoningEffort
import top.aaa000111.maid.provider.availableReasoningEfforts
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.ProviderAvailability
import top.aaa000111.maid.provider.RoleModelHistory
import top.aaa000111.maid.provider.RoleModelSelection
import java.util.UUID
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.search.ProotSearXngManager
import top.aaa000111.maid.search.ProotSearXngStatus
import top.aaa000111.maid.search.SearchConfigRepository
import top.aaa000111.maid.search.SearchEngineAvailability
import top.aaa000111.maid.search.SearchSettingsConfig
import top.aaa000111.maid.search.SearxngMode
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.DefaultAndroidToolSets
import top.aaa000111.maid.androidtool.PrivilegeManager
import top.aaa000111.maid.androidtool.PrivilegeOverview
import top.aaa000111.maid.androidtool.ScreenCaptureResult
import top.aaa000111.maid.proot.RootfsArchiveStatus
import top.aaa000111.maid.proot.TermuxProotRuntime
import top.aaa000111.maid.tinymodel.TinyModelBackendPreference
import top.aaa000111.maid.tinymodel.TinyModelConfig
import top.aaa000111.maid.tinymodel.TinyModelConfigRepository
import top.aaa000111.maid.tinymodel.TinyModelFileStore
import top.aaa000111.maid.tinymodel.TinyModelOption
import top.aaa000111.maid.tinymodel.TinyModelSelection
import top.aaa000111.maid.tinymodel.TinyModelSourceType
import top.aaa000111.maid.tinymodel.TinyModelState

data class SettingsUiState(
    val selectedProvider: LlmProvider = LlmProvider.OPENAI,
    val inputText: String = "",
    @param:StringRes val inputErrorRes: Int? = null,
    val customBaseUrlInput: String = "",
    val savedCustomBaseUrl: String = "",
    val customProfiles: List<CustomProviderProfile> = listOf(
        CustomProviderProfile(
            id = CustomProviderProfile.DEFAULT_ID,
            name = ModelConfigRepository.DEFAULT_CUSTOM_PROFILE_NAME,
        ),
    ),
    val selectedCustomProfileId: String = CustomProviderProfile.DEFAULT_ID,
    val customProfileNameInput: String = ModelConfigRepository.DEFAULT_CUSTOM_PROFILE_NAME,
    val customProfileStoredKeys: Map<String, String?> = emptyMap(),
    val storedKeys: Map<LlmProvider, String?> = emptyMap(),
    val providerAvailability: Map<LlmProvider, ProviderAvailability> = emptyMap(),
    val chatGptAuthState: ChatGptAuthState = ChatGptAuthState(),
    val isBusy: Boolean = false,
    val isLoaded: Boolean = false,
    val modelGroups: ModelGroups = ModelGroups(),
    val isCreatingModelGroup: Boolean = false,
    val savedModelConfigs: Map<ModelRole, RoleModelSelection> = emptyMap(),
    val draftModelConfigs: Map<ModelRole, RoleModelSelection> = emptyMap(),
    val remoteModelsByProvider: Map<String, List<LlmModelOption>> = emptyMap(),
    val loadingModelProviders: Set<String> = emptySet(),
    val modelFetchErrors: Map<String, String> = emptyMap(),
    val isSavingModels: Boolean = false,
    val imageGenModelSelection: ImageGenModelSelection = ImageGenModelSelection(),
    val draftImageGenModelSelection: ImageGenModelSelection = ImageGenModelSelection(),
    val personaPresets: List<PersonaPreset> = emptyList(),
    val isScanningPersonas: Boolean = false,
    val savedPersonaConfig: PersonaConfig = PersonaConfig(),
    val draftSelectedPresetId: String? = null,
    val draftPersonaText: String = "",
    val isSavingPersona: Boolean = false,
    val privilegeOverview: PrivilegeOverview = PrivilegeOverview(),
    val unlockedToolSetCount: Int = 0,
    val totalToolSetCount: Int = 10,
    val requestingPrivilege: AndroidPrivilege? = null,
    val hasCheckedRoot: Boolean = false,
    val isCapturingScreenshot: Boolean = false,
    val lastScreenshotResult: ScreenCaptureResult? = null,
    val lastScreenshotError: String? = null,
    val safetyConfig: ModelSafetyConfig = ModelSafetyConfig.DEFAULT,
    val savedAgentStepConfig: AgentStepConfig = AgentStepConfig.DEFAULT,
    val draftChatStepInput: String = AgentStepConfig.DEFAULT_CHAT_MAX_ITERATIONS.toString(),
    val draftPlannerStepInput: String = AgentStepConfig.DEFAULT_PLANNER_MAX_ITERATIONS_PER_CHECKPOINT.toString(),
    val draftPlannerCheckpointInput: String = AgentStepConfig.DEFAULT_PLANNER_MAX_CHECKPOINTS.toString(),
    val draftDeviceActionStepInput: String = AgentStepConfig.DEFAULT_DEVICE_ACTION_MAX_ITERATIONS.toString(),
    val isSavingAgentSteps: Boolean = false,
    val rootfsArchiveStatus: RootfsArchiveStatus = RootfsArchiveStatus(),
    val tinyModelOptions: List<TinyModelOption> = emptyList(),
    val tinyModelSelection: TinyModelSelection = TinyModelSelection(),
    val tinyModelRuntimeState: TinyModelState = TinyModelState.Uninitialized,
    val isScanningTinyModels: Boolean = false,
    val isImportingTinyModel: Boolean = false,
    val searchSettingsConfig: SearchSettingsConfig = SearchSettingsConfig(),
    val prootSearxngStatus: ProotSearXngStatus = ProotSearXngStatus(),
    val engineAvailabilityList: List<SearchEngineAvailability> = emptyList(),
    val isProbingSearchEngines: Boolean = false,
) {
    val searxngBaseUrl: String
        get() = searchSettingsConfig.searxngBaseUrl
    val currentStoredKey: String?
        get() = if (selectedProvider == LlmProvider.CUSTOM) {
            customProfileStoredKeys[selectedCustomProfileId]
                ?: if (selectedCustomProfileId == CustomProviderProfile.DEFAULT_ID) {
                    storedKeys[LlmProvider.CUSTOM]
                } else {
                    null
                }
        } else {
            storedKeys[selectedProvider]
        }

    val hasStoredKeyForSelected: Boolean
        get() = !currentStoredKey.isNullOrBlank()

    val canDeleteSelectedCustomProfile: Boolean
        get() = selectedProvider == LlmProvider.CUSTOM &&
            selectedCustomProfileId != CustomProviderProfile.DEFAULT_ID &&
            customProfiles.any { it.normalizedId == selectedCustomProfileId }

    /** 仅包含在「API Key 管理」中已保存密钥的服务商 */
    val configuredApiKeyProviders: List<LlmProvider>
        get() = LlmProvider.apiKeyProviders.filter { provider ->
            if (provider == LlmProvider.CUSTOM) {
                customProfileStoredKeys.values.any { !it.isNullOrBlank() } ||
                    !storedKeys[LlmProvider.CUSTOM].isNullOrBlank()
            } else {
                !storedKeys[provider].isNullOrBlank()
            }
        }

    /** 「模型选择」界面中所有已就绪的具体服务商目标（展开全部已配置的自定义端点档案 + 内置与 OAuth 服务商） */
    val configuredModelTargets: List<ModelProviderTarget>
        get() = buildList {
            LlmProvider.modelSelectionProviders.forEach { provider ->
                if (provider == LlmProvider.CUSTOM) {
                    customProfiles.forEach { profile ->
                        val profileKey = customProfileStoredKeys[profile.normalizedId]
                            ?: if (profile.normalizedId == CustomProviderProfile.DEFAULT_ID) {
                                storedKeys[LlmProvider.CUSTOM]
                            } else {
                                null
                            }
                        if (!profileKey.isNullOrBlank()) {
                            add(
                                ModelProviderTarget(
                                    provider = LlmProvider.CUSTOM,
                                    customProviderId = profile.normalizedId,
                                    customDisplayName = profile.normalizedName,
                                ),
                            )
                        }
                    }
                } else {
                    val isReady = providerAvailability[provider]?.isReady ?: run {
                        if (provider.usesApiKey) {
                            !storedKeys[provider].isNullOrBlank()
                        } else {
                            provider == LlmProvider.CHATGPT && chatGptAuthState.isPlanUsageReady
                        }
                    }
                    if (isReady) {
                        add(ModelProviderTarget(provider = provider))
                    }
                }
            }
        }

    /** 「模型选择」界面中所有已就绪的服务商枚举列表 */
    val configuredModelProviders: List<LlmProvider>
        get() = configuredModelTargets.map { it.provider }.distinct()

    val configuredProviders: List<LlmProvider>
        get() = configuredModelProviders

    val configuredModelCount: Int
        get() = ModelRole.entries.count { savedModelConfigs[it]?.isComplete == true }

    val selectedTinyModelOption: TinyModelOption?
        get() = TinyModelConfigRepository.resolveSelectedOption(
            selection = tinyModelSelection,
            availableOptions = tinyModelOptions,
        )

    val effectiveTinyModelConfig: TinyModelConfig
        get() = TinyModelConfigRepository.resolveEffectiveConfig(
            selection = tinyModelSelection,
            availableOptions = tinyModelOptions,
        )
}

private data class CustomProfilesSnapshot(
    val profiles: List<CustomProviderProfile>,
    val selectedProfileId: String,
    val profileNameInput: String,
    val customBaseUrlInput: String,
    val savedCustomBaseUrl: String,
    val profileKeys: Map<String, String?>,
)

private data class LlmFormState(
    val selectedProvider: LlmProvider,
    val inputText: String,
    val inputErrorRes: Int?,
    val custom: CustomProfilesSnapshot,
    val storedKeys: Map<LlmProvider, String?>,
    val providerAvailability: Map<LlmProvider, ProviderAvailability>,
    val chatGptAuthState: ChatGptAuthState,
    val isBusy: Boolean,
)

private data class ModelSectionState(
    val groups: ModelGroups,
    val isCreatingGroup: Boolean,
    val savedConfigs: Map<ModelRole, RoleModelSelection>,
    val draftConfigs: Map<ModelRole, RoleModelSelection>,
    val remoteModelsByProvider: Map<String, List<LlmModelOption>>,
    val loadingModelProviders: Set<String>,
    val modelFetchErrors: Map<String, String>,
    val isSavingModels: Boolean,
    val imageGenModelSelection: ImageGenModelSelection,
    val draftImageGenModelSelection: ImageGenModelSelection,
)

private data class SearchSectionState(
    val config: SearchSettingsConfig = SearchSettingsConfig(),
    val prootStatus: ProotSearXngStatus = ProotSearXngStatus(),
    val engineAvailability: List<SearchEngineAvailability> = emptyList(),
    val isProbing: Boolean = false,
)

private data class TinyModelSectionState(
    val options: List<TinyModelOption> = emptyList(),
    val selection: TinyModelSelection = TinyModelSelection(),
    val runtimeState: TinyModelState = TinyModelState.Uninitialized,
    val isScanning: Boolean = false,
    val isImporting: Boolean = false,
    val search: SearchSectionState = SearchSectionState(),
)

private data class PrivilegeSectionState(
    val overview: PrivilegeOverview,
    val requestingPrivilege: AndroidPrivilege?,
    val hasCheckedRoot: Boolean,
    val isCapturingScreenshot: Boolean,
    val lastScreenshotResult: ScreenCaptureResult?,
    val lastScreenshotError: String?,
)

private data class PersonaSectionState(
    val presets: List<PersonaPreset>,
    val isScanning: Boolean,
    val savedConfig: PersonaConfig,
    val draftSelectedPresetId: String?,
    val draftPersonaText: String,
    val isSaving: Boolean,
)

private data class SecurityAndAgentStepState(
    val safetyConfig: ModelSafetyConfig,
    val savedAgentStepConfig: AgentStepConfig,
    val draftChatStepInput: String,
    val draftPlannerStepInput: String,
    val draftPlannerCheckpointInput: String,
    val draftDeviceActionStepInput: String,
    val isSavingAgentSteps: Boolean,
    val rootfsArchiveStatus: RootfsArchiveStatus = RootfsArchiveStatus(),
    val tinyModel: TinyModelSectionState = TinyModelSectionState(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val allToolSets = DefaultAndroidToolSets.createAll(appContext)

    private val selectedProvider = MutableStateFlow(LlmProvider.OPENAI)
    private val inputText = MutableStateFlow("")
    private val inputErrorRes = MutableStateFlow<Int?>(null)
    private val customBaseUrlInput = MutableStateFlow("")
    private val selectedCustomProfileId = MutableStateFlow(CustomProviderProfile.DEFAULT_ID)
    private val customProfileNameInput = MutableStateFlow(ModelConfigRepository.DEFAULT_CUSTOM_PROFILE_NAME)
    private val isBusy = MutableStateFlow(false)

    private val isCreatingModelGroup = MutableStateFlow(false)
    private val draftModelConfigs = MutableStateFlow(
        ModelRole.entries.associateWith { RoleModelSelection() },
    )
    private companion object {
        val remoteModelsByProvider = MutableStateFlow<Map<String, List<LlmModelOption>>>(emptyMap())
        val loadingModelProviders = MutableStateFlow<Set<String>>(emptySet())
        val modelFetchErrors = MutableStateFlow<Map<String, String>>(emptyMap())
        val modelFetchJobs = mutableMapOf<String, Job>()
    }
    private val isSavingModels = MutableStateFlow(false)
    private val draftImageGenModelSelection = MutableStateFlow<ImageGenModelSelection?>(null)

    private val personaPresets = MutableStateFlow<List<PersonaPreset>>(emptyList())
    private val isScanningPersonas = MutableStateFlow(false)
    private val draftSelectedPresetId = MutableStateFlow<String?>(null)
    private val draftPersonaText = MutableStateFlow("")
    private val isSavingPersona = MutableStateFlow(false)

    private val requestingPrivilege = MutableStateFlow<AndroidPrivilege?>(null)
    private val hasCheckedRoot = MutableStateFlow(false)
    private val isCapturingScreenshot = MutableStateFlow(false)
    private val lastScreenshotResult = MutableStateFlow<ScreenCaptureResult?>(null)
    private val lastScreenshotError = MutableStateFlow<String?>(null)

    private val draftChatStepInput = MutableStateFlow(
        AgentStepConfig.DEFAULT_CHAT_MAX_ITERATIONS.toString(),
    )
    private val draftPlannerStepInput = MutableStateFlow(
        AgentStepConfig.DEFAULT_PLANNER_MAX_ITERATIONS_PER_CHECKPOINT.toString(),
    )
    private val draftPlannerCheckpointInput = MutableStateFlow(
        AgentStepConfig.DEFAULT_PLANNER_MAX_CHECKPOINTS.toString(),
    )
    private val draftDeviceActionStepInput = MutableStateFlow(
        AgentStepConfig.DEFAULT_DEVICE_ACTION_MAX_ITERATIONS.toString(),
    )
    private val isSavingAgentSteps = MutableStateFlow(false)
    private val rootfsArchiveStatus = MutableStateFlow(RootfsArchiveStatus())

    private val tinyModelOptions = MutableStateFlow<List<TinyModelOption>>(emptyList())
    private val isScanningTinyModels = MutableStateFlow(false)
    private val isImportingTinyModel = MutableStateFlow(false)

    private val engineAvailabilityList = MutableStateFlow<List<SearchEngineAvailability>>(emptyList())
    private val isProbingSearchEngines = MutableStateFlow(false)

    private var initialInputPopulated = false
    private var initialCustomUrlPopulated = false
    private var lastActiveGroupId: String? = null
    private var lastSavedModelConfigs: Map<ModelRole, RoleModelSelection> = emptyMap()
    private var savedModelHistory: Map<ModelRole, List<RoleModelSelection>> = emptyMap()
    private val draftModelHistory = mutableMapOf<ModelRole, List<RoleModelSelection>>()
    private var initialPersonaPopulated = false
    private var initialAgentStepsPopulated = false

    private var chatGptSignInJob: Job? = null

    private val _snackbarMessages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<Int> = _snackbarMessages.asSharedFlow()

    private val _statusMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val statusMessages: SharedFlow<String> = _statusMessages.asSharedFlow()

    private val _externalAuthUrls = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val externalAuthUrls: SharedFlow<String> = _externalAuthUrls.asSharedFlow()

    private val customProfilesWithKeysFlow = ModelConfigRepository.observeCustomProfiles()
        .flatMapLatest { profiles ->
            LlmKeyRepository.observeCustomProfileApiKeys(profiles).flatMapLatest { keys ->
                flowOf(profiles to keys)
            }
        }

    private val customProfilesSnapshotFlow = combine(
        customProfilesWithKeysFlow,
        combine(selectedCustomProfileId, customProfileNameInput, customBaseUrlInput) { id, name, url ->
            Triple(id, name, url)
        },
        ModelConfigRepository.observeCustomBaseUrl(),
    ) { (profiles, profileKeys), (selectedId, nameInput, urlInput), savedCustomUrl ->
        if (!initialCustomUrlPopulated) {
            initialCustomUrlPopulated = true
            val defaultProfile = profiles.firstOrNull { it.normalizedId == selectedId } ?: profiles.firstOrNull()
            if (defaultProfile != null) {
                if (urlInput.isEmpty() && defaultProfile.baseUrl.isNotEmpty()) {
                    customBaseUrlInput.value = defaultProfile.baseUrl
                }
                customProfileNameInput.value = defaultProfile.normalizedName
            } else if (savedCustomUrl.isNotEmpty() && urlInput.isEmpty()) {
                customBaseUrlInput.value = savedCustomUrl
            }
        }
        CustomProfilesSnapshot(
            profiles = profiles,
            selectedProfileId = selectedId,
            profileNameInput = customProfileNameInput.value,
            customBaseUrlInput = customBaseUrlInput.value,
            savedCustomBaseUrl = savedCustomUrl,
            profileKeys = profileKeys,
        )
    }

    private val llmFormFlow = combine(
        combine(selectedProvider, inputText, inputErrorRes) { provider, text, err ->
            Triple(provider, text, err)
        },
        customProfilesSnapshotFlow,
        combine(
            LlmKeyRepository.observeAllApiKeys(),
            LlmProviderRegistry.observeAllAvailability(),
        ) { keys, availability ->
            keys to availability
        },
        ChatGptAuthRepository.state,
        isBusy,
    ) { (provider, _, errorRes), customSnapshot, (keys, availability), chatGptState, busy ->
        if (!initialInputPopulated) {
            initialInputPopulated = true
            val initialKey = keys[provider].orEmpty()
            if (initialKey.isNotEmpty() && inputText.value.isEmpty()) {
                inputText.value = initialKey
            }
        }
        LlmFormState(
            selectedProvider = provider,
            inputText = inputText.value,
            inputErrorRes = errorRes,
            custom = customSnapshot,
            storedKeys = keys,
            providerAvailability = availability,
            chatGptAuthState = chatGptState,
            isBusy = busy,
        )
    }

    private val remoteModelsStateFlow = combine(
        remoteModelsByProvider,
        loadingModelProviders,
        modelFetchErrors,
    ) { models, loading, errors ->
        Triple(models, loading, errors)
    }

    private val imageGenSelectionStateFlow = combine(
        ModelConfigRepository.observeImageGenModelSelection(),
        draftImageGenModelSelection,
    ) { saved, draft -> saved to (draft ?: saved) }

    private val modelSectionFlow = combine(
        combine(
            ModelConfigRepository.observeModelGroups(),
            ModelConfigRepository.observeModelHistory(),
        ) { groups, history -> groups to history },
        combine(draftModelConfigs, isCreatingModelGroup) { draft, creating -> draft to creating },
        remoteModelsStateFlow,
        isSavingModels,
        imageGenSelectionStateFlow,
    ) { (groups, history), (draft, creating), (remoteModels, loadingProviders, errors), saving, (savedImageGen, draftImageGen) ->
        val saved = groups.groups.firstOrNull { it.id == groups.activeId }?.selections.orEmpty()
        savedModelHistory = history
        val resolvedDraft = if (groups.activeId != lastActiveGroupId ||
            (!creating && saved != lastSavedModelConfigs && draft == lastSavedModelConfigs)
        ) {
            lastActiveGroupId = groups.activeId
            draftModelConfigs.value = saved
            draftModelHistory.clear()
            saved
        } else {
            draft
        }
        lastSavedModelConfigs = saved
        ModelSectionState(
            groups = groups,
            isCreatingGroup = creating,
            savedConfigs = saved,
            draftConfigs = resolvedDraft,
            remoteModelsByProvider = remoteModels,
            loadingModelProviders = loadingProviders,
            modelFetchErrors = errors,
            isSavingModels = saving,
            imageGenModelSelection = savedImageGen,
            draftImageGenModelSelection = draftImageGen,
        )
    }

    private val screenshotStateFlow = combine(
        isCapturingScreenshot,
        lastScreenshotResult,
        lastScreenshotError,
    ) { capturing, result, error ->
        Triple(capturing, result, error)
    }

    private val privilegeSectionFlow = combine(
        PrivilegeManager.overview,
        requestingPrivilege,
        hasCheckedRoot,
        screenshotStateFlow,
    ) { overview, requesting, rootChecked, (capturing, shotResult, shotError) ->
        PrivilegeSectionState(
            overview = overview,
            requestingPrivilege = requesting,
            hasCheckedRoot = rootChecked,
            isCapturingScreenshot = capturing,
            lastScreenshotResult = shotResult,
            lastScreenshotError = shotError,
        )
    }

    private val personaSectionFlow = combine(
        personaPresets,
        isScanningPersonas,
        PersonaRepository.observePersonaConfig(),
        combine(draftSelectedPresetId, draftPersonaText, isSavingPersona) { presetId, text, saving ->
            Triple(presetId, text, saving)
        },
    ) { presets, scanning, savedConfig, (draftPresetId, draftText, saving) ->
        if (!initialPersonaPopulated) {
            initialPersonaPopulated = true
            draftSelectedPresetId.value = savedConfig.selectedPresetId
            draftPersonaText.value = savedConfig.personaText
            PersonaSectionState(
                presets = presets,
                isScanning = scanning,
                savedConfig = savedConfig,
                draftSelectedPresetId = savedConfig.selectedPresetId,
                draftPersonaText = savedConfig.personaText,
                isSaving = saving,
            )
        } else {
            PersonaSectionState(
                presets = presets,
                isScanning = scanning,
                savedConfig = savedConfig,
                draftSelectedPresetId = draftPresetId,
                draftPersonaText = draftText,
                isSaving = saving,
            )
        }
    }

    private val draftAgentStepInputsFlow = combine(
        draftChatStepInput,
        draftPlannerStepInput,
        draftPlannerCheckpointInput,
        draftDeviceActionStepInput,
        isSavingAgentSteps,
    ) { chatInput, plannerStepInput, plannerCheckpointInput, deviceInput, saving ->
        AgentStepDraftSnapshot(
            chatInput = chatInput,
            plannerStepInput = plannerStepInput,
            plannerCheckpointInput = plannerCheckpointInput,
            deviceInput = deviceInput,
            isSaving = saving,
        )
    }

    private val tinyModelRuntimeStateFlow = AppRuntime.activeTinyModelFlow
        .flatMapLatest { model ->
            model?.state ?: flowOf(TinyModelState.Uninitialized)
        }

    private val searchSectionFlow = combine(
        SearchConfigRepository.observeConfig(),
        ProotSearXngManager.status,
        engineAvailabilityList,
        isProbingSearchEngines,
    ) { config, prootStatus, availability, probing ->
        SearchSectionState(
            config = config,
            prootStatus = prootStatus,
            engineAvailability = availability,
            isProbing = probing,
        )
    }

    private val tinyModelSectionFlow = combine(
        combine(tinyModelOptions, TinyModelConfigRepository.observeSelection(), tinyModelRuntimeStateFlow) { options, selection, runtimeState ->
            Triple(options, selection, runtimeState)
        },
        combine(isScanningTinyModels, isImportingTinyModel, searchSectionFlow) { scanning, importing, searchState ->
            Triple(scanning, importing, searchState)
        },
    ) { (options, selection, runtimeState), (scanning, importing, searchState) ->
        TinyModelSectionState(
            options = options,
            selection = selection,
            runtimeState = runtimeState,
            isScanning = scanning,
            isImporting = importing,
            search = searchState,
        )
    }

    private val securityAndAgentStepFlow = combine(
        ModelSafetyRepository.observeSafetyConfig(),
        AgentStepConfigRepository.observeStepConfig(),
        draftAgentStepInputsFlow,
        rootfsArchiveStatus,
        tinyModelSectionFlow,
    ) { safety, savedStepConfig, draft, rootfsStatus, tinyModel ->
        if (!initialAgentStepsPopulated) {
            initialAgentStepsPopulated = true
            draftChatStepInput.value = savedStepConfig.chatMaxIterations.toString()
            draftPlannerStepInput.value = savedStepConfig.plannerMaxIterationsPerCheckpoint.toString()
            draftPlannerCheckpointInput.value = savedStepConfig.plannerMaxCheckpoints.toString()
            draftDeviceActionStepInput.value = savedStepConfig.deviceActionMaxIterations.toString()
            SecurityAndAgentStepState(
                safetyConfig = safety,
                savedAgentStepConfig = savedStepConfig,
                draftChatStepInput = savedStepConfig.chatMaxIterations.toString(),
                draftPlannerStepInput = savedStepConfig.plannerMaxIterationsPerCheckpoint.toString(),
                draftPlannerCheckpointInput = savedStepConfig.plannerMaxCheckpoints.toString(),
                draftDeviceActionStepInput = savedStepConfig.deviceActionMaxIterations.toString(),
                isSavingAgentSteps = draft.isSaving,
                rootfsArchiveStatus = rootfsStatus,
                tinyModel = tinyModel,
            )
        } else {
            SecurityAndAgentStepState(
                safetyConfig = safety,
                savedAgentStepConfig = savedStepConfig,
                draftChatStepInput = draft.chatInput,
                draftPlannerStepInput = draft.plannerStepInput,
                draftPlannerCheckpointInput = draft.plannerCheckpointInput,
                draftDeviceActionStepInput = draft.deviceInput,
                isSavingAgentSteps = draft.isSaving,
                rootfsArchiveStatus = rootfsStatus,
                tinyModel = tinyModel,
            )
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        llmFormFlow,
        modelSectionFlow,
        personaSectionFlow,
        privilegeSectionFlow,
        securityAndAgentStepFlow,
    ) { llm, models, persona, priv, secAndStep ->
        val unlockedCount = allToolSets.count { it.isAllowed(priv.overview.availablePrivileges) }
        SettingsUiState(
            selectedProvider = llm.selectedProvider,
            inputText = llm.inputText,
            inputErrorRes = llm.inputErrorRes,
            customBaseUrlInput = llm.custom.customBaseUrlInput,
            savedCustomBaseUrl = llm.custom.savedCustomBaseUrl,
            customProfiles = llm.custom.profiles,
            selectedCustomProfileId = llm.custom.selectedProfileId,
            customProfileNameInput = llm.custom.profileNameInput,
            customProfileStoredKeys = llm.custom.profileKeys,
            storedKeys = llm.storedKeys,
            providerAvailability = llm.providerAvailability,
            chatGptAuthState = llm.chatGptAuthState,
            isBusy = llm.isBusy,
            isLoaded = true,
            modelGroups = models.groups,
            isCreatingModelGroup = models.isCreatingGroup,
            savedModelConfigs = models.savedConfigs,
            draftModelConfigs = models.draftConfigs,
            remoteModelsByProvider = models.remoteModelsByProvider,
            loadingModelProviders = models.loadingModelProviders,
            modelFetchErrors = models.modelFetchErrors,
            isSavingModels = models.isSavingModels,
            imageGenModelSelection = models.imageGenModelSelection,
            draftImageGenModelSelection = models.draftImageGenModelSelection,
            personaPresets = persona.presets,
            isScanningPersonas = persona.isScanning,
            savedPersonaConfig = persona.savedConfig,
            draftSelectedPresetId = persona.draftSelectedPresetId,
            draftPersonaText = persona.draftPersonaText,
            isSavingPersona = persona.isSaving,
            privilegeOverview = priv.overview,
            unlockedToolSetCount = unlockedCount,
            totalToolSetCount = allToolSets.size,
            requestingPrivilege = priv.requestingPrivilege,
            hasCheckedRoot = priv.hasCheckedRoot,
            isCapturingScreenshot = priv.isCapturingScreenshot,
            lastScreenshotResult = priv.lastScreenshotResult,
            lastScreenshotError = priv.lastScreenshotError,
            safetyConfig = secAndStep.safetyConfig,
            savedAgentStepConfig = secAndStep.savedAgentStepConfig,
            draftChatStepInput = secAndStep.draftChatStepInput,
            draftPlannerStepInput = secAndStep.draftPlannerStepInput,
            draftPlannerCheckpointInput = secAndStep.draftPlannerCheckpointInput,
            draftDeviceActionStepInput = secAndStep.draftDeviceActionStepInput,
            isSavingAgentSteps = secAndStep.isSavingAgentSteps,
            rootfsArchiveStatus = secAndStep.rootfsArchiveStatus,
            tinyModelOptions = secAndStep.tinyModel.options,
            tinyModelSelection = secAndStep.tinyModel.selection,
            tinyModelRuntimeState = secAndStep.tinyModel.runtimeState,
            isScanningTinyModels = secAndStep.tinyModel.isScanning,
            isImportingTinyModel = secAndStep.tinyModel.isImporting,
            searchSettingsConfig = secAndStep.tinyModel.search.config,
            prootSearxngStatus = secAndStep.tinyModel.search.prootStatus,
            engineAvailabilityList = secAndStep.tinyModel.search.engineAvailability,
            isProbingSearchEngines = secAndStep.tinyModel.search.isProbing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(totalToolSetCount = allToolSets.size),
    )

    init {
        refreshPrivileges(checkRoot = false)
        refreshPersonaPresets()
        refreshRootfsArchiveStatus()
        refreshTinyModels()
    }

    fun onInputChanged(value: String) {
        inputText.value = value
        if (inputErrorRes.value != null) {
            inputErrorRes.value = null
        }
    }

    fun onCustomBaseUrlChanged(value: String) {
        customBaseUrlInput.value = value
    }

    fun onCustomProfileNameChanged(value: String) {
        customProfileNameInput.value = value
    }

    fun selectProvider(provider: LlmProvider) {
        if (!provider.usesApiKey) return
        if (selectedProvider.value == provider) return
        selectedProvider.value = provider
        inputErrorRes.value = null
        viewModelScope.launch {
            if (provider == LlmProvider.CUSTOM) {
                val profiles = ModelConfigRepository.getCustomProfiles()
                val activeId = selectedCustomProfileId.value
                val matched = profiles.firstOrNull { it.normalizedId == activeId } ?: profiles.first()
                selectedCustomProfileId.value = matched.normalizedId
                customProfileNameInput.value = matched.normalizedName
                customBaseUrlInput.value = matched.normalizedBaseUrl
                inputText.value = LlmKeyRepository.getCustomProfileApiKey(matched.normalizedId).orEmpty()
            } else {
                inputText.value = LlmKeyRepository.getApiKey(provider).orEmpty()
            }
        }
    }

    fun selectCustomProfile(profileId: String) {
        val targetId = profileId.trim()
        if (targetId.isEmpty()) return
        selectedProvider.value = LlmProvider.CUSTOM
        selectedCustomProfileId.value = targetId
        inputErrorRes.value = null
        viewModelScope.launch {
            val profiles = ModelConfigRepository.getCustomProfiles()
            val matched = profiles.firstOrNull { it.normalizedId == targetId }
            customProfileNameInput.value = matched?.normalizedName
                ?: if (targetId == CustomProviderProfile.DEFAULT_ID) {
                    ModelConfigRepository.DEFAULT_CUSTOM_PROFILE_NAME
                } else {
                    targetId
                }
            customBaseUrlInput.value = matched?.normalizedBaseUrl.orEmpty()
            inputText.value = LlmKeyRepository.getCustomProfileApiKey(targetId).orEmpty()
        }
    }

    fun startAddCustomProfile() {
        val newId = "p_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val nextIndex = uiState.value.customProfiles.size + 1
        selectedProvider.value = LlmProvider.CUSTOM
        selectedCustomProfileId.value = newId
        customProfileNameInput.value = "自定义端点 $nextIndex"
        customBaseUrlInput.value = ""
        inputText.value = ""
        inputErrorRes.value = null
    }

    fun deleteSelectedCustomProfile() {
        val targetId = selectedCustomProfileId.value
        if (selectedProvider.value != LlmProvider.CUSTOM || targetId == CustomProviderProfile.DEFAULT_ID) {
            return
        }
        viewModelScope.launch {
            isBusy.value = true
            try {
                LlmKeyRepository.deleteCustomProfileApiKey(targetId)
                ModelConfigRepository.deleteCustomProfile(targetId)
                invalidateModelList(ModelProviderTarget.providerCacheKey(LlmProvider.CUSTOM, targetId))
                AppRuntime.invalidateExecutorsForProvider(LlmProvider.CUSTOM, targetId)
                AppRuntime.syncModelExecutors()
                selectCustomProfile(CustomProviderProfile.DEFAULT_ID)
                _snackbarMessages.emit(R.string.settings_llm_custom_profile_deleted)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_llm_key_clear_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    fun saveApiKey() {
        val trimmed = inputText.value.trim()
        if (trimmed.isEmpty()) {
            inputErrorRes.value = R.string.settings_llm_key_error_empty
            return
        }
        inputErrorRes.value = null

        val targetProvider = selectedProvider.value
        val customUrl = customBaseUrlInput.value.trim()
        val customProfileId = selectedCustomProfileId.value
        val customProfileName = customProfileNameInput.value.trim()
        viewModelScope.launch {
            isBusy.value = true
            try {
                if (targetProvider == LlmProvider.CUSTOM) {
                    val profile = CustomProviderProfile(
                        id = customProfileId,
                        name = customProfileName.ifEmpty {
                            if (customProfileId == CustomProviderProfile.DEFAULT_ID) {
                                ModelConfigRepository.DEFAULT_CUSTOM_PROFILE_NAME
                            } else {
                                customProfileId
                            }
                        },
                        baseUrl = customUrl,
                    )
                    ModelConfigRepository.upsertCustomProfile(profile)
                    LlmKeyRepository.saveCustomProfileApiKey(customProfileId, trimmed)
                    customProfileNameInput.value = profile.normalizedName
                    customBaseUrlInput.value = customUrl
                    invalidateModelList(ModelProviderTarget.providerCacheKey(LlmProvider.CUSTOM, customProfileId))
                    AppRuntime.invalidateExecutorsForProvider(LlmProvider.CUSTOM, customProfileId)
                } else {
                    LlmKeyRepository.saveApiKey(targetProvider, trimmed)
                    invalidateModelList(ModelProviderTarget.providerCacheKey(targetProvider))
                    AppRuntime.invalidateExecutorsForProvider(targetProvider)
                }
                AppRuntime.syncModelExecutors()
                inputText.value = trimmed
                _snackbarMessages.emit(R.string.settings_llm_key_saved)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_llm_key_save_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    fun clearApiKey() {
        val targetProvider = selectedProvider.value
        val customProfileId = selectedCustomProfileId.value
        viewModelScope.launch {
            isBusy.value = true
            try {
                if (targetProvider == LlmProvider.CUSTOM) {
                    LlmKeyRepository.deleteCustomProfileApiKey(customProfileId)
                    if (customProfileId == CustomProviderProfile.DEFAULT_ID) {
                        ModelConfigRepository.saveCustomBaseUrl("")
                        customBaseUrlInput.value = ""
                    } else {
                        ModelConfigRepository.deleteCustomProfile(customProfileId)
                        selectCustomProfile(CustomProviderProfile.DEFAULT_ID)
                    }
                    invalidateModelList(ModelProviderTarget.providerCacheKey(LlmProvider.CUSTOM, customProfileId))
                    AppRuntime.invalidateExecutorsForProvider(LlmProvider.CUSTOM, customProfileId)
                } else {
                    LlmKeyRepository.deleteApiKey(targetProvider)
                    invalidateModelList(ModelProviderTarget.providerCacheKey(targetProvider))
                    AppRuntime.invalidateExecutorsForProvider(targetProvider)
                }
                AppRuntime.syncModelExecutors()
                inputErrorRes.value = null
                inputText.value = ""
                _snackbarMessages.emit(R.string.settings_llm_key_cleared)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_llm_key_clear_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    fun onSelectImageGenProvider(target: ModelProviderTarget?) {
        if (target != null && uiState.value.configuredModelTargets.none {
                it.provider == target.provider && it.customProviderId == target.customProviderId
            }
        ) return
        val current = uiState.value.draftImageGenModelSelection
        draftImageGenModelSelection.value = current.copy(
            provider = target?.provider,
            customProviderId = target?.customProviderId,
        )
    }

    fun onImageGenModelIdChanged(modelId: String) {
        draftImageGenModelSelection.value = uiState.value.draftImageGenModelSelection.copy(modelId = modelId)
    }

    fun onSelectModelProvider(role: ModelRole, target: ModelProviderTarget?) {
        if (target != null && uiState.value.configuredModelTargets.none {
                it.provider == target.provider && it.customProviderId == target.customProviderId
            }
        ) {
            return
        }
        val current = draftModelConfigs.value[role] ?: RoleModelSelection()
        if (target != null &&
            current.provider == target.provider &&
            current.normalizedCustomProviderId == target.customProviderId
        ) return
        rememberDraftModel(role, current)
        val updatedSelection = RoleModelHistory.selectProvider(current, modelHistoryFor(role), target)
        draftModelConfigs.value += (role to updatedSelection)
    }

    fun onSelectModelId(role: ModelRole, modelId: String) {
        val current = draftModelConfigs.value[role] ?: return
        // 未选择服务商前禁止设置模型
        if (current.provider == null) return
        val cacheKey = current.providerCacheKey
        val matchedOption = cacheKey?.let { key ->
            remoteModelsByProvider.value[key]?.firstOrNull { it.modelId == modelId }
        }
        rememberDraftModel(role, current)
        val option = matchedOption ?: return
        draftModelConfigs.value += (role to RoleModelHistory.selectModel(current, modelHistoryFor(role), option))
    }

    fun onSelectReasoningEffort(role: ModelRole, effort: ModelReasoningEffort) {
        val current = draftModelConfigs.value[role] ?: return
        if (effort !in current.availableReasoningEfforts()) return
        draftModelConfigs.value += (role to current.copy(reasoningEffort = effort))
    }

    private fun modelHistoryFor(role: ModelRole): List<RoleModelSelection> =
        (draftModelHistory[role].orEmpty() +
            if (isCreatingModelGroup.value) emptyList() else savedModelHistory[role].orEmpty())
            .distinctBy { it.providerCacheKey to it.normalizedModelId }

    private fun rememberDraftModel(role: ModelRole, selection: RoleModelSelection) {
        draftModelHistory[role] = RoleModelHistory.remember(modelHistoryFor(role), selection)
    }

    fun resetDraftModelConfigsToSaved() {
        isCreatingModelGroup.value = false
        draftModelHistory.clear()
        draftModelConfigs.value = uiState.value.savedModelConfigs
    }

    fun fetchModelsForProvider(
        provider: LlmProvider,
        customProviderId: String?,
        forceRefresh: Boolean,
    ) {
        val cacheKey = ModelProviderTarget.providerCacheKey(provider, customProviderId)
        if (cacheKey in modelFetchJobs) return
        if (!forceRefresh &&
            (cacheKey in remoteModelsByProvider.value || cacheKey in modelFetchErrors.value)
        ) return

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val request = coroutineContext.job
            try {
                val result = LlmProviderRegistry.fetchModels(provider, customProviderId)
                if (modelFetchJobs[cacheKey] !== request) return@launch
                result.onSuccess { models ->
                    remoteModelsByProvider.value += (cacheKey to models)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    modelFetchErrors.value += (cacheKey to (error.message ?: "获取模型列表失败"))
                }
            } finally {
                if (modelFetchJobs[cacheKey] === request) {
                    modelFetchJobs.remove(cacheKey)
                    loadingModelProviders.value -= cacheKey
                }
            }
        }
        modelFetchJobs[cacheKey] = job
        loadingModelProviders.value += cacheKey
        modelFetchErrors.value -= cacheKey
        job.start()
    }

    private fun invalidateModelList(cacheKey: String) {
        modelFetchJobs.remove(cacheKey)?.cancel()
        loadingModelProviders.value -= cacheKey
        remoteModelsByProvider.value -= cacheKey
        modelFetchErrors.value -= cacheKey
    }

    private fun invalidateAllModelLists() {
        val jobs = modelFetchJobs.values.toList()
        modelFetchJobs.clear()
        jobs.forEach { it.cancel() }
        loadingModelProviders.value = emptySet()
        remoteModelsByProvider.value = emptyMap()
        modelFetchErrors.value = emptyMap()
    }

    fun startChatGptSignIn(forceConsent: Boolean = false) {
        chatGptSignInJob?.cancel()
        ChatGptAuthRepository.cancelActiveSignIn()
        chatGptSignInJob = viewModelScope.launch {
            try {
                val pending = ChatGptAuthRepository.prepareSignIn(forceConsent = forceConsent)
                _externalAuthUrls.emit(pending.authorizeUrl)
                val resultState = ChatGptAuthRepository.awaitAndCompleteSignIn(pending)
                invalidateModelList(ModelProviderTarget.providerCacheKey(LlmProvider.CHATGPT))
                AppRuntime.syncModelExecutors()
                if (resultState.isPlanUsageReady) {
                    _snackbarMessages.emit(R.string.settings_chatgpt_sign_in_success)
                } else {
                    _snackbarMessages.emit(R.string.settings_chatgpt_sign_in_no_scope_toast)
                }
            } catch (_: CancellationException) {
                // 用户取消或重新发起登录
            } catch (e: Exception) {
                _statusMessages.emit(e.message ?: "Sign in with ChatGPT 登录失败")
            }
        }
    }

    fun onChatGptBrowserLaunchFailed() {
        chatGptSignInJob?.cancel()
        chatGptSignInJob = null
        ChatGptAuthRepository.cancelActiveSignIn()
        _snackbarMessages.tryEmit(R.string.settings_chatgpt_browser_open_failed)
    }

    fun cancelChatGptSignIn() {
        chatGptSignInJob?.cancel()
        chatGptSignInJob = null
        ChatGptAuthRepository.cancelActiveSignIn()
        _snackbarMessages.tryEmit(R.string.settings_chatgpt_sign_in_cancelled)
    }

    fun refreshChatGptToken() {
        viewModelScope.launch {
            try {
                ChatGptAuthRepository.getValidAccessToken(forceRefresh = true)
                AppRuntime.syncModelExecutors()
                _snackbarMessages.emit(R.string.settings_chatgpt_refresh_success)
            } catch (e: Exception) {
                _statusMessages.emit(e.message ?: "刷新 ChatGPT 访问令牌失败")
            }
        }
    }

    fun signOutChatGpt() {
        chatGptSignInJob?.cancel()
        chatGptSignInJob = null
        viewModelScope.launch {
            try {
                ChatGptAuthRepository.signOut()
                invalidateModelList(ModelProviderTarget.providerCacheKey(LlmProvider.CHATGPT))
                AppRuntime.invalidateExecutorsForProvider(LlmProvider.CHATGPT)
                AppRuntime.syncModelExecutors()
                _snackbarMessages.emit(R.string.settings_chatgpt_sign_out_success)
            } catch (e: Exception) {
                _statusMessages.emit(e.message ?: "退出 ChatGPT 登录失败")
            }
        }
    }

    override fun onCleared() {
        chatGptSignInJob?.cancel()
        ChatGptAuthRepository.cancelActiveSignIn()
    }

    fun beginNewModelGroup() {
        if (isSavingModels.value || lastActiveGroupId == null || isCreatingModelGroup.value) return
        isCreatingModelGroup.value = true
        draftModelHistory.clear()
        draftModelConfigs.value = ModelRole.entries.associateWith { RoleModelSelection() }
    }

    fun saveModelConfigs(newGroupName: String? = null) {
        if (isSavingModels.value || isCreatingModelGroup.value != (newGroupName != null)) return
        if (newGroupName != null && newGroupName.isBlank()) return
        val groupId = lastActiveGroupId ?: return
        val draft = draftModelConfigs.value
        val imageGenDraft = uiState.value.draftImageGenModelSelection
        if (imageGenDraft.provider != null && !imageGenDraft.isConfigured) {
            _statusMessages.tryEmit(appContext.getString(R.string.settings_image_gen_model_id_required))
            return
        }
        val incompleteRole = ModelRole.entries.firstOrNull { role ->
            draft[role]?.hasPartialInput == true
        }
        if (incompleteRole != null) {
            val roleName = appContext.getString(incompleteRole.titleRes)
            _statusMessages.tryEmit(
                appContext.getString(R.string.settings_model_error_incomplete, roleName),
            )
            return
        }

        viewModelScope.launch {
            isSavingModels.value = true
            try {
                ModelConfigRepository.saveAllConfigs(
                    draft, ModelRole.entries.associateWith { modelHistoryFor(it) }, newGroupName, groupId,
                )
                ModelConfigRepository.saveImageGenModelSelection(uiState.value.draftImageGenModelSelection)
                draftImageGenModelSelection.value = null
                isCreatingModelGroup.value = false
                AppRuntime.syncModelExecutors()
                _snackbarMessages.emit(R.string.settings_model_saved)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_model_save_failed)
            } finally {
                isSavingModels.value = false
            }
        }
    }

    fun activateModelGroup(id: String) {
        if (isSavingModels.value) return
        if (id == lastActiveGroupId) {
            if (isCreatingModelGroup.value) resetDraftModelConfigsToSaved()
            return
        }
        viewModelScope.launch {
            isSavingModels.value = true
            try {
                ModelConfigRepository.activateModelGroup(id)
                isCreatingModelGroup.value = false
                AppRuntime.syncModelExecutors()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_model_save_failed)
            } finally {
                isSavingModels.value = false
            }
        }
    }

    fun clearAllModelConfigs() {
        viewModelScope.launch {
            isSavingModels.value = true
            try {
                ModelConfigRepository.clearAll()
                isCreatingModelGroup.value = false
                draftModelHistory.clear()
                draftModelConfigs.value = ModelRole.entries.associateWith { RoleModelSelection() }
                AppRuntime.syncModelExecutors()
                _snackbarMessages.emit(R.string.settings_model_cleared)
            } finally {
                isSavingModels.value = false
            }
        }
    }

    fun refreshPersonaPresets() {
        if (isScanningPersonas.value) return
        viewModelScope.launch {
            isScanningPersonas.value = true
            try {
                personaPresets.value = PersonaRepository.scanBuiltInPersonas()
            } finally {
                isScanningPersonas.value = false
            }
        }
    }

    fun onSelectPersonaPreset(preset: PersonaPreset) {
        draftSelectedPresetId.value = preset.id
        draftPersonaText.value = preset.content
    }

    fun onPersonaTextChanged(value: String) {
        draftPersonaText.value = value
        val selectedId = draftSelectedPresetId.value
        if (selectedId != null) {
            val matchedPreset = personaPresets.value.firstOrNull { it.id == selectedId }
            if (matchedPreset == null || matchedPreset.content.trim() != value.trim()) {
                draftSelectedPresetId.value = null
            }
        }
    }

    fun resetDraftPersonaToSaved() {
        val saved = uiState.value.savedPersonaConfig
        draftSelectedPresetId.value = saved.selectedPresetId
        draftPersonaText.value = saved.personaText
    }

    fun savePersona() {
        val textToSave = draftPersonaText.value.trim()
        val matchedPresetId = draftSelectedPresetId.value?.takeIf { presetId ->
            personaPresets.value.any { it.id == presetId && it.content.trim() == textToSave }
        }
        viewModelScope.launch {
            isSavingPersona.value = true
            try {
                PersonaRepository.savePersona(
                    personaText = textToSave,
                    selectedPresetId = matchedPresetId,
                )
                draftPersonaText.value = textToSave
                draftSelectedPresetId.value = matchedPresetId
                _snackbarMessages.emit(R.string.settings_persona_saved)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_persona_save_failed)
            } finally {
                isSavingPersona.value = false
            }
        }
    }

    fun addCustomPersona(name: String, content: String) {
        val trimmedName = name.trim()
        val trimmedContent = content.trim()
        if (trimmedName.isEmpty() || trimmedContent.isEmpty() || isSavingPersona.value) {
            return
        }
        viewModelScope.launch {
            isSavingPersona.value = true
            try {
                val created = PersonaRepository.addCustomPersona(
                    name = trimmedName,
                    content = trimmedContent,
                )
                personaPresets.value = PersonaRepository.scanBuiltInPersonas()
                draftSelectedPresetId.value = created.id
                draftPersonaText.value = created.content
                PersonaRepository.savePersona(
                    personaText = created.content,
                    selectedPresetId = created.id,
                )
                _statusMessages.emit(
                    appContext.getString(R.string.settings_persona_added, created.name),
                )
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_persona_add_failed)
            } finally {
                isSavingPersona.value = false
            }
        }
    }

    fun deleteCustomPersona(preset: PersonaPreset) {
        if (!preset.isCustom || isSavingPersona.value) return
        viewModelScope.launch {
            isSavingPersona.value = true
            try {
                val deleted = PersonaRepository.deleteCustomPersona(preset.id)
                if (deleted) {
                    personaPresets.value = PersonaRepository.scanBuiltInPersonas()
                    if (draftSelectedPresetId.value == preset.id) {
                        draftSelectedPresetId.value = null
                    }
                    if (uiState.value.savedPersonaConfig.selectedPresetId == preset.id) {
                        PersonaRepository.savePersona(
                            personaText = uiState.value.savedPersonaConfig.personaText,
                            selectedPresetId = null,
                        )
                    }
                    _snackbarMessages.emit(R.string.settings_persona_deleted)
                }
            } finally {
                isSavingPersona.value = false
            }
        }
    }

    fun clearPersona() {
        viewModelScope.launch {
            isSavingPersona.value = true
            try {
                PersonaRepository.clearPersona()
                draftSelectedPresetId.value = null
                draftPersonaText.value = ""
                _snackbarMessages.emit(R.string.settings_persona_cleared)
            } finally {
                isSavingPersona.value = false
            }
        }
    }

    fun refreshPrivileges(checkRoot: Boolean = false) {
        viewModelScope.launch {
            if (checkRoot) {
                hasCheckedRoot.value = true
            }
            PrivilegeManager.refreshOverview(checkRoot = checkRoot)
        }
    }

    fun requestPrivilege(privilege: AndroidPrivilege) {
        if (requestingPrivilege.value != null) return
        viewModelScope.launch {
            requestingPrivilege.value = privilege
            try {
                if (privilege == AndroidPrivilege.ROOT) {
                    hasCheckedRoot.value = true
                }
                val result = PrivilegeManager.requestPrivilege(privilege)
                _statusMessages.emit(result.message)
            } finally {
                requestingPrivilege.value = null
            }
        }
    }

    fun captureTestScreenshot() {
        if (isCapturingScreenshot.value) return
        viewModelScope.launch {
            isCapturingScreenshot.value = true
            lastScreenshotError.value = null
            try {
                PrivilegeManager.captureScreenshot(
                    format = "jpeg",
                    maxDimension = 1280,
                    quality = 85,
                ).onSuccess { result ->
                    lastScreenshotResult.value = result
                    _statusMessages.emit("截图成功（${result.backend}，${result.width}x${result.height}）")
                }.onFailure { error ->
                    val msg = error.message ?: "截图失败"
                    lastScreenshotError.value = msg
                    _statusMessages.emit(msg)
                }
            } finally {
                isCapturingScreenshot.value = false
            }
        }
    }

    fun applySafetyLevel(level: ModelSafetyLevel) {
        viewModelScope.launch {
            ModelSafetyRepository.applySafetyLevel(level)
        }
    }

    fun updateSafetyConfig(transform: (ModelSafetyConfig) -> ModelSafetyConfig) {
        val updated = transform(uiState.value.safetyConfig)
        viewModelScope.launch {
            ModelSafetyRepository.saveSafetyConfig(updated)
        }
    }

    fun resetSafetyConfig() {
        viewModelScope.launch {
            ModelSafetyRepository.resetToDefault()
            _snackbarMessages.emit(R.string.settings_model_safety_reset_done)
        }
    }

    fun onChatStepInputChanged(value: String) {
        draftChatStepInput.value = sanitizeNumericInput(value)
    }

    fun onPlannerStepInputChanged(value: String) {
        draftPlannerStepInput.value = sanitizeNumericInput(value)
    }

    fun onPlannerCheckpointInputChanged(value: String) {
        draftPlannerCheckpointInput.value = sanitizeNumericInput(value)
    }

    fun onDeviceActionStepInputChanged(value: String) {
        draftDeviceActionStepInput.value = sanitizeNumericInput(value)
    }

    fun resetDraftAgentStepsToSaved() {
        val saved = uiState.value.savedAgentStepConfig
        draftChatStepInput.value = saved.chatMaxIterations.toString()
        draftPlannerStepInput.value = saved.plannerMaxIterationsPerCheckpoint.toString()
        draftPlannerCheckpointInput.value = saved.plannerMaxCheckpoints.toString()
        draftDeviceActionStepInput.value = saved.deviceActionMaxIterations.toString()
    }

    internal val voiceCleanupEnabled = TinyModelConfigRepository.observeVoiceCleanupEnabled()

    internal fun setVoiceCleanupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            TinyModelConfigRepository.setVoiceCleanupEnabled(enabled)
        }
    }

    fun setCrossChatMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            AgentStepConfigRepository.setCrossChatMemoryEnabled(enabled)
        }
    }

    fun setModelOutputDisplayMode(mode: ModelOutputDisplayMode) {
        viewModelScope.launch {
            AgentStepConfigRepository.setModelOutputDisplayMode(mode)
        }
    }

    fun saveAgentStepConfigs() {
        if (isSavingAgentSteps.value) return
        val chatVal = parseValidatedStep(
            raw = draftChatStepInput.value,
            min = AgentStepConfig.MIN_AGENT_ITERATIONS,
            label = appContext.getString(R.string.settings_agent_steps_chat_title),
        ) ?: return
        val plannerStepVal = parseValidatedStep(
            raw = draftPlannerStepInput.value,
            min = AgentStepConfig.MIN_AGENT_ITERATIONS,
            label = appContext.getString(R.string.settings_agent_steps_planner_step_label),
        ) ?: return
        val plannerCheckpointVal = parseValidatedStep(
            raw = draftPlannerCheckpointInput.value,
            min = AgentStepConfig.MIN_CHECKPOINTS,
            label = appContext.getString(R.string.settings_agent_steps_planner_checkpoint_label),
        ) ?: return
        val deviceVal = parseValidatedStep(
            raw = draftDeviceActionStepInput.value,
            min = AgentStepConfig.MIN_AGENT_ITERATIONS,
            label = appContext.getString(R.string.settings_agent_steps_device_title),
        ) ?: return

        viewModelScope.launch {
            isSavingAgentSteps.value = true
            try {
                val currentSaved = uiState.value.savedAgentStepConfig
                val saved = AgentStepConfigRepository.saveStepConfig(
                    AgentStepConfig(
                        chatMaxIterations = chatVal,
                        plannerMaxIterationsPerCheckpoint = plannerStepVal,
                        plannerMaxCheckpoints = plannerCheckpointVal,
                        deviceActionMaxIterations = deviceVal,
                        enableCrossChatMemory = currentSaved.enableCrossChatMemory,
                        modelOutputDisplayMode = currentSaved.modelOutputDisplayMode,
                    ),
                )
                draftChatStepInput.value = saved.chatMaxIterations.toString()
                draftPlannerStepInput.value = saved.plannerMaxIterationsPerCheckpoint.toString()
                draftPlannerCheckpointInput.value = saved.plannerMaxCheckpoints.toString()
                draftDeviceActionStepInput.value = saved.deviceActionMaxIterations.toString()
                _snackbarMessages.emit(R.string.settings_agent_steps_saved)
            } finally {
                isSavingAgentSteps.value = false
            }
        }
    }

    fun resetAgentStepConfigs() {
        if (isSavingAgentSteps.value) return
        viewModelScope.launch {
            isSavingAgentSteps.value = true
            try {
                val defaults = AgentStepConfig.DEFAULT
                val currentSaved = uiState.value.savedAgentStepConfig
                AgentStepConfigRepository.saveStepConfig(
                    defaults.copy(
                        enableCrossChatMemory = currentSaved.enableCrossChatMemory,
                        modelOutputDisplayMode = currentSaved.modelOutputDisplayMode,
                    ),
                )
                draftChatStepInput.value = defaults.chatMaxIterations.toString()
                draftPlannerStepInput.value = defaults.plannerMaxIterationsPerCheckpoint.toString()
                draftPlannerCheckpointInput.value = defaults.plannerMaxCheckpoints.toString()
                draftDeviceActionStepInput.value = defaults.deviceActionMaxIterations.toString()
                _snackbarMessages.emit(R.string.settings_agent_steps_reset_done)
            } finally {
                isSavingAgentSteps.value = false
            }
        }
    }

    /**
     * 单独清除聊天记录与内部附件文件（包括所有会话历史、会话持久化文件、会话内部附件目录及跨会话长期记忆）。
     */
    fun clearChatRecordsData() {
        viewModelScope.launch {
            isBusy.value = true
            try {
                val defaultTitle = appContext.getString(R.string.chat_default_session_title)
                if (ChatSessionRepository.isReady) {
                    ChatSessionRepository.clearAllSessions()
                    ChatSessionRepository.flush()
                }
                ChatBackgroundRuntime.current.clearAllSessions(defaultTitlePrefix = defaultTitle)
                if (MaidMemoryManager.isInitialized) {
                    MaidMemoryManager.clearAllMemory()
                }
                _snackbarMessages.emit(R.string.settings_clear_data_chat_done)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_clear_data_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    /**
     * 单独清除所有已保存的 API Key 与自定义端点档案。
     */
    fun clearAllApiKeysData() {
        viewModelScope.launch {
            isBusy.value = true
            try {
                LlmKeyRepository.clearAllApiKeys()
                ModelConfigRepository.clearAllCustomProfiles()
                selectedCustomProfileId.value = CustomProviderProfile.DEFAULT_ID
                customProfileNameInput.value = ModelConfigRepository.DEFAULT_CUSTOM_PROFILE_NAME
                customBaseUrlInput.value = ""
                invalidateAllModelLists()
                inputErrorRes.value = null
                inputText.value = ""
                AppRuntime.clearAllCachedExecutors()
                AppRuntime.syncModelExecutors()
                _snackbarMessages.emit(R.string.settings_clear_data_api_keys_done)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_clear_data_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    /**
     * 单独清除 Sign in with ChatGPT 登录会话与本地令牌（保留设备标识与已注册 client_id）。
     */
    fun clearChatGptSessionData() {
        chatGptSignInJob?.cancel()
        chatGptSignInJob = null
        viewModelScope.launch {
            isBusy.value = true
            try {
                ChatGptAuthRepository.signOut()
                invalidateModelList(ModelProviderTarget.providerCacheKey(LlmProvider.CHATGPT))
                AppRuntime.invalidateExecutorsForProvider(LlmProvider.CHATGPT)
                AppRuntime.syncModelExecutors()
                _snackbarMessages.emit(R.string.settings_clear_data_chatgpt_done)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_clear_data_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    fun refreshRootfsArchiveStatus() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                TermuxProotRuntime.getRootfsArchiveStatus(appContext)
            }.onSuccess { status ->
                rootfsArchiveStatus.value = status
            }
        }
    }

    /**
     * 从用户在设置中选择的本地 `.tar.gz` / `.tgz` / `.tar` 归档文件导入为自定义 rootfs 镜像并立即重置解压到 `<filesDir>/rootfs`。
     */
    fun importCustomRootfsArchive(archiveUri: String) {
        if (archiveUri.isBlank()) return
        viewModelScope.launch {
            isBusy.value = true
            try {
                val status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    TermuxProotRuntime.importCustomRootfsArchive(
                        context = appContext,
                        archiveUri = archiveUri,
                        applyImmediately = true,
                    )
                }
                rootfsArchiveStatus.value = status
                _snackbarMessages.emit(R.string.settings_clear_data_rootfs_import_done)
            } catch (e: Exception) {
                _statusMessages.tryEmit(e.message ?: appContext.getString(R.string.settings_clear_data_failed))
            } finally {
                isBusy.value = false
            }
        }
    }

    /**
     * 移除已导入的自定义 rootfs 归档文件，恢复使用 APK 内置 `assets/rootfs.tar.gz` 作为初始化源并立即重置 `files/rootfs`。
     */
    fun clearCustomRootfsArchive() {
        viewModelScope.launch {
            isBusy.value = true
            try {
                val status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    TermuxProotRuntime.clearCustomRootfsArchive(
                        context = appContext,
                        resetImmediately = true,
                    )
                }
                rootfsArchiveStatus.value = status
                _snackbarMessages.emit(R.string.settings_clear_data_rootfs_clear_custom_done)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_clear_data_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    /**
     * 单独重置全局单一 PRoot rootfs (`<filesDir>/rootfs`) 并重新从当前配置的初始化归档解压。
     */
    fun resetRootfsData() {
        viewModelScope.launch {
            isBusy.value = true
            try {
                val status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    TermuxProotRuntime.resetRootfs(appContext)
                    TermuxProotRuntime.getRootfsArchiveStatus(appContext)
                }
                rootfsArchiveStatus.value = status
                _snackbarMessages.emit(R.string.settings_clear_data_rootfs_done)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_clear_data_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    /**
     * 按选中的类别（[clearChatHistory] 聊天记录 / [clearApiKeys] API Key / [clearChatGptSession] ChatGPT 登录会话 / [resetRootfs] 重置 rootfs）执行数据清除或重置。
     */
    fun clearSelectedData(
        clearChatHistory: Boolean,
        clearApiKeys: Boolean,
        clearChatGptSession: Boolean = false,
        resetRootfs: Boolean = false,
    ) {
        if (!clearChatHistory && !clearApiKeys && !clearChatGptSession && !resetRootfs) return
        if (clearChatHistory && !clearApiKeys && !clearChatGptSession && !resetRootfs) {
            clearChatRecordsData()
            return
        }
        if (clearApiKeys && !clearChatHistory && !clearChatGptSession && !resetRootfs) {
            clearAllApiKeysData()
            return
        }
        if (clearChatGptSession && !clearChatHistory && !clearApiKeys && !resetRootfs) {
            clearChatGptSessionData()
            return
        }
        if (resetRootfs && !clearChatHistory && !clearApiKeys && !clearChatGptSession) {
            resetRootfsData()
            return
        }
        viewModelScope.launch {
            isBusy.value = true
            try {
                if (clearChatHistory) {
                    val defaultTitle = appContext.getString(R.string.chat_default_session_title)
                    if (ChatSessionRepository.isReady) {
                        ChatSessionRepository.clearAllSessions()
                        ChatSessionRepository.flush()
                    }
                    ChatBackgroundRuntime.current.clearAllSessions(defaultTitlePrefix = defaultTitle)
                    if (MaidMemoryManager.isInitialized) {
                        MaidMemoryManager.clearAllMemory()
                    }
                }
                if (clearApiKeys) {
                    LlmKeyRepository.clearAllApiKeys()
                    ModelConfigRepository.clearAllCustomProfiles()
                    selectedCustomProfileId.value = CustomProviderProfile.DEFAULT_ID
                    customProfileNameInput.value = ModelConfigRepository.DEFAULT_CUSTOM_PROFILE_NAME
                    customBaseUrlInput.value = ""
                    invalidateAllModelLists()
                    inputErrorRes.value = null
                    inputText.value = ""
                    AppRuntime.clearAllCachedExecutors()
                }
                if (clearChatGptSession) {
                    chatGptSignInJob?.cancel()
                    chatGptSignInJob = null
                    ChatGptAuthRepository.signOut()
                    invalidateModelList(ModelProviderTarget.providerCacheKey(LlmProvider.CHATGPT))
                    AppRuntime.invalidateExecutorsForProvider(LlmProvider.CHATGPT)
                }
                if (clearApiKeys || clearChatGptSession) {
                    AppRuntime.syncModelExecutors()
                }
                if (resetRootfs) {
                    val status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        TermuxProotRuntime.resetRootfs(appContext)
                        TermuxProotRuntime.getRootfsArchiveStatus(appContext)
                    }
                    rootfsArchiveStatus.value = status
                }
                _snackbarMessages.emit(R.string.settings_clear_data_both_done)
            } catch (_: Exception) {
                _snackbarMessages.emit(R.string.settings_clear_data_failed)
            } finally {
                isBusy.value = false
            }
        }
    }

    fun refreshTinyModels() {
        viewModelScope.launch {
            isScanningTinyModels.value = true
            try {
                val options = withContext(Dispatchers.IO) {
                    TinyModelFileStore(appContext).discoverAvailableModels()
                }
                tinyModelOptions.value = options
            } finally {
                isScanningTinyModels.value = false
            }
        }
    }

    fun selectTinyModel(option: TinyModelOption) {
        viewModelScope.launch {
            TinyModelConfigRepository.selectOption(option)
            AppRuntime.syncTinyModel()
            refreshTinyModels()
            _statusMessages.emit(
                appContext.getString(
                    R.string.settings_tiny_model_selected_done,
                    option.spec.displayName,
                ),
            )
        }
    }

    fun selectTinyModelBackend(preference: TinyModelBackendPreference) {
        viewModelScope.launch {
            TinyModelConfigRepository.saveBackendPreference(preference)
            AppRuntime.syncTinyModel()
            _snackbarMessages.emit(R.string.settings_tiny_model_backend_updated)
        }
    }

    fun importTinyModel(uriString: String) {
        val parsedUri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        viewModelScope.launch {
            isImportingTinyModel.value = true
            try {
                val fileStore = TinyModelFileStore(appContext)
                val displayName = withContext(Dispatchers.IO) {
                    queryDocumentDisplayName(parsedUri) ?: "imported_model.litertlm"
                }
                val importedFile = withContext(Dispatchers.IO) {
                    val stream = appContext.contentResolver.openInputStream(parsedUri)
                        ?: error("无法读取所选模型文件")
                    fileStore.importModelFile(
                        fileName = displayName,
                        inputStream = stream,
                    )
                }
                val options = withContext(Dispatchers.IO) {
                    fileStore.discoverAvailableModels()
                }
                tinyModelOptions.value = options
                val importedOption = options.firstOrNull {
                    it.sourceType == TinyModelSourceType.IMPORTED_LOCAL &&
                        it.customModelPath == importedFile.absolutePath
                }
                if (importedOption != null) {
                    TinyModelConfigRepository.selectOption(importedOption)
                    AppRuntime.syncTinyModel()
                }
                _statusMessages.emit(
                    appContext.getString(
                        R.string.settings_tiny_model_import_done,
                        importedOption?.spec?.displayName ?: importedFile.name,
                    ),
                )
            } catch (e: Exception) {
                _statusMessages.emit(
                    appContext.getString(
                        R.string.settings_tiny_model_import_failed,
                        e.message ?: "unknown error",
                    ),
                )
            } finally {
                isImportingTinyModel.value = false
            }
        }
    }

    fun deleteImportedTinyModel(option: TinyModelOption) {
        val path = option.customModelPath ?: return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                TinyModelFileStore(appContext).deleteImportedModel(path)
            }
            if (deleted) {
                if (uiState.value.tinyModelSelection.selectedCustomModelPath == path) {
                    TinyModelConfigRepository.resetToDefault()
                }
                AppRuntime.syncTinyModel()
                refreshTinyModels()
                _statusMessages.emit(
                    appContext.getString(
                        R.string.settings_tiny_model_delete_done,
                        option.spec.displayName,
                    ),
                )
            }
        }
    }

    fun preloadTinyModel() {
        viewModelScope.launch {
            AppRuntime.syncTinyModel()
            val activeModel = AppRuntime.tinyModel
            if (activeModel == null) {
                _statusMessages.emit(
                    appContext.getString(
                        R.string.settings_tiny_model_preload_failed,
                        "TinyModel 未初始化",
                    ),
                )
                return@launch
            }
            val result = activeModel.preload()
            refreshTinyModels()
            if (result.isSuccess) {
                _snackbarMessages.emit(R.string.settings_tiny_model_preload_done)
            } else {
                _statusMessages.emit(
                    appContext.getString(
                        R.string.settings_tiny_model_preload_failed,
                        result.exceptionOrNull()?.message ?: "unknown error",
                    ),
                )
            }
        }
    }

    fun unloadTinyModel() {
        viewModelScope.launch {
            AppRuntime.tinyModel?.unload()
            _snackbarMessages.emit(R.string.settings_tiny_model_unload_done)
        }
    }

    fun selectSearxngMode(mode: SearxngMode) {
        viewModelScope.launch {
            val saved = SearchConfigRepository.saveSearxngMode(mode)
            AppRuntime.invalidateSearchAvailabilityCache()
            if (mode == SearxngMode.PROOT_LOCAL) {
                ProotSearXngManager.refreshStatus(saved.safeLocalProotPort)
            }
            val labelRes = when (mode) {
                SearxngMode.DISABLED -> R.string.settings_search_mode_disabled
                SearxngMode.EXTERNAL -> R.string.settings_search_mode_external
                SearxngMode.PROOT_LOCAL -> R.string.settings_search_mode_proot
            }
            _statusMessages.emit(
                appContext.getString(
                    R.string.settings_search_mode_switched,
                    appContext.getString(labelRes),
                ),
            )
        }
    }

    fun saveSearxngBaseUrl(rawUrl: String) {
        viewModelScope.launch {
            val trimmed = rawUrl.trim()
            if (trimmed.isNotEmpty() && SearchSettingsConfig.normalizeSearxngUrl(trimmed) == null) {
                _snackbarMessages.emit(R.string.settings_search_searxng_invalid)
                return@launch
            }
            val saved = SearchConfigRepository.saveSearxngBaseUrl(trimmed)
            AppRuntime.invalidateSearchAvailabilityCache()
            if (saved.searxngBaseUrl.isEmpty()) {
                _snackbarMessages.emit(R.string.settings_search_searxng_cleared)
            } else {
                _statusMessages.emit(
                    appContext.getString(
                        R.string.settings_search_searxng_saved,
                        saved.searxngBaseUrl,
                    ),
                )
            }
        }
    }

    fun saveLocalProotSearxngPort(portInput: String) {
        val parsed = portInput.trim().toIntOrNull()
        if (parsed == null || parsed !in SearchSettingsConfig.MIN_PORT..SearchSettingsConfig.MAX_PORT) {
            viewModelScope.launch {
                _snackbarMessages.emit(R.string.settings_search_proot_port_invalid)
            }
            return
        }
        viewModelScope.launch {
            val current = SearchConfigRepository.getConfig()
            val saved = SearchConfigRepository.saveLocalProotOptions(
                port = parsed,
                autoStart = current.autoStartLocalProot,
            )
            AppRuntime.invalidateSearchAvailabilityCache()
            ProotSearXngManager.refreshStatus(saved.safeLocalProotPort)
            _statusMessages.emit(
                appContext.getString(R.string.settings_search_proot_port_saved, saved.safeLocalProotPort),
            )
        }
    }

    fun setLocalProotSearxngAutoStart(autoStart: Boolean) {
        viewModelScope.launch {
            val current = SearchConfigRepository.getConfig()
            SearchConfigRepository.saveLocalProotOptions(
                port = current.safeLocalProotPort,
                autoStart = autoStart,
            )
            AppRuntime.invalidateSearchAvailabilityCache()
        }
    }

    fun refreshProotSearxngStatus() {
        viewModelScope.launch {
            val port = SearchConfigRepository.getConfig().safeLocalProotPort
            ProotSearXngManager.refreshStatus(port)
        }
    }

    fun installProotSearxng() {
        viewModelScope.launch {
            val port = SearchConfigRepository.getConfig().safeLocalProotPort
            val result = ProotSearXngManager.install(port)
            AppRuntime.invalidateSearchAvailabilityCache()
            if (result.isSuccess) {
                _snackbarMessages.emit(R.string.settings_search_proot_install_done)
            } else {
                _statusMessages.emit(result.exceptionOrNull()?.message ?: "安装本机 SearXNG 失败")
            }
        }
    }

    fun startProotSearxng() {
        viewModelScope.launch {
            val port = SearchConfigRepository.getConfig().safeLocalProotPort
            val result = ProotSearXngManager.start(port)
            AppRuntime.invalidateSearchAvailabilityCache()
            if (result.isSuccess) {
                _statusMessages.emit(
                    appContext.getString(R.string.settings_search_proot_start_done, port),
                )
            } else {
                _statusMessages.emit(result.exceptionOrNull()?.message ?: "启动本机 SearXNG 失败")
            }
        }
    }

    fun stopProotSearxng() {
        viewModelScope.launch {
            ProotSearXngManager.stop()
            AppRuntime.invalidateSearchAvailabilityCache()
            _snackbarMessages.emit(R.string.settings_search_proot_stop_done)
        }
    }

    fun uninstallProotSearxng() {
        viewModelScope.launch {
            val result = ProotSearXngManager.uninstall()
            AppRuntime.invalidateSearchAvailabilityCache()
            if (result.isSuccess) {
                _snackbarMessages.emit(R.string.settings_search_proot_uninstall_done)
            } else {
                _statusMessages.emit(result.exceptionOrNull()?.message ?: "卸载本机 SearXNG 失败")
            }
        }
    }

    fun probeAllSearchEngines() {
        if (isProbingSearchEngines.value) return
        viewModelScope.launch {
            isProbingSearchEngines.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    AppRuntime.getOrCreateSearchEngineRouter().probeAll(forceRefresh = true)
                }
                engineAvailabilityList.value = results
            } catch (e: Exception) {
                _statusMessages.emit("探测搜索引擎失败：${e.message ?: "unknown error"}")
            } finally {
                isProbingSearchEngines.value = false
            }
        }
    }

    private fun queryDocumentDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.trim()?.takeIf { it.isNotEmpty() } else null
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun parseValidatedStep(
        raw: String,
        min: Int,
        label: String,
    ): Int? {
        val parsed = raw.trim().toIntOrNull()
        if (parsed == null || parsed < min) {
            _statusMessages.tryEmit(
                appContext.getString(
                    R.string.settings_agent_steps_invalid_range,
                    label,
                    min,
                ),
            )
            return null
        }
        return parsed
    }

    private fun sanitizeNumericInput(value: String): String {
        return value.filter { it.isDigit() }
    }
}

private data class AgentStepDraftSnapshot(
    val chatInput: String,
    val plannerStepInput: String,
    val plannerCheckpointInput: String,
    val deviceInput: String,
    val isSaving: Boolean,
)
