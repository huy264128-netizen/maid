package top.aaa000111.maid.settings

import android.content.Intent
import android.content.res.Configuration
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aaa000111.maid.R
import top.aaa000111.maid.provider.ChatGptAuthStatus
import top.aaa000111.maid.provider.LlmKeyRepository
import top.aaa000111.maid.provider.LlmModelOption
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.ModelProviderTarget
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.shizuku.ShizukuAvailability
import top.aaa000111.maid.shizuku.ShizukuServerInfo
import top.aaa000111.maid.shizuku.ShizukuStatus
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.PrivilegeOverview
import top.aaa000111.maid.androidtool.ScreenCaptureResult
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import top.aaa000111.maid.search.ProotSearXngPhase
import top.aaa000111.maid.search.ProotSearXngStatus
import top.aaa000111.maid.search.SearchEngineAvailability
import top.aaa000111.maid.search.SearchEngineCategory
import top.aaa000111.maid.search.SearchSettingsConfig
import top.aaa000111.maid.search.SearxngMode
import top.aaa000111.maid.tinymodel.TinyModelBackendPreference
import top.aaa000111.maid.tinymodel.TinyModelCatalog
import top.aaa000111.maid.tinymodel.TinyModelOption
import top.aaa000111.maid.tinymodel.TinyModelSelection
import top.aaa000111.maid.tinymodel.TinyModelSourceType
import top.aaa000111.maid.tinymodel.TinyModelState
import top.aaa000111.maid.ui.theme.MaidTheme

enum class SettingsDestination {
    HOME,
    LLM_KEYS,
    CHATGPT_AUTH,
    MODEL_CONFIG,
    TINY_MODEL,
    SEARCH_SETTINGS,
    AGENT_STEPS,
    PERSONA,
    PRIVILEGES,
    MODEL_SAFETY,
    CLEAR_DATA,
    ACKNOWLEDGEMENTS,
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentDestination by rememberSaveable { mutableStateOf(SettingsDestination.HOME) }

    LaunchedEffect(viewModel, resources) {
        viewModel.snackbarMessages.collect { messageRes ->
            snackbarHostState.showSnackbar(resources.getString(messageRes))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.statusMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.externalAuthUrls.collect { authUrl ->
            val uri = authUrl.toUri()
            val launched = runCatching {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, uri)
            }.isSuccess || runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.isSuccess
            if (!launched) {
                viewModel.onChatGptBrowserLaunchFailed()
            }
        }
    }

    val checkRootOnResume by rememberUpdatedState(uiState.hasCheckedRoot)

    DisposableEffect(lifecycleOwner, currentDestination) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPrivileges(checkRoot = checkRootOnResume)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = currentDestination != SettingsDestination.HOME) {
        when (currentDestination) {
            SettingsDestination.MODEL_CONFIG -> viewModel.resetDraftModelConfigsToSaved()
            SettingsDestination.AGENT_STEPS -> viewModel.resetDraftAgentStepsToSaved()
            SettingsDestination.PERSONA -> viewModel.resetDraftPersonaToSaved()
            else -> Unit
        }
        currentDestination = SettingsDestination.HOME
    }

    SettingsScreenContent(
        currentDestination = currentDestination,
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNavigateBack = {
            if (currentDestination == SettingsDestination.HOME) {
                onNavigateBack()
            } else {
                when (currentDestination) {
                    SettingsDestination.MODEL_CONFIG -> viewModel.resetDraftModelConfigsToSaved()
                    SettingsDestination.AGENT_STEPS -> viewModel.resetDraftAgentStepsToSaved()
                    SettingsDestination.PERSONA -> viewModel.resetDraftPersonaToSaved()
                    else -> Unit
                }
                currentDestination = SettingsDestination.HOME
            }
        },
        onOpenLlmKeys = { currentDestination = SettingsDestination.LLM_KEYS },
        onOpenChatGptAuth = { currentDestination = SettingsDestination.CHATGPT_AUTH },
        onOpenModelConfig = {
            viewModel.resetDraftModelConfigsToSaved()
            viewModel.refreshSelectedProviderModels()
            currentDestination = SettingsDestination.MODEL_CONFIG
        },
        onOpenTinyModel = {
            viewModel.refreshTinyModels()
            currentDestination = SettingsDestination.TINY_MODEL
        },
        onOpenAgentSteps = {
            viewModel.resetDraftAgentStepsToSaved()
            currentDestination = SettingsDestination.AGENT_STEPS
        },
        onOpenPersona = {
            viewModel.resetDraftPersonaToSaved()
            viewModel.refreshPersonaPresets()
            currentDestination = SettingsDestination.PERSONA
        },
        onOpenPrivileges = {
            viewModel.refreshPrivileges(checkRoot = false)
            currentDestination = SettingsDestination.PRIVILEGES
        },
        onSelectProvider = viewModel::selectProvider,
        onInputChanged = viewModel::onInputChanged,
        onCustomBaseUrlChanged = viewModel::onCustomBaseUrlChanged,
        onSelectCustomProfile = viewModel::selectCustomProfile,
        onStartAddCustomProfile = viewModel::startAddCustomProfile,
        onCustomProfileNameChanged = viewModel::onCustomProfileNameChanged,
        onDeleteCustomProfile = viewModel::deleteSelectedCustomProfile,
        onSaveApiKey = viewModel::saveApiKey,
        onClearApiKey = viewModel::clearApiKey,
        onSelectModelProvider = viewModel::onSelectModelProvider,
        onSelectModelId = viewModel::onSelectModelId,
        onRefreshProviderModels = { provider, customId ->
            viewModel.fetchModelsForProvider(provider, customId)
        },
        onSaveModelConfigs = viewModel::saveModelConfigs,
        onClearAllModelConfigs = viewModel::clearAllModelConfigs,
        onRefreshTinyModels = viewModel::refreshTinyModels,
        onSelectTinyModel = viewModel::selectTinyModel,
        onSelectTinyModelBackend = viewModel::selectTinyModelBackend,
        onImportTinyModel = viewModel::importTinyModel,
        onDeleteImportedTinyModel = viewModel::deleteImportedTinyModel,
        onPreloadTinyModel = viewModel::preloadTinyModel,
        onUnloadTinyModel = viewModel::unloadTinyModel,
        onChatStepInputChanged = viewModel::onChatStepInputChanged,
        onPlannerStepInputChanged = viewModel::onPlannerStepInputChanged,
        onPlannerCheckpointInputChanged = viewModel::onPlannerCheckpointInputChanged,
        onDeviceActionStepInputChanged = viewModel::onDeviceActionStepInputChanged,
        onToggleCrossChatMemory = viewModel::setCrossChatMemoryEnabled,
        onSelectModelOutputDisplayMode = viewModel::setModelOutputDisplayMode,
        onSaveAgentStepConfigs = viewModel::saveAgentStepConfigs,
        onResetAgentStepConfigs = viewModel::resetAgentStepConfigs,
        onRefreshPersonaPresets = viewModel::refreshPersonaPresets,
        onSelectPersonaPreset = viewModel::onSelectPersonaPreset,
        onAddPersonaPreset = viewModel::addCustomPersona,
        onDeletePersonaPreset = viewModel::deleteCustomPersona,
        onPersonaTextChanged = viewModel::onPersonaTextChanged,
        onSavePersona = viewModel::savePersona,
        onClearPersona = viewModel::clearPersona,
        onRefreshPrivileges = { viewModel.refreshPrivileges(checkRoot = true) },
        onRequestPrivilege = viewModel::requestPrivilege,
        onCaptureTestScreenshot = viewModel::captureTestScreenshot,
        onOpenModelSafety = { currentDestination = SettingsDestination.MODEL_SAFETY },
        onApplySafetyLevel = viewModel::applySafetyLevel,
        onUpdateSafetyConfig = viewModel::updateSafetyConfig,
        onResetSafetyConfig = viewModel::resetSafetyConfig,
        onOpenClearData = {
            viewModel.refreshRootfsArchiveStatus()
            currentDestination = SettingsDestination.CLEAR_DATA
        },
        onClearChatRecordsData = viewModel::clearChatRecordsData,
        onClearAllApiKeysData = viewModel::clearAllApiKeysData,
        onClearChatGptSessionData = viewModel::clearChatGptSessionData,
        onResetRootfsData = viewModel::resetRootfsData,
        onImportRootfsArchive = viewModel::importCustomRootfsArchive,
        onClearCustomRootfsArchive = viewModel::clearCustomRootfsArchive,
        onClearSelectedData = viewModel::clearSelectedData,
        onOpenSearchSettings = {
            viewModel.refreshProotSearxngStatus()
            currentDestination = SettingsDestination.SEARCH_SETTINGS
        },
        onSelectSearxngMode = viewModel::selectSearxngMode,
        onSaveSearxngBaseUrl = viewModel::saveSearxngBaseUrl,
        onSaveLocalProotSearxngPort = viewModel::saveLocalProotSearxngPort,
        onSetLocalProotSearxngAutoStart = viewModel::setLocalProotSearxngAutoStart,
        onRefreshProotSearxngStatus = viewModel::refreshProotSearxngStatus,
        onInstallProotSearxng = viewModel::installProotSearxng,
        onStartProotSearxng = viewModel::startProotSearxng,
        onStopProotSearxng = viewModel::stopProotSearxng,
        onUninstallProotSearxng = viewModel::uninstallProotSearxng,
        onProbeAllSearchEngines = viewModel::probeAllSearchEngines,
        onStartChatGptSignIn = viewModel::startChatGptSignIn,
        onCancelChatGptSignIn = viewModel::cancelChatGptSignIn,
        onRefreshChatGptToken = viewModel::refreshChatGptToken,
        onSignOutChatGpt = viewModel::signOutChatGpt,
        onOpenAcknowledgements = { currentDestination = SettingsDestination.ACKNOWLEDGEMENTS },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    currentDestination: SettingsDestination,
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onOpenLlmKeys: () -> Unit,
    onOpenModelConfig: () -> Unit,
    onOpenPersona: () -> Unit,
    onOpenPrivileges: () -> Unit,
    onSelectProvider: (LlmProvider) -> Unit,
    onInputChanged: (String) -> Unit,
    onCustomBaseUrlChanged: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onClearApiKey: () -> Unit,
    onSelectModelProvider: (ModelRole, ModelProviderTarget?) -> Unit,
    onSelectModelId: (ModelRole, String) -> Unit,
    onRefreshProviderModels: (LlmProvider, String?) -> Unit,
    onSaveModelConfigs: () -> Unit,
    onClearAllModelConfigs: () -> Unit,
    onRefreshPersonaPresets: () -> Unit,
    onSelectPersonaPreset: (PersonaPreset) -> Unit,
    onPersonaTextChanged: (String) -> Unit,
    onSavePersona: () -> Unit,
    onClearPersona: () -> Unit,
    onRefreshPrivileges: () -> Unit,
    onRequestPrivilege: (AndroidPrivilege) -> Unit,
    onCaptureTestScreenshot: () -> Unit,
    onSelectCustomProfile: (String) -> Unit = {},
    onStartAddCustomProfile: () -> Unit = {},
    onCustomProfileNameChanged: (String) -> Unit = {},
    onDeleteCustomProfile: () -> Unit = {},
    onAddPersonaPreset: (String, String) -> Unit = { _, _ -> },
    onDeletePersonaPreset: (PersonaPreset) -> Unit = {},
    onOpenAgentSteps: () -> Unit = {},
    onChatStepInputChanged: (String) -> Unit = {},
    onPlannerStepInputChanged: (String) -> Unit = {},
    onPlannerCheckpointInputChanged: (String) -> Unit = {},
    onDeviceActionStepInputChanged: (String) -> Unit = {},
    onToggleCrossChatMemory: (Boolean) -> Unit = {},
    onSelectModelOutputDisplayMode: (ModelOutputDisplayMode) -> Unit = {},
    onSaveAgentStepConfigs: () -> Unit = {},
    onResetAgentStepConfigs: () -> Unit = {},
    onOpenModelSafety: () -> Unit = {},
    onApplySafetyLevel: (ModelSafetyLevel) -> Unit = {},
    onUpdateSafetyConfig: ((ModelSafetyConfig) -> ModelSafetyConfig) -> Unit = {},
    onResetSafetyConfig: () -> Unit = {},
    onOpenClearData: () -> Unit = {},
    onClearChatRecordsData: () -> Unit = {},
    onClearAllApiKeysData: () -> Unit = {},
    onClearChatGptSessionData: () -> Unit = {},
    onResetRootfsData: () -> Unit = {},
    onImportRootfsArchive: (String) -> Unit = {},
    onClearCustomRootfsArchive: () -> Unit = {},
    onClearSelectedData: (Boolean, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _ -> },
    onOpenTinyModel: () -> Unit = {},
    onRefreshTinyModels: () -> Unit = {},
    onSelectTinyModel: (TinyModelOption) -> Unit = {},
    onSelectTinyModelBackend: (TinyModelBackendPreference) -> Unit = {},
    onImportTinyModel: (String) -> Unit = {},
    onDeleteImportedTinyModel: (TinyModelOption) -> Unit = {},
    onPreloadTinyModel: () -> Unit = {},
    onUnloadTinyModel: () -> Unit = {},
    onOpenSearchSettings: () -> Unit = {},
    onSelectSearxngMode: (SearxngMode) -> Unit = {},
    onSaveSearxngBaseUrl: (String) -> Unit = {},
    onSaveLocalProotSearxngPort: (String) -> Unit = {},
    onSetLocalProotSearxngAutoStart: (Boolean) -> Unit = {},
    onRefreshProotSearxngStatus: () -> Unit = {},
    onInstallProotSearxng: () -> Unit = {},
    onStartProotSearxng: () -> Unit = {},
    onStopProotSearxng: () -> Unit = {},
    onUninstallProotSearxng: () -> Unit = {},
    onProbeAllSearchEngines: () -> Unit = {},
    onOpenChatGptAuth: () -> Unit = {},
    onStartChatGptSignIn: (Boolean) -> Unit = {},
    onCancelChatGptSignIn: () -> Unit = {},
    onRefreshChatGptToken: () -> Unit = {},
    onSignOutChatGpt: () -> Unit = {},
    onOpenAcknowledgements: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showAddPersonaDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentDestination) {
                            SettingsDestination.HOME -> stringResource(R.string.settings_title)
                            SettingsDestination.LLM_KEYS -> stringResource(R.string.settings_llm_entry_title)
                            SettingsDestination.CHATGPT_AUTH -> stringResource(R.string.settings_chatgpt_title)
                            SettingsDestination.MODEL_CONFIG -> stringResource(R.string.settings_model_config_title)
                            SettingsDestination.TINY_MODEL -> stringResource(R.string.settings_tiny_model_title)
                            SettingsDestination.SEARCH_SETTINGS -> stringResource(R.string.settings_search_title)
                            SettingsDestination.AGENT_STEPS -> stringResource(R.string.settings_agent_steps_title)
                            SettingsDestination.PERSONA -> stringResource(R.string.settings_persona_title)
                            SettingsDestination.PRIVILEGES -> stringResource(R.string.settings_privileges_title)
                            SettingsDestination.MODEL_SAFETY -> stringResource(R.string.settings_model_safety_title)
                            SettingsDestination.CLEAR_DATA -> stringResource(R.string.settings_clear_data_title)
                            SettingsDestination.ACKNOWLEDGEMENTS -> stringResource(R.string.settings_acknowledgements_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.settings_navigate_back),
                        )
                    }
                },
                actions = {
                    if (currentDestination == SettingsDestination.PRIVILEGES) {
                        TextButton(onClick = onRefreshPrivileges) {
                            Text(stringResource(R.string.settings_privileges_refresh))
                        }
                    } else if (currentDestination == SettingsDestination.TINY_MODEL) {
                        TextButton(
                            onClick = onRefreshTinyModels,
                            enabled = !uiState.isScanningTinyModels && !uiState.isImportingTinyModel,
                        ) {
                            Text(stringResource(R.string.settings_tiny_model_rescan))
                        }
                    } else if (currentDestination == SettingsDestination.SEARCH_SETTINGS) {
                        TextButton(onClick = onRefreshProotSearxngStatus) {
                            Text(stringResource(R.string.settings_search_proot_action_refresh))
                        }
                    } else if (currentDestination == SettingsDestination.PERSONA) {
                        IconButton(
                            onClick = { showAddPersonaDialog = true },
                            enabled = !uiState.isSavingPersona,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.settings_persona_add),
                            )
                        }
                        TextButton(
                            onClick = onRefreshPersonaPresets,
                            enabled = !uiState.isScanningPersonas,
                        ) {
                            Text(stringResource(R.string.settings_persona_presets_rescan))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentDestination,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "settings_destination",
        ) { destination ->
            when (destination) {
                SettingsDestination.HOME -> {
                    SettingsHomeList(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onOpenLlmKeys = onOpenLlmKeys,
                        onOpenChatGptAuth = onOpenChatGptAuth,
                        onOpenModelConfig = onOpenModelConfig,
                        onOpenTinyModel = onOpenTinyModel,
                        onOpenSearchSettings = onOpenSearchSettings,
                        onOpenAgentSteps = onOpenAgentSteps,
                        onToggleCrossChatMemory = onToggleCrossChatMemory,
                        onSelectModelOutputDisplayMode = onSelectModelOutputDisplayMode,
                        onOpenPersona = onOpenPersona,
                        onOpenPrivileges = onOpenPrivileges,
                        onOpenModelSafety = onOpenModelSafety,
                        onOpenClearData = onOpenClearData,
                        onOpenAcknowledgements = onOpenAcknowledgements,
                    )
                }

                SettingsDestination.LLM_KEYS -> {
                    LlmKeyManagementContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onSelectProvider = onSelectProvider,
                        onInputChanged = onInputChanged,
                        onCustomBaseUrlChanged = onCustomBaseUrlChanged,
                        onSelectCustomProfile = onSelectCustomProfile,
                        onStartAddCustomProfile = onStartAddCustomProfile,
                        onCustomProfileNameChanged = onCustomProfileNameChanged,
                        onDeleteCustomProfile = onDeleteCustomProfile,
                        onSaveApiKey = onSaveApiKey,
                        onClearApiKey = onClearApiKey,
                    )
                }

                SettingsDestination.CHATGPT_AUTH -> {
                    ChatGptAuthContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onStartSignIn = onStartChatGptSignIn,
                        onCancelSignIn = onCancelChatGptSignIn,
                        onRefreshToken = onRefreshChatGptToken,
                        onSignOut = onSignOutChatGpt,
                        onOpenModelConfig = onOpenModelConfig,
                    )
                }

                SettingsDestination.MODEL_CONFIG -> {
                    ModelSelectionContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onOpenLlmKeys = onOpenLlmKeys,
                        onOpenChatGptAuth = onOpenChatGptAuth,
                        onSelectModelProvider = onSelectModelProvider,
                        onSelectModelId = onSelectModelId,
                        onRefreshProviderModels = onRefreshProviderModels,
                        onSaveModelConfigs = onSaveModelConfigs,
                        onClearAllModelConfigs = onClearAllModelConfigs,
                    )
                }

                SettingsDestination.TINY_MODEL -> {
                    TinyModelSelectionContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onRefreshTinyModels = onRefreshTinyModels,
                        onSelectTinyModel = onSelectTinyModel,
                        onSelectTinyModelBackend = onSelectTinyModelBackend,
                        onImportTinyModel = onImportTinyModel,
                        onDeleteImportedTinyModel = onDeleteImportedTinyModel,
                        onPreloadTinyModel = onPreloadTinyModel,
                        onUnloadTinyModel = onUnloadTinyModel,
                    )
                }

                SettingsDestination.SEARCH_SETTINGS -> {
                    SearchSettingsContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onSelectSearxngMode = onSelectSearxngMode,
                        onSaveSearxngBaseUrl = onSaveSearxngBaseUrl,
                        onSaveLocalProotSearxngPort = onSaveLocalProotSearxngPort,
                        onSetLocalProotSearxngAutoStart = onSetLocalProotSearxngAutoStart,
                        onInstallProotSearxng = onInstallProotSearxng,
                        onStartProotSearxng = onStartProotSearxng,
                        onStopProotSearxng = onStopProotSearxng,
                        onUninstallProotSearxng = onUninstallProotSearxng,
                        onProbeAllSearchEngines = onProbeAllSearchEngines,
                    )
                }

                SettingsDestination.AGENT_STEPS -> {
                    AgentStepsContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onChatStepInputChanged = onChatStepInputChanged,
                        onPlannerStepInputChanged = onPlannerStepInputChanged,
                        onPlannerCheckpointInputChanged = onPlannerCheckpointInputChanged,
                        onDeviceActionStepInputChanged = onDeviceActionStepInputChanged,
                        onToggleCrossChatMemory = onToggleCrossChatMemory,
                        onSaveAgentStepConfigs = onSaveAgentStepConfigs,
                        onResetAgentStepConfigs = onResetAgentStepConfigs,
                    )
                }

                SettingsDestination.PERSONA -> {
                    PersonaManagementContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onRefreshPersonaPresets = onRefreshPersonaPresets,
                        onOpenAddPersonaDialog = { showAddPersonaDialog = true },
                        onSelectPersonaPreset = onSelectPersonaPreset,
                        onDeletePersonaPreset = onDeletePersonaPreset,
                        onPersonaTextChanged = onPersonaTextChanged,
                        onSavePersona = onSavePersona,
                        onClearPersona = onClearPersona,
                    )
                }

                SettingsDestination.PRIVILEGES -> {
                    PrivilegeManagementContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onRequestPrivilege = onRequestPrivilege,
                        onCaptureTestScreenshot = onCaptureTestScreenshot,
                    )
                }

                SettingsDestination.MODEL_SAFETY -> {
                    ModelSafetyContent(
                        safetyConfig = uiState.safetyConfig,
                        contentPadding = innerPadding,
                        onApplySafetyLevel = onApplySafetyLevel,
                        onUpdateSafetyConfig = onUpdateSafetyConfig,
                        onResetSafetyConfig = onResetSafetyConfig,
                    )
                }

                SettingsDestination.CLEAR_DATA -> {
                    ClearDataContent(
                        uiState = uiState,
                        contentPadding = innerPadding,
                        onClearChatRecordsData = onClearChatRecordsData,
                        onClearAllApiKeysData = onClearAllApiKeysData,
                        onClearChatGptSessionData = onClearChatGptSessionData,
                        onResetRootfsData = onResetRootfsData,
                        onImportRootfsArchive = onImportRootfsArchive,
                        onClearCustomRootfsArchive = onClearCustomRootfsArchive,
                        onClearSelectedData = onClearSelectedData,
                    )
                }

                SettingsDestination.ACKNOWLEDGEMENTS -> {
                    AcknowledgementsContent(
                        contentPadding = innerPadding,
                    )
                }
            }
        }
    }

    if (showAddPersonaDialog) {
        AddPersonaDialog(
            onDismiss = { showAddPersonaDialog = false },
            onConfirm = { name, content ->
                showAddPersonaDialog = false
                onAddPersonaPreset(name, content)
            },
        )
    }
}

@Composable
private fun SettingsHomeList(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onOpenLlmKeys: () -> Unit,
    onOpenChatGptAuth: () -> Unit,
    onOpenModelConfig: () -> Unit,
    onOpenAgentSteps: () -> Unit,
    onOpenPersona: () -> Unit,
    onOpenPrivileges: () -> Unit,
    onToggleCrossChatMemory: (Boolean) -> Unit = {},
    onSelectModelOutputDisplayMode: (ModelOutputDisplayMode) -> Unit = {},
    onOpenModelSafety: () -> Unit = {},
    onOpenClearData: () -> Unit = {},
    onOpenTinyModel: () -> Unit = {},
    onOpenSearchSettings: () -> Unit = {},
    onOpenAcknowledgements: () -> Unit = {},
) {
    val configuredApiKeyProviders = uiState.configuredApiKeyProviders
    val configuredNames = configuredApiKeyProviders
        .map { stringResource(it.displayNameRes) }
        .joinToString("、")
    val llmSummary = if (configuredApiKeyProviders.isEmpty()) {
        stringResource(R.string.settings_llm_entry_summary_empty)
    } else {
        stringResource(
            R.string.settings_llm_entry_summary_configured,
            configuredApiKeyProviders.size,
            configuredNames,
        )
    }

    val chatGptState = uiState.chatGptAuthState
    val chatGptSummary = when (chatGptState.status) {
        ChatGptAuthStatus.SIGNED_OUT -> stringResource(R.string.settings_chatgpt_entry_summary_signed_out)
        ChatGptAuthStatus.SIGNING_IN -> stringResource(R.string.settings_chatgpt_entry_summary_signing_in)
        ChatGptAuthStatus.SIGNED_IN_READY -> stringResource(
            R.string.settings_chatgpt_entry_summary_ready,
            chatGptState.profile?.displayLabel ?: "ChatGPT",
        )
        ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE -> stringResource(
            R.string.settings_chatgpt_entry_summary_no_scope,
            chatGptState.profile?.displayLabel ?: "ChatGPT",
        )
        ChatGptAuthStatus.SESSION_EXPIRED -> stringResource(R.string.settings_chatgpt_entry_summary_expired)
    }

    val activePrivilegeNames = buildList {
        val ov = uiState.privilegeOverview
        if (ov.shizukuStatus.isReady) {
            add(
                if (ov.shizukuStatus.serverInfo?.isRoot == true) "Root-Shizuku" else "Shizuku",
            )
        }
        if (ov.dhizukuGranted) add("Dhizuku")
        if (ov.accessibilityConnected || ov.accessibilityEnabled) add("无障碍")
        if (ov.rootAvailable) add("Root")
    }
    val privilegesSummary = if (activePrivilegeNames.isEmpty()) {
        stringResource(R.string.settings_privileges_entry_summary_empty)
    } else {
        stringResource(
            R.string.settings_privileges_entry_summary_active,
            activePrivilegeNames.size,
            activePrivilegeNames.joinToString("、"),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_llm),
            )
        }

        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_llm_entry_title))
                },
                supportingContent = {
                    Text(llmSummary)
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_key),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLlmKeys),
            )
        }

        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_chatgpt_entry_title))
                },
                supportingContent = {
                    Text(chatGptSummary)
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (chatGptState.isPlanUsageReady) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_persona),
                            contentDescription = null,
                            tint = if (chatGptState.isPlanUsageReady) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenChatGptAuth),
            )
        }

        item {
            val modelSummary = if (uiState.configuredModelCount == 0) {
                stringResource(R.string.settings_model_config_entry_summary_empty)
            } else {
                stringResource(
                    R.string.settings_model_config_entry_summary_configured,
                    uiState.configuredModelCount,
                    ModelRole.entries.size,
                )
            }
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_model_config_entry_title))
                },
                supportingContent = {
                    Text(modelSummary)
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_build),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenModelConfig),
            )
        }

        item {
            val selectedTiny = uiState.selectedTinyModelOption
            val backendShortLabel = stringResource(
                when (uiState.tinyModelSelection.backendPreference) {
                    TinyModelBackendPreference.AUTO -> R.string.settings_tiny_model_backend_short_auto
                    TinyModelBackendPreference.CPU -> R.string.settings_tiny_model_backend_short_cpu
                    TinyModelBackendPreference.GPU -> R.string.settings_tiny_model_backend_short_gpu
                },
            )
            val tinySummary = if (selectedTiny != null) {
                stringResource(
                    R.string.settings_tiny_model_entry_summary_selected,
                    selectedTiny.spec.displayName,
                    backendShortLabel,
                )
            } else {
                stringResource(R.string.settings_tiny_model_entry_summary_empty)
            }
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_tiny_model_entry_title))
                },
                supportingContent = {
                    Text(tinySummary)
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTinyModel),
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_agent),
            )
        }

        item {
            val searchCfg = uiState.searchSettingsConfig
            val prootStatus = uiState.prootSearxngStatus
            val searxngSummary = when (searchCfg.searxngMode) {
                SearxngMode.DISABLED -> stringResource(R.string.settings_search_entry_summary_disabled)
                SearxngMode.EXTERNAL -> {
                    val url = searchCfg.normalizedExternalBaseUrl
                    if (url != null) {
                        stringResource(R.string.settings_search_entry_summary_external, url)
                    } else {
                        stringResource(R.string.settings_search_entry_summary_external_empty)
                    }
                }
                SearxngMode.PROOT_LOCAL -> {
                    val stateLabel = when {
                        prootStatus.isRunning -> stringResource(R.string.settings_search_proot_status_running)
                        prootStatus.isInstalled -> stringResource(R.string.settings_search_proot_status_stopped)
                        else -> stringResource(R.string.settings_search_proot_status_not_installed)
                    }
                    stringResource(
                        R.string.settings_search_entry_summary_proot,
                        searchCfg.safeLocalProotPort,
                        stateLabel,
                    )
                }
            }
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_search_entry_title))
                },
                supportingContent = {
                    Text(searxngSummary)
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSearchSettings),
            )
        }

        item {
            val crossChatMemoryEnabled = uiState.savedAgentStepConfig.enableCrossChatMemory
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_cross_chat_memory_title))
                },
                supportingContent = {
                    Text(
                        stringResource(
                            if (crossChatMemoryEnabled) {
                                R.string.settings_cross_chat_memory_summary_on
                            } else {
                                R.string.settings_cross_chat_memory_summary_off
                            },
                        ),
                    )
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chat_session),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Switch(
                        checked = crossChatMemoryEnabled,
                        onCheckedChange = onToggleCrossChatMemory,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleCrossChatMemory(!crossChatMemoryEnabled) },
            )
        }

        item {
            val outputDisplayMode = uiState.savedAgentStepConfig.modelOutputDisplayMode
            val summaryRes = when (outputDisplayMode) {
                ModelOutputDisplayMode.SHOW_ALL -> R.string.settings_model_output_display_summary_show_all
                ModelOutputDisplayMode.LAST_ONLY -> R.string.settings_model_output_display_summary_last_only
                ModelOutputDisplayMode.HIDE -> R.string.settings_model_output_display_summary_hide
            }
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_model_output_display_title))
                },
                supportingContent = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(summaryRes))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                ModelOutputDisplayMode.SHOW_ALL to R.string.settings_model_output_display_show_all,
                                ModelOutputDisplayMode.LAST_ONLY to R.string.settings_model_output_display_last_only,
                                ModelOutputDisplayMode.HIDE to R.string.settings_model_output_display_hide,
                            ).forEach { (mode, labelRes) ->
                                val selected = outputDisplayMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectModelOutputDisplayMode(mode) },
                                    label = { Text(stringResource(labelRes)) },
                                    leadingIcon = if (selected) {
                                        {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (outputDisplayMode == ModelOutputDisplayMode.HIDE) {
                                    R.drawable.ic_visibility_off
                                } else {
                                    R.drawable.ic_visibility
                                },
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            val stepConfig = uiState.savedAgentStepConfig
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_agent_steps_entry_title))
                },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.settings_agent_steps_entry_summary,
                            stepConfig.chatMaxIterations,
                            stepConfig.plannerMaxIterationsPerCheckpoint,
                            stepConfig.plannerMaxCheckpoints,
                            stepConfig.deviceActionMaxIterations,
                        ),
                    )
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAgentSteps),
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_persona),
            )
        }

        item {
            val savedPersona = uiState.savedPersonaConfig
            val matchedPreset = savedPersona.selectedPresetId?.let { presetId ->
                uiState.personaPresets.firstOrNull { it.id == presetId }
            }
            val personaSummary = when {
                !savedPersona.isConfigured && uiState.personaPresets.isNotEmpty() -> {
                    stringResource(
                        R.string.settings_persona_entry_summary_empty_with_presets,
                        uiState.personaPresets.size,
                    )
                }
                !savedPersona.isConfigured -> {
                    stringResource(R.string.settings_persona_entry_summary_empty)
                }
                matchedPreset != null -> {
                    stringResource(R.string.settings_persona_entry_summary_preset, matchedPreset.name)
                }
                else -> {
                    val singleLine = savedPersona.personaText.replace('\n', ' ').trim()
                    val preview = if (singleLine.length > 24) "${singleLine.take(24)}…" else singleLine
                    stringResource(R.string.settings_persona_entry_summary_custom, preview)
                }
            }
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_persona_entry_title))
                },
                supportingContent = {
                    Text(personaSummary)
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_persona),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPersona),
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_privileges),
            )
        }

        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_privileges_entry_title))
                },
                supportingContent = {
                    Text(privilegesSummary)
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPrivileges),
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_security),
            )
        }

        item {
            val safetyLevelLabel = stringResource(
                when (uiState.safetyConfig.safetyLevel) {
                    ModelSafetyLevel.STRICT -> R.string.settings_model_safety_level_strict
                    ModelSafetyLevel.BALANCED -> R.string.settings_model_safety_level_balanced
                    ModelSafetyLevel.UNRESTRICTED -> R.string.settings_model_safety_level_unrestricted
                    ModelSafetyLevel.CUSTOM -> R.string.settings_model_safety_level_custom
                },
            )
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_model_safety_entry_title))
                },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.settings_model_safety_entry_summary,
                            safetyLevelLabel,
                            uiState.safetyConfig.allowedSensitiveCategoryCount,
                            SensitiveToolCategory.entries.size,
                        ),
                    )
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (uiState.safetyConfig.hasHighRiskEnabled) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            tint = if (uiState.safetyConfig.hasHighRiskEnabled) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenModelSafety),
            )
        }

        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_security_note_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_llm_card_subtitle))
                },
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_data_management),
            )
        }

        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_clear_data_entry_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_clear_data_entry_summary))
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenClearData),
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_about),
            )
        }

        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_acknowledgements_entry_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_acknowledgements_entry_summary))
                },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_persona),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAcknowledgements),
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmKeyManagementContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onSelectProvider: (LlmProvider) -> Unit,
    onInputChanged: (String) -> Unit,
    onCustomBaseUrlChanged: (String) -> Unit,
    onSelectCustomProfile: (String) -> Unit = {},
    onStartAddCustomProfile: () -> Unit = {},
    onCustomProfileNameChanged: (String) -> Unit = {},
    onDeleteCustomProfile: () -> Unit = {},
    onSaveApiKey: () -> Unit,
    onClearApiKey: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var isKeyVisible by rememberSaveable { mutableStateOf(false) }
    var showClearConfirmDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_key),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(10.dp)
                                .size(22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_llm_card_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_llm_card_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = {
                        if (!uiState.isBusy) {
                            providerMenuExpanded = !providerMenuExpanded
                        }
                    },
                ) {
                    OutlinedTextField(
                        value = stringResource(uiState.selectedProvider.displayNameRes),
                        onValueChange = {},
                        readOnly = true,
                        enabled = !uiState.isBusy,
                        label = { Text(stringResource(R.string.settings_llm_provider_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = providerMenuExpanded,
                            )
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor(
                                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled = !uiState.isBusy,
                            )
                            .fillMaxWidth(),
                    )

                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false },
                    ) {
                        LlmProvider.apiKeyProviders.forEach { provider ->
                            val isConfigured = if (provider == LlmProvider.CUSTOM) {
                                uiState.customProfileStoredKeys.values.any { !it.isNullOrBlank() } ||
                                    !uiState.storedKeys[LlmProvider.CUSTOM].isNullOrBlank()
                            } else {
                                !uiState.storedKeys[provider].isNullOrBlank()
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(provider.displayNameRes)) },
                                trailingIcon = if (isConfigured) {
                                    {
                                        Text(
                                            text = stringResource(
                                                R.string.settings_llm_provider_configured_badge,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    providerMenuExpanded = false
                                    onSelectProvider(provider)
                                },
                            )
                        }
                    }
                }

                KeyStatusBanner(
                    hasStoredKey = uiState.hasStoredKeyForSelected,
                    storedKey = uiState.currentStoredKey,
                )

                if (uiState.selectedProvider == LlmProvider.CUSTOM) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_llm_custom_profiles_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            uiState.customProfiles.forEach { profile ->
                                val isSelected = profile.normalizedId == uiState.selectedCustomProfileId
                                val hasProfileKey = !uiState.customProfileStoredKeys[profile.normalizedId].isNullOrBlank()
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectCustomProfile(profile.normalizedId) },
                                    enabled = !uiState.isBusy,
                                    label = { Text(profile.normalizedName) },
                                    leadingIcon = if (hasProfileKey) {
                                        {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                            AssistChip(
                                onClick = onStartAddCustomProfile,
                                enabled = !uiState.isBusy,
                                label = { Text(stringResource(R.string.settings_llm_custom_profile_add)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add),
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.customProfileNameInput,
                        onValueChange = onCustomProfileNameChanged,
                        enabled = !uiState.isBusy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_llm_custom_profile_name_label)) },
                        placeholder = { Text(stringResource(R.string.settings_llm_custom_profile_name_placeholder)) },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = uiState.customBaseUrlInput,
                        onValueChange = onCustomBaseUrlChanged,
                        enabled = !uiState.isBusy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_llm_custom_base_url_label)) },
                        placeholder = { Text(stringResource(R.string.settings_llm_custom_base_url_placeholder)) },
                        supportingText = {
                            Text(stringResource(R.string.settings_llm_custom_base_url_helper))
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (uiState.canDeleteSelectedCustomProfile) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = onDeleteCustomProfile,
                                enabled = !uiState.isBusy,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_llm_custom_profile_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = uiState.inputText,
                    onValueChange = onInputChanged,
                    enabled = !uiState.isBusy,
                    singleLine = true,
                    isError = uiState.inputErrorRes != null,
                    label = { Text(stringResource(R.string.settings_llm_key_label)) },
                    placeholder = { Text(uiState.selectedProvider.keyHint) },
                    supportingText = {
                        val errorRes = uiState.inputErrorRes
                        if (errorRes != null) {
                            Text(stringResource(errorRes))
                        } else {
                            Text(
                                stringResource(
                                    R.string.settings_llm_key_helper,
                                    uiState.selectedProvider.keyHint,
                                ),
                            )
                        }
                    },
                    visualTransformation = if (isKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                            Icon(
                                painter = painterResource(
                                    if (isKeyVisible) {
                                        R.drawable.ic_visibility_off
                                    } else {
                                        R.drawable.ic_visibility
                                    },
                                ),
                                contentDescription = stringResource(
                                    if (isKeyVisible) {
                                        R.string.settings_llm_key_hide
                                    } else {
                                        R.string.settings_llm_key_show
                                    },
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onSaveApiKey()
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { showClearConfirmDialog = true },
                        enabled = !uiState.isBusy && uiState.hasStoredKeyForSelected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_llm_clear))
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onSaveApiKey()
                        },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_llm_save))
                    }
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_llm_configured_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (uiState.configuredApiKeyProviders.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_llm_configured_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            uiState.configuredApiKeyProviders.forEach { provider ->
                                val isSelected = provider == uiState.selectedProvider
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectProvider(provider) },
                                    label = { Text(stringResource(provider.displayNameRes)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        val providerName = stringResource(uiState.selectedProvider.displayNameRes)
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(stringResource(R.string.settings_llm_clear_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_llm_clear_dialog_message,
                        providerName,
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        onClearApiKey()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_llm_clear_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}

@Composable
private fun KeyStatusBanner(
    hasStoredKey: Boolean,
    storedKey: String?,
) {
    val containerColor = if (hasStoredKey) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (hasStoredKey) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasStoredKey) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = if (hasStoredKey) {
                    stringResource(
                        R.string.settings_llm_status_configured,
                        LlmKeyRepository.maskKey(storedKey),
                    )
                } else {
                    stringResource(R.string.settings_llm_status_unconfigured)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PrivilegeManagementContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onRequestPrivilege: (AndroidPrivilege) -> Unit,
    onCaptureTestScreenshot: () -> Unit,
) {
    val overview = uiState.privilegeOverview
    val shizuku = overview.shizukuStatus
    val isAnyRequesting = uiState.requestingPrivilege != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部综合权限概览卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_privileges_overview_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_privileges_overview_unlocked,
                                uiState.unlockedToolSetCount,
                                uiState.totalToolSetCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (overview.availablePrivileges.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_privileges_overview_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        overview.availablePrivileges.forEach { privilege ->
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(privilege.name) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // 1. Shizuku 卡片
        val shizukuDetail = when (shizuku.availability) {
            ShizukuAvailability.READY -> if (shizuku.serverInfo?.isRoot == true) {
                "已连接（Root 模式，UID=${shizuku.serverInfo.uid}）"
            } else {
                "已连接（ADB Shell 模式，UID=${shizuku.serverInfo?.uid ?: 2000}）"
            }

            ShizukuAvailability.PERMISSION_REQUIRED -> "Shizuku 服务运行中，点击申请授权"
            ShizukuAvailability.STOPPED_OR_DISCONNECTED -> "已安装 Shizuku，但后台服务尚未启动"
            ShizukuAvailability.NOT_INSTALLED -> "未检测到 Shizuku 应用"
        }
        val shizukuActionText = when (shizuku.availability) {
            ShizukuAvailability.READY -> stringResource(R.string.settings_priv_shizuku_action_recheck)
            ShizukuAvailability.STOPPED_OR_DISCONNECTED -> stringResource(R.string.settings_priv_shizuku_action_open)
            else -> stringResource(R.string.settings_priv_shizuku_action_request)
        }
        PrivilegeActionCard(
            title = stringResource(R.string.settings_priv_shizuku_title),
            description = stringResource(R.string.settings_priv_shizuku_desc),
            statusDetail = shizukuDetail,
            badgeText = when {
                shizuku.isReady -> stringResource(R.string.settings_priv_status_granted)
                shizuku.isBinderAlive -> stringResource(R.string.settings_priv_status_pending)
                else -> stringResource(R.string.settings_priv_status_inactive)
            },
            isGranted = shizuku.isReady,
            actionText = shizukuActionText,
            isLoading = uiState.requestingPrivilege == AndroidPrivilege.SHIZUKU,
            enabled = !isAnyRequesting,
            onActionClick = { onRequestPrivilege(AndroidPrivilege.SHIZUKU) },
        )

        // 2. Dhizuku 卡片
        val dhizukuDetail = when {
            overview.dhizukuGranted -> "已授权，Owner: ${overview.dhizukuOwnerComponent ?: "已绑定"}"
            overview.dhizukuInstalledOrActive -> "已激活 Owner (${overview.dhizukuOwnerComponent ?: "未知"})，点击申请权限"
            else -> "未安装 Dhizuku 或尚未激活 Device/Profile Owner"
        }
        PrivilegeActionCard(
            title = stringResource(R.string.settings_priv_dhizuku_title),
            description = stringResource(R.string.settings_priv_dhizuku_desc),
            statusDetail = dhizukuDetail,
            badgeText = when {
                overview.dhizukuGranted -> stringResource(R.string.settings_priv_status_granted)
                overview.dhizukuInstalledOrActive -> stringResource(R.string.settings_priv_status_pending)
                else -> stringResource(R.string.settings_priv_status_inactive)
            },
            isGranted = overview.dhizukuGranted,
            actionText = stringResource(
                if (overview.dhizukuGranted) {
                    R.string.settings_priv_dhizuku_action_recheck
                } else {
                    R.string.settings_priv_dhizuku_action_request
                },
            ),
            isLoading = uiState.requestingPrivilege == AndroidPrivilege.DHIZUKU,
            enabled = !isAnyRequesting,
            onActionClick = { onRequestPrivilege(AndroidPrivilege.DHIZUKU) },
        )

        // 3. 无障碍服务卡片
        val accessibilityGranted = overview.accessibilityConnected || overview.accessibilityEnabled
        val accessibilityDetail = when {
            overview.accessibilityConnected -> "Maid 无障碍服务已连接并运行中"
            overview.accessibilityEnabled -> "系统已启用无障碍开关，等待服务实例绑定"
            else -> "需要在系统「无障碍」设置中开启「Maid 无障碍服务」"
        }
        PrivilegeActionCard(
            title = stringResource(R.string.settings_priv_accessibility_title),
            description = stringResource(R.string.settings_priv_accessibility_desc),
            statusDetail = accessibilityDetail,
            badgeText = if (accessibilityGranted) {
                stringResource(R.string.settings_priv_status_granted)
            } else {
                stringResource(R.string.settings_priv_status_inactive)
            },
            isGranted = accessibilityGranted,
            actionText = stringResource(
                if (accessibilityGranted) {
                    R.string.settings_priv_accessibility_action_recheck
                } else {
                    R.string.settings_priv_accessibility_action_open
                },
            ),
            isLoading = uiState.requestingPrivilege == AndroidPrivilege.ACCESSIBILITY,
            enabled = !isAnyRequesting,
            onActionClick = { onRequestPrivilege(AndroidPrivilege.ACCESSIBILITY) },
        )

        // 4. Root (su / KernelSU / APatch / Magisk) 卡片
        val rootManager = overview.detectedRootManager
        val rootDetail = when {
            overview.rootAvailable -> if (rootManager != null) {
                "已通过 ${rootManager.displayName} (su) 验证 UID=0 Root 权限"
            } else {
                "已通过 su 验证 UID=0 Root 权限"
            }

            rootManager != null && rootManager.requiresManualWhitelistGrant ->
                "检测到 ${rootManager.displayName}（白名单机制，不会自动弹窗），请前往其「超级用户」为 Maid 开启权限"

            uiState.hasCheckedRoot && !overview.suBinaryVisible ->
                "当前进程不可见 su（若使用 KernelSU / APatch，请在管理器「超级用户」中手动授权）"

            uiState.hasCheckedRoot ->
                "未检测到可用 su 或 Root 授权被拒绝"

            rootManager != null ->
                "检测到 ${rootManager.displayName}，点击按钮检测或申请 Root 权限"

            else ->
                "点击按钮将执行 su -c id 以申请或检测 Root 权限"
        }
        val rootBadge = when {
            overview.rootAvailable -> stringResource(R.string.settings_priv_status_granted)
            rootManager != null && rootManager.requiresManualWhitelistGrant ->
                stringResource(R.string.settings_priv_status_pending)

            uiState.hasCheckedRoot -> stringResource(R.string.settings_priv_status_inactive)
            else -> stringResource(R.string.settings_priv_status_unchecked)
        }
        val rootActionText = if (overview.rootAvailable) {
            stringResource(R.string.settings_priv_shizuku_action_recheck)
        } else {
            stringResource(R.string.settings_priv_root_action_request)
        }
        PrivilegeActionCard(
            title = stringResource(R.string.settings_priv_root_title),
            description = stringResource(R.string.settings_priv_root_desc),
            statusDetail = rootDetail,
            badgeText = rootBadge,
            isGranted = overview.rootAvailable,
            actionText = rootActionText,
            isLoading = uiState.requestingPrivilege == AndroidPrivilege.ROOT,
            enabled = !isAnyRequesting,
            onActionClick = { onRequestPrivilege(AndroidPrivilege.ROOT) },
        )

        // 5. 所有文件访问权限 (MANAGE_EXTERNAL_STORAGE) 卡片
        PrivilegeActionCard(
            title = stringResource(R.string.settings_priv_file_management_title),
            description = stringResource(R.string.settings_priv_file_management_desc),
            statusDetail = if (overview.fileManagementGranted) {
                "已授予所有文件访问权限，可直接调用 file_management 工具集管理外部存储文件"
            } else {
                "未授予所有文件访问权限，点击前往系统设置开启"
            },
            badgeText = stringResource(
                if (overview.fileManagementGranted) {
                    R.string.settings_priv_status_granted
                } else {
                    R.string.settings_priv_status_inactive
                },
            ),
            isGranted = overview.fileManagementGranted,
            actionText = stringResource(
                if (overview.fileManagementGranted) {
                    R.string.settings_priv_file_management_action_recheck
                } else {
                    R.string.settings_priv_file_management_action_open
                },
            ),
            isLoading = uiState.requestingPrivilege == AndroidPrivilege.FILE_MANAGEMENT,
            enabled = !isAnyRequesting,
            onActionClick = { onRequestPrivilege(AndroidPrivilege.FILE_MANAGEMENT) },
        )

        // 6. 应用使用情况访问权限 (PACKAGE_USAGE_STATS) 卡片
        PrivilegeActionCard(
            title = stringResource(R.string.settings_priv_usage_stats_title),
            description = stringResource(R.string.settings_priv_usage_stats_desc),
            statusDetail = if (overview.usageStatsGranted) {
                "已授予使用情况访问权限，可直接调用 usage_stats 工具集查询前台与活跃应用"
            } else {
                "未授予使用情况访问权限，点击前往系统「有权访问使用情况的应用」开启"
            },
            badgeText = stringResource(
                if (overview.usageStatsGranted) {
                    R.string.settings_priv_status_granted
                } else {
                    R.string.settings_priv_status_inactive
                },
            ),
            isGranted = overview.usageStatsGranted,
            actionText = stringResource(
                if (overview.usageStatsGranted) {
                    R.string.settings_priv_usage_stats_action_recheck
                } else {
                    R.string.settings_priv_usage_stats_action_open
                },
            ),
            isLoading = uiState.requestingPrivilege == AndroidPrivilege.USAGE_STATS,
            enabled = !isAnyRequesting,
            onActionClick = { onRequestPrivilege(AndroidPrivilege.USAGE_STATS) },
        )

        // 7. 修改系统设置权限 (WRITE_SETTINGS) 卡片
        PrivilegeActionCard(
            title = stringResource(R.string.settings_priv_write_settings_title),
            description = stringResource(R.string.settings_priv_write_settings_desc),
            statusDetail = if (overview.writeSettingsGranted) {
                "已授予修改系统设置权限，可直接调用 system_settings 工具集调节亮度和息屏超时"
            } else {
                "未授予修改系统设置权限，点击前往系统设置开启"
            },
            badgeText = stringResource(
                if (overview.writeSettingsGranted) {
                    R.string.settings_priv_status_granted
                } else {
                    R.string.settings_priv_status_inactive
                },
            ),
            isGranted = overview.writeSettingsGranted,
            actionText = stringResource(
                if (overview.writeSettingsGranted) {
                    R.string.settings_priv_write_settings_action_recheck
                } else {
                    R.string.settings_priv_write_settings_action_open
                },
            ),
            isLoading = uiState.requestingPrivilege == AndroidPrivilege.WRITE_SETTINGS,
            enabled = !isAnyRequesting,
            onActionClick = { onRequestPrivilege(AndroidPrivilege.WRITE_SETTINGS) },
        )

        // 8. 悬浮窗权限 (SYSTEM_ALERT_WINDOW) 卡片
        PrivilegeActionCard(
            title = stringResource(R.string.settings_priv_overlay_title),
            description = stringResource(R.string.settings_priv_overlay_desc),
            statusDetail = if (overview.overlayGranted) {
                "已授予悬浮窗权限（显示在其他应用上层）"
            } else {
                "未授予悬浮窗权限，点击前往系统设置开启"
            },
            badgeText = stringResource(
                if (overview.overlayGranted) {
                    R.string.settings_priv_status_granted
                } else {
                    R.string.settings_priv_status_inactive
                },
            ),
            isGranted = overview.overlayGranted,
            actionText = stringResource(
                if (overview.overlayGranted) {
                    R.string.settings_priv_overlay_action_recheck
                } else {
                    R.string.settings_priv_overlay_action_open
                },
            ),
            isLoading = uiState.requestingPrivilege == AndroidPrivilege.SYSTEM_ALERT_WINDOW,
            enabled = !isAnyRequesting,
            onActionClick = { onRequestPrivilege(AndroidPrivilege.SYSTEM_ALERT_WINDOW) },
        )

        // 9. 截图能力测试卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_priv_screenshot_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_priv_screenshot_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val shot = uiState.lastScreenshotResult
                val shotError = uiState.lastScreenshotError
                if (shot != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.settings_priv_screenshot_success,
                                shot.backend,
                                shot.width,
                                shot.height,
                                (shot.byteSize / 1024L).coerceAtLeast(1L),
                                shot.filePath,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                } else if (!shotError.isNullOrBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = shotError,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                OutlinedButton(
                    onClick = onCaptureTestScreenshot,
                    enabled = !uiState.isCapturingScreenshot,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isCapturingScreenshot) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_priv_screenshot_testing))
                    } else {
                        Text(stringResource(R.string.settings_priv_screenshot_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivilegeActionCard(
    title: String,
    description: String,
    statusDetail: String,
    badgeText: String,
    isGranted: Boolean,
    actionText: String,
    isLoading: Boolean,
    enabled: Boolean,
    onActionClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isGranted) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (isGranted) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = statusDetail,
                style = MaterialTheme.typography.bodySmall,
                color = if (isGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            Button(
                onClick = onActionClick,
                enabled = enabled && !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(actionText)
            }
        }
    }
}

@Composable
private fun ChatGptAuthContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onStartSignIn: (Boolean) -> Unit,
    onCancelSignIn: () -> Unit,
    onRefreshToken: () -> Unit,
    onSignOut: () -> Unit,
    onOpenModelConfig: () -> Unit,
) {
    val authState = uiState.chatGptAuthState
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }

    val statusText = when (authState.status) {
        ChatGptAuthStatus.SIGNED_OUT -> stringResource(R.string.settings_chatgpt_status_signed_out)
        ChatGptAuthStatus.SIGNING_IN -> stringResource(R.string.settings_chatgpt_status_signing_in)
        ChatGptAuthStatus.SIGNED_IN_READY -> stringResource(R.string.settings_chatgpt_status_ready)
        ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE -> stringResource(R.string.settings_chatgpt_status_no_scope)
        ChatGptAuthStatus.SESSION_EXPIRED -> stringResource(R.string.settings_chatgpt_status_expired)
    }

    val statusContainerColor = when (authState.status) {
        ChatGptAuthStatus.SIGNED_IN_READY -> MaterialTheme.colorScheme.secondaryContainer
        ChatGptAuthStatus.SIGNING_IN -> MaterialTheme.colorScheme.tertiaryContainer
        ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE,
        ChatGptAuthStatus.SESSION_EXPIRED -> MaterialTheme.colorScheme.errorContainer
        ChatGptAuthStatus.SIGNED_OUT -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val statusContentColor = when (authState.status) {
        ChatGptAuthStatus.SIGNED_IN_READY -> MaterialTheme.colorScheme.onSecondaryContainer
        ChatGptAuthStatus.SIGNING_IN -> MaterialTheme.colorScheme.onTertiaryContainer
        ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE,
        ChatGptAuthStatus.SESSION_EXPIRED -> MaterialTheme.colorScheme.onErrorContainer
        ChatGptAuthStatus.SIGNED_OUT -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_persona),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(10.dp)
                                .size(22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_chatgpt_card_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_chatgpt_card_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusContainerColor,
                    contentColor = statusContentColor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (authState.status) {
                            ChatGptAuthStatus.SIGNING_IN -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = statusContentColor,
                                )
                            }
                            ChatGptAuthStatus.SIGNED_IN_READY -> {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            else -> Unit
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (!authState.lastError.isNullOrBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = authState.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }

                authState.profile?.let { profile ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(
                                R.string.settings_chatgpt_account_label,
                                profile.displayLabel,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (authState.grantedScopes.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.settings_chatgpt_scopes_label,
                                    authState.grantedScopes.sorted().joinToString(", "),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                authState.issuedClientId?.takeIf { it.isNotBlank() }?.let { clientId ->
                    Text(
                        text = stringResource(R.string.settings_chatgpt_client_id_label, clientId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                when (authState.status) {
                    ChatGptAuthStatus.SIGNING_IN -> {
                        OutlinedButton(
                            onClick = onCancelSignIn,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_chatgpt_action_cancel))
                        }
                    }

                    ChatGptAuthStatus.SIGNED_OUT -> {
                        Button(
                            onClick = { onStartSignIn(false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_chatgpt_action_continue))
                        }
                    }

                    ChatGptAuthStatus.SESSION_EXPIRED -> {
                        Button(
                            onClick = { onStartSignIn(false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_chatgpt_action_relogin))
                        }
                    }

                    ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE -> {
                        Button(
                            onClick = { onStartSignIn(true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_chatgpt_action_reconsent))
                        }
                        OutlinedButton(
                            onClick = { showSignOutDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_chatgpt_action_sign_out))
                        }
                    }

                    ChatGptAuthStatus.SIGNED_IN_READY -> {
                        Button(
                            onClick = onOpenModelConfig,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_build),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_chatgpt_action_open_models))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FilledTonalButton(
                                onClick = onRefreshToken,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.settings_chatgpt_action_refresh))
                            }
                            OutlinedButton(
                                onClick = { showSignOutDialog = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.settings_chatgpt_action_sign_out))
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.settings_chatgpt_security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.settings_chatgpt_sign_out_dialog_title)) },
            text = { Text(stringResource(R.string.settings_chatgpt_sign_out_dialog_message)) },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        onSignOut()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_chatgpt_sign_out_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}

@Composable
private fun ModelSelectionContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onOpenLlmKeys: () -> Unit,
    onOpenChatGptAuth: () -> Unit,
    onSelectModelProvider: (ModelRole, ModelProviderTarget?) -> Unit,
    onSelectModelId: (ModelRole, String) -> Unit,
    onRefreshProviderModels: (LlmProvider, String?) -> Unit,
    onSaveModelConfigs: () -> Unit,
    onClearAllModelConfigs: () -> Unit,
) {
    val configuredTargets = uiState.configuredModelTargets
    val hasAnyDraftOrSaved = uiState.draftModelConfigs.values.any { it.provider != null } ||
        uiState.savedModelConfigs.values.any { it.provider != null }

    if (configuredTargets.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_key),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(14.dp)
                                .size(28.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_model_no_provider_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_model_no_provider_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onOpenLlmKeys,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_key),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_model_no_provider_action))
                    }
                    FilledTonalButton(
                        onClick = onOpenChatGptAuth,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_persona),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_model_no_provider_chatgpt_action))
                    }
                    if (hasAnyDraftOrSaved) {
                        OutlinedButton(
                            onClick = onClearAllModelConfigs,
                            enabled = !uiState.isSavingModels,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_model_clear_invalid_configs))
                        }
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.settings_model_config_header_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        if (!uiState.chatGptAuthState.isPlanUsageReady) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_model_chatgpt_promo_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenChatGptAuth) {
                        Text(stringResource(R.string.settings_model_chatgpt_promo_action))
                    }
                }
            }
        }

        ModelRole.entries.forEach { role ->
            val selection = uiState.draftModelConfigs[role] ?: RoleModelSelection()
            val selectedProvider = selection.provider
            val cacheKey = selection.providerCacheKey
            val providerModels = cacheKey?.let { uiState.remoteModelsByProvider[it] }.orEmpty()
            val isLoadingModels = cacheKey != null && cacheKey in uiState.loadingModelProviders
            val fetchError = cacheKey?.let { uiState.modelFetchErrors[it] }
            ModelRoleConfigCard(
                role = role,
                selection = selection,
                configuredTargets = configuredTargets,
                providerModels = providerModels,
                isLoadingModels = isLoadingModels,
                fetchError = fetchError,
                isSaving = uiState.isSavingModels,
                onSelectProvider = { target -> onSelectModelProvider(role, target) },
                onSelectModelId = { modelId -> onSelectModelId(role, modelId) },
                onRefreshModels = {
                    selectedProvider?.let { provider ->
                        onRefreshProviderModels(provider, selection.normalizedCustomProviderId)
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onClearAllModelConfigs,
                enabled = !uiState.isSavingModels && hasAnyDraftOrSaved,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.settings_model_clear_all))
            }

            Button(
                onClick = onSaveModelConfigs,
                enabled = !uiState.isSavingModels,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isSavingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(stringResource(R.string.settings_model_save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelRoleConfigCard(
    role: ModelRole,
    selection: RoleModelSelection,
    configuredTargets: List<ModelProviderTarget>,
    providerModels: List<LlmModelOption>,
    isLoadingModels: Boolean,
    fetchError: String?,
    isSaving: Boolean,
    onSelectProvider: (ModelProviderTarget?) -> Unit,
    onSelectModelId: (String) -> Unit,
    onRefreshModels: () -> Unit,
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val selectedProvider = selection.provider
    val selectedTarget = remember(selection.providerCacheKey, configuredTargets) {
        val cacheKey = selection.providerCacheKey ?: return@remember null
        configuredTargets.firstOrNull { it.cacheKey == cacheKey }
    }
    val selectedProviderDisplayText = when {
        selectedProvider == null -> ""
        selectedProvider == LlmProvider.CUSTOM && !selectedTarget?.customDisplayName.isNullOrBlank() ->
            "${stringResource(LlmProvider.CUSTOM.displayNameRes)} · ${selectedTarget.customDisplayName}"
        else -> stringResource(selectedProvider.displayNameRes)
    }

    val isModelEnabled = selectedProvider != null &&
        !isSaving &&
        !isLoadingModels &&
        providerModels.isNotEmpty()
    val selectedOption = providerModels.firstOrNull { it.modelId == selection.modelId }
    val selectedMetadataSummary = (selectedOption?.metadata ?: selection.modelMetadata)?.formatCompactSummary()

    LaunchedEffect(selectedProvider, isLoadingModels, providerModels) {
        if (selectedProvider == null || isLoadingModels || providerModels.isEmpty()) {
            modelMenuExpanded = false
        }
    }

    val badgeText = when {
        selection.isComplete -> stringResource(
            R.string.settings_model_status_configured,
            selectedOption?.displayName ?: selection.normalizedModelId.orEmpty(),
        )
        selection.hasPartialInput -> stringResource(R.string.settings_model_status_incomplete)
        else -> stringResource(R.string.settings_model_status_unconfigured)
    }

    val badgeContainerColor = when {
        selection.isComplete -> MaterialTheme.colorScheme.secondaryContainer
        selection.hasPartialInput -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val badgeContentColor = when {
        selection.isComplete -> MaterialTheme.colorScheme.onSecondaryContainer
        selection.hasPartialInput -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(role.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = badgeContainerColor,
                    contentColor = badgeContentColor,
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = stringResource(role.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 第一步：选择服务商 (Provider / 自定义端点档案)
            ExposedDropdownMenuBox(
                expanded = providerMenuExpanded && !isSaving,
                onExpandedChange = {
                    if (!isSaving) {
                        providerMenuExpanded = !providerMenuExpanded
                    }
                },
            ) {
                OutlinedTextField(
                    value = selectedProviderDisplayText,
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isSaving,
                    label = { Text(stringResource(R.string.settings_model_provider_label)) },
                    placeholder = { Text(stringResource(R.string.settings_model_provider_placeholder)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = providerMenuExpanded && !isSaving,
                        )
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(
                            type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            enabled = !isSaving,
                        )
                        .fillMaxWidth(),
                )

                ExposedDropdownMenu(
                    expanded = providerMenuExpanded && !isSaving,
                    onDismissRequest = { providerMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.settings_model_provider_clear),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {
                            providerMenuExpanded = false
                            modelMenuExpanded = false
                            onSelectProvider(null)
                        },
                    )
                    configuredTargets.forEach { target ->
                        val providerBadge = if (target.provider.usesApiKey) {
                            stringResource(R.string.settings_llm_provider_configured_badge)
                        } else {
                            stringResource(R.string.settings_model_provider_badge_oauth)
                        }
                        val targetLabel = if (target.provider == LlmProvider.CUSTOM && !target.customDisplayName.isNullOrBlank()) {
                            "${stringResource(LlmProvider.CUSTOM.displayNameRes)} · ${target.customDisplayName}"
                        } else {
                            stringResource(target.provider.displayNameRes)
                        }
                        DropdownMenuItem(
                            text = { Text(targetLabel) },
                            trailingIcon = {
                                Text(
                                    text = providerBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                providerMenuExpanded = false
                                onSelectProvider(target)
                            },
                        )
                    }
                }
            }

            // 第二步：仅在已选择服务商且从服务器获取到模型列表后，允许选择模型 (Model)
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded && isModelEnabled,
                onExpandedChange = {
                    if (isModelEnabled) {
                        modelMenuExpanded = !modelMenuExpanded
                    }
                },
            ) {
                OutlinedTextField(
                    value = selectedOption?.displayName ?: selection.modelId.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    enabled = isModelEnabled,
                    label = { Text(stringResource(R.string.settings_model_model_label)) },
                    placeholder = {
                        Text(
                            stringResource(
                                when {
                                    selectedProvider == null ->
                                        R.string.settings_model_model_placeholder_need_provider
                                    isLoadingModels ->
                                        R.string.settings_model_model_placeholder_loading
                                    providerModels.isEmpty() ->
                                        R.string.settings_model_model_placeholder_unavailable
                                    else ->
                                        R.string.settings_model_model_placeholder_select
                                },
                            ),
                        )
                    },
                    supportingText = if (!selectedMetadataSummary.isNullOrBlank()) {
                        {
                            Text(
                                text = selectedMetadataSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = modelMenuExpanded && isModelEnabled,
                        )
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(
                            type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            enabled = isModelEnabled,
                        )
                        .fillMaxWidth(),
                )

                if (isModelEnabled) {
                    ExposedDropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        providerModels.forEach { option ->
                            val metadataSummary = option.metadata.formatCompactSummary()
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.displayName)
                                        if (option.displayName != option.modelId) {
                                            Text(
                                                text = option.modelId,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (!metadataSummary.isNullOrBlank()) {
                                            Text(
                                                text = metadataSummary,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    modelMenuExpanded = false
                                    onSelectModelId(option.modelId)
                                },
                            )
                        }
                    }
                }
            }

            if (selectedProvider != null) {
                when {
                    isLoadingModels -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_model_loading_hint,
                                    selectedProviderDisplayText,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    !fetchError.isNullOrBlank() -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = fetchError,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onRefreshModels) {
                                    Text(stringResource(R.string.settings_model_refresh_models))
                                }
                            }
                        }
                    }

                    selectedTarget == null -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (selectedProvider.usesApiKey) {
                                    stringResource(R.string.settings_model_missing_key_hint)
                                } else {
                                    stringResource(R.string.settings_chatgpt_status_expired)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.settings_model_loaded_count,
                                    providerModels.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onRefreshModels) {
                                Text(stringResource(R.string.settings_model_refresh_models))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaManagementContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onRefreshPersonaPresets: () -> Unit,
    onOpenAddPersonaDialog: () -> Unit,
    onSelectPersonaPreset: (PersonaPreset) -> Unit,
    onDeletePersonaPreset: (PersonaPreset) -> Unit,
    onPersonaTextChanged: (String) -> Unit,
    onSavePersona: () -> Unit,
    onClearPersona: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_persona),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(10.dp)
                                .size(22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_persona_intro_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_persona_intro_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_persona_presets_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onOpenAddPersonaDialog,
                            enabled = !uiState.isSavingPersona,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_persona_add))
                        }
                        TextButton(
                            onClick = onRefreshPersonaPresets,
                            enabled = !uiState.isScanningPersonas && !uiState.isSavingPersona,
                        ) {
                            Text(stringResource(R.string.settings_persona_presets_rescan))
                        }
                    }
                }

                when {
                    uiState.isScanningPersonas -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.settings_persona_presets_scanning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    uiState.personaPresets.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.settings_persona_presets_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Text(
                            text = stringResource(
                                R.string.settings_persona_presets_count,
                                uiState.personaPresets.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.personaPresets.forEach { preset ->
                                val isSelected = uiState.draftSelectedPresetId == preset.id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectPersonaPreset(preset) },
                                    enabled = !uiState.isSavingPersona,
                                    label = { Text(preset.name) },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    trailingIcon = if (preset.isCustom) {
                                        {
                                            IconButton(
                                                onClick = { onDeletePersonaPreset(preset) },
                                                enabled = !uiState.isSavingPersona,
                                                modifier = Modifier.size(18.dp),
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_close),
                                                    contentDescription = stringResource(R.string.settings_persona_delete_custom),
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_persona_editor_title),
                    style = MaterialTheme.typography.titleSmall,
                )

                val selectedPreset = uiState.draftSelectedPresetId?.let { id ->
                    uiState.personaPresets.firstOrNull { it.id == id }
                }
                val statusText = when {
                    selectedPreset != null -> stringResource(
                        R.string.settings_persona_status_preset,
                        selectedPreset.name,
                    )
                    uiState.draftPersonaText.isNotBlank() -> stringResource(
                        R.string.settings_persona_status_custom,
                    )
                    else -> stringResource(R.string.settings_persona_status_default)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = uiState.draftPersonaText,
                    onValueChange = onPersonaTextChanged,
                    enabled = !uiState.isSavingPersona,
                    label = { Text(stringResource(R.string.settings_persona_editor_label)) },
                    placeholder = {
                        Text(stringResource(R.string.settings_persona_editor_placeholder))
                    },
                    supportingText = {
                        Text(stringResource(R.string.settings_persona_editor_helper))
                    },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onSavePersona()
                        },
                        enabled = !uiState.isSavingPersona,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (uiState.isSavingPersona) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.settings_persona_save))
                    }

                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            onClearPersona()
                        },
                        enabled = !uiState.isSavingPersona &&
                            (uiState.draftPersonaText.isNotBlank() || uiState.savedPersonaConfig.isConfigured),
                    ) {
                        Text(stringResource(R.string.settings_persona_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPersonaDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val canConfirm = name.trim().isNotEmpty() && content.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_persona_add_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_persona_add_name_label)) },
                    placeholder = { Text(stringResource(R.string.settings_persona_add_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.settings_persona_add_content_label)) },
                    placeholder = { Text(stringResource(R.string.settings_persona_editor_placeholder)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, content) },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.settings_persona_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun ModelSafetyContent(
    safetyConfig: ModelSafetyConfig,
    contentPadding: PaddingValues,
    onApplySafetyLevel: (ModelSafetyLevel) -> Unit,
    onUpdateSafetyConfig: ((ModelSafetyConfig) -> ModelSafetyConfig) -> Unit,
    onResetSafetyConfig: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_model_safety_intro_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_model_safety_intro_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_model_safety_level_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (safetyConfig.safetyLevel == ModelSafetyLevel.CUSTOM) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_model_safety_level_custom),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        ModelSafetyLevel.STRICT to R.string.settings_model_safety_level_strict,
                        ModelSafetyLevel.BALANCED to R.string.settings_model_safety_level_balanced,
                        ModelSafetyLevel.UNRESTRICTED to R.string.settings_model_safety_level_unrestricted,
                    ).forEach { (level, labelRes) ->
                        val selected = safetyConfig.safetyLevel == level
                        FilterChip(
                            selected = selected,
                            onClick = { onApplySafetyLevel(level) },
                            label = { Text(stringResource(labelRes)) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

                val levelDescRes = when (safetyConfig.safetyLevel) {
                    ModelSafetyLevel.STRICT -> R.string.settings_model_safety_level_strict_desc
                    ModelSafetyLevel.BALANCED -> R.string.settings_model_safety_level_balanced_desc
                    ModelSafetyLevel.UNRESTRICTED -> R.string.settings_model_safety_level_unrestricted_desc
                    ModelSafetyLevel.CUSTOM -> R.string.settings_model_safety_level_balanced_desc
                }
                if (safetyConfig.safetyLevel != ModelSafetyLevel.CUSTOM) {
                    Text(
                        text = stringResource(levelDescRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (safetyConfig.hasHighRiskEnabled) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.settings_model_safety_high_risk_warning),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_model_safety_toggles_title),
                    style = MaterialTheme.typography.titleSmall,
                )

                SafetyToggleRow(
                    title = stringResource(R.string.settings_model_safety_shell_title),
                    description = stringResource(R.string.settings_model_safety_shell_desc),
                    checked = safetyConfig.allowShellExecution,
                    onCheckedChange = { checked ->
                        onUpdateSafetyConfig { it.copy(allowShellExecution = checked) }
                    },
                )
                HorizontalDivider()
                SafetyToggleRow(
                    title = stringResource(R.string.settings_model_safety_block_dangerous_title),
                    description = stringResource(R.string.settings_model_safety_block_dangerous_desc),
                    checked = safetyConfig.blockDangerousShellCommands,
                    onCheckedChange = { checked ->
                        onUpdateSafetyConfig { it.copy(blockDangerousShellCommands = checked) }
                    },
                )
                HorizontalDivider()
                SafetyToggleRow(
                    title = stringResource(R.string.settings_model_safety_app_lifecycle_title),
                    description = stringResource(R.string.settings_model_safety_app_lifecycle_desc),
                    checked = safetyConfig.allowAppLifecycleControl,
                    onCheckedChange = { checked ->
                        onUpdateSafetyConfig { it.copy(allowAppLifecycleControl = checked) }
                    },
                )
                HorizontalDivider()
                SafetyToggleRow(
                    title = stringResource(R.string.settings_model_safety_display_title),
                    description = stringResource(R.string.settings_model_safety_display_desc),
                    checked = safetyConfig.allowDisplayModification,
                    onCheckedChange = { checked ->
                        onUpdateSafetyConfig { it.copy(allowDisplayModification = checked) }
                    },
                )
                HorizontalDivider()
                SafetyToggleRow(
                    title = stringResource(R.string.settings_model_safety_device_policy_title),
                    description = stringResource(R.string.settings_model_safety_device_policy_desc),
                    checked = safetyConfig.allowDevicePolicyControl,
                    onCheckedChange = { checked ->
                        onUpdateSafetyConfig { it.copy(allowDevicePolicyControl = checked) }
                    },
                )
                HorizontalDivider()
                SafetyToggleRow(
                    title = stringResource(R.string.settings_model_safety_root_title),
                    description = stringResource(R.string.settings_model_safety_root_desc),
                    checked = safetyConfig.allowRootOperations,
                    onCheckedChange = { checked ->
                        onUpdateSafetyConfig { it.copy(allowRootOperations = checked) }
                    },
                )
                HorizontalDivider()
                SafetyToggleRow(
                    title = stringResource(R.string.settings_model_safety_llm_api_key_title),
                    description = stringResource(R.string.settings_model_safety_llm_api_key_desc),
                    checked = safetyConfig.allowLocalLlmApiKeyAccess,
                    onCheckedChange = { checked ->
                        onUpdateSafetyConfig { it.copy(allowLocalLlmApiKeyAccess = checked) }
                    },
                )
            }
        }

        OutlinedButton(
            onClick = onResetSafetyConfig,
            enabled = safetyConfig != ModelSafetyConfig.DEFAULT,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Text(stringResource(R.string.settings_model_safety_reset))
        }
    }
}

@Composable
private fun AgentStepsContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onChatStepInputChanged: (String) -> Unit,
    onPlannerStepInputChanged: (String) -> Unit,
    onPlannerCheckpointInputChanged: (String) -> Unit,
    onDeviceActionStepInputChanged: (String) -> Unit,
    onToggleCrossChatMemory: (Boolean) -> Unit,
    onSaveAgentStepConfigs: () -> Unit,
    onResetAgentStepConfigs: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val chatParsed = uiState.draftChatStepInput.toIntOrNull()
    val plannerStepParsed = uiState.draftPlannerStepInput.toIntOrNull()
    val plannerCheckpointParsed = uiState.draftPlannerCheckpointInput.toIntOrNull()
    val deviceParsed = uiState.draftDeviceActionStepInput.toIntOrNull()

    val isChatValid = chatParsed != null && chatParsed >= AgentStepConfig.MIN_AGENT_ITERATIONS
    val isPlannerStepValid = plannerStepParsed != null &&
        plannerStepParsed >= AgentStepConfig.MIN_AGENT_ITERATIONS
    val isPlannerCheckpointValid = plannerCheckpointParsed != null &&
        plannerCheckpointParsed >= AgentStepConfig.MIN_CHECKPOINTS
    val isDeviceValid = deviceParsed != null && deviceParsed >= AgentStepConfig.MIN_AGENT_ITERATIONS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_agent_steps_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.settings_agent_steps_intro_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SafetyToggleRow(
                    title = stringResource(R.string.settings_cross_chat_memory_title),
                    description = stringResource(R.string.settings_cross_chat_memory_desc),
                    checked = uiState.savedAgentStepConfig.enableCrossChatMemory,
                    onCheckedChange = onToggleCrossChatMemory,
                )
                HorizontalDivider()
                AgentStepFieldEditor(
                    title = stringResource(R.string.settings_agent_steps_chat_title),
                    description = stringResource(R.string.settings_agent_steps_chat_desc),
                    estimateText = chatParsed?.let {
                        stringResource(
                            R.string.settings_agent_steps_chat_estimate,
                            AgentStepConfig.estimateChatToolCalls(it),
                        )
                    },
                    valueText = uiState.draftChatStepInput,
                    isValid = isChatValid,
                    min = AgentStepConfig.MIN_AGENT_ITERATIONS,
                    stepDelta = 8,
                    presets = listOf(32, 64, 128, 256, 1000),
                    enabled = !uiState.isSavingAgentSteps,
                    onValueChange = onChatStepInputChanged,
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_agent_steps_planner_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                AgentStepFieldEditor(
                    title = stringResource(R.string.settings_agent_steps_planner_step_label),
                    description = stringResource(R.string.settings_agent_steps_planner_step_desc),
                    estimateText = plannerStepParsed?.let {
                        stringResource(
                            R.string.settings_agent_steps_planner_step_estimate,
                            AgentStepConfig.estimatePlannerToolCallsPerCheckpoint(it),
                        )
                    },
                    valueText = uiState.draftPlannerStepInput,
                    isValid = isPlannerStepValid,
                    min = AgentStepConfig.MIN_AGENT_ITERATIONS,
                    stepDelta = 8,
                    presets = listOf(32, 64, 128, 256, 1000),
                    enabled = !uiState.isSavingAgentSteps,
                    onValueChange = onPlannerStepInputChanged,
                )
                HorizontalDivider()
                AgentStepFieldEditor(
                    title = stringResource(R.string.settings_agent_steps_planner_checkpoint_label),
                    description = stringResource(R.string.settings_agent_steps_planner_checkpoint_desc),
                    estimateText = null,
                    valueText = uiState.draftPlannerCheckpointInput,
                    isValid = isPlannerCheckpointValid,
                    min = AgentStepConfig.MIN_CHECKPOINTS,
                    stepDelta = 2,
                    presets = listOf(8, 16, 32, 64, 200),
                    enabled = !uiState.isSavingAgentSteps,
                    onValueChange = onPlannerCheckpointInputChanged,
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgentStepFieldEditor(
                    title = stringResource(R.string.settings_agent_steps_device_title),
                    description = stringResource(R.string.settings_agent_steps_device_desc),
                    estimateText = deviceParsed?.let {
                        stringResource(
                            R.string.settings_agent_steps_device_estimate,
                            AgentStepConfig.estimateDeviceActionToolCalls(it),
                        )
                    },
                    valueText = uiState.draftDeviceActionStepInput,
                    isValid = isDeviceValid,
                    min = AgentStepConfig.MIN_AGENT_ITERATIONS,
                    stepDelta = 12,
                    presets = listOf(48, 96, 160, 300, 1000),
                    enabled = !uiState.isSavingAgentSteps,
                    onValueChange = onDeviceActionStepInputChanged,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    onResetAgentStepConfigs()
                },
                enabled = !uiState.isSavingAgentSteps,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_agent_steps_reset))
            }
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSaveAgentStepConfigs()
                },
                enabled = !uiState.isSavingAgentSteps &&
                    isChatValid &&
                    isPlannerStepValid &&
                    isPlannerCheckpointValid &&
                    isDeviceValid,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_agent_steps_save))
            }
        }
    }
}

@Composable
private fun AgentStepFieldEditor(
    title: String,
    description: String,
    estimateText: String?,
    valueText: String,
    isValid: Boolean,
    min: Int,
    stepDelta: Int,
    presets: List<Int>,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val currentInt = valueText.toIntOrNull() ?: min
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!estimateText.isNullOrBlank()) {
            Text(
                text = estimateText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    val next = (currentInt - stepDelta).coerceAtLeast(min)
                    onValueChange(next.toString())
                },
                enabled = enabled && currentInt > min,
            ) {
                Text("-$stepDelta")
            }
            OutlinedTextField(
                value = valueText,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                isError = !isValid,
                supportingText = {
                    Text(stringResource(R.string.settings_agent_steps_range_hint, min))
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = {
                    val next = (currentInt.toLong() + stepDelta)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                    onValueChange(next.toString())
                },
                enabled = enabled && currentInt < Int.MAX_VALUE,
            ) {
                Text("+$stepDelta")
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                FilterChip(
                    selected = valueText.toIntOrNull() == preset,
                    onClick = { onValueChange(preset.toString()) },
                    enabled = enabled,
                    label = { Text(preset.toString()) },
                )
            }
        }
    }
}

@Composable
private fun SafetyToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private enum class ClearDataConfirmTarget {
    CHAT_ONLY,
    API_KEYS_ONLY,
    CHATGPT_ONLY,
    ROOTFS_ONLY,
    SELECTED,
}

@Composable
private fun ClearDataContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onClearChatRecordsData: () -> Unit,
    onClearAllApiKeysData: () -> Unit,
    onClearChatGptSessionData: () -> Unit = {},
    onResetRootfsData: () -> Unit = {},
    onImportRootfsArchive: (String) -> Unit = {},
    onClearCustomRootfsArchive: () -> Unit = {},
    onClearSelectedData: (Boolean, Boolean, Boolean, Boolean) -> Unit,
) {
    var clearChatChecked by rememberSaveable { mutableStateOf(true) }
    var clearApiKeysChecked by rememberSaveable { mutableStateOf(false) }
    var clearChatGptChecked by rememberSaveable { mutableStateOf(false) }
    var resetRootfsChecked by rememberSaveable { mutableStateOf(false) }
    var confirmTarget by remember { mutableStateOf<ClearDataConfirmTarget?>(null) }

    val rootfsArchivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportRootfsArchive(uri.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_clear_data_intro_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_clear_data_intro_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 1. 单独清除聊天记录卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = clearChatChecked,
                        onCheckedChange = { clearChatChecked = it },
                        enabled = !uiState.isBusy,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_clear_data_option_chat_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_clear_data_option_chat_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = { confirmTarget = ClearDataConfirmTarget.CHAT_ONLY },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_clear_data_action_chat_only))
                }
            }
        }

        // 2. 单独清除 API Key 卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = clearApiKeysChecked,
                        onCheckedChange = { clearApiKeysChecked = it },
                        enabled = !uiState.isBusy,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_clear_data_option_api_keys_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_clear_data_option_api_keys_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = { confirmTarget = ClearDataConfirmTarget.API_KEYS_ONLY },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_clear_data_action_api_keys_only))
                }
            }
        }

        // 3. 单独清除 Sign in with ChatGPT 会话卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = clearChatGptChecked,
                        onCheckedChange = { clearChatGptChecked = it },
                        enabled = !uiState.isBusy,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_clear_data_option_chatgpt_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_clear_data_option_chatgpt_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = { confirmTarget = ClearDataConfirmTarget.CHATGPT_ONLY },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_clear_data_action_chatgpt_only))
                }
            }
        }

        // 4. PRoot Rootfs 初始化源设置与重置卡片
        val rootfsStatus = uiState.rootfsArchiveStatus
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = resetRootfsChecked,
                        onCheckedChange = { resetRootfsChecked = it },
                        enabled = !uiState.isBusy,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_clear_data_option_rootfs_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_clear_data_option_rootfs_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 当前初始化归档文件来源状态展示
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (rootfsStatus.activeSourceLabel.isNotBlank()) {
                            Text(
                                text = stringResource(
                                    R.string.settings_clear_data_rootfs_status_source,
                                    rootfsStatus.activeSourceLabel,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (rootfsStatus.hasCustomArchive) {
                            Text(
                                text = stringResource(
                                    R.string.settings_clear_data_rootfs_status_custom,
                                    rootfsStatus.customArchiveName ?: "custom_rootfs.tar.gz",
                                    (rootfsStatus.customArchiveByteSize / 1024L).coerceAtLeast(1L),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val bundledPath = rootfsStatus.bundledAssetPath
                        Text(
                            text = if (rootfsStatus.hasBundledAssetArchive && !bundledPath.isNullOrBlank()) {
                                stringResource(
                                    R.string.settings_clear_data_rootfs_status_bundled,
                                    bundledPath,
                                )
                            } else {
                                stringResource(R.string.settings_clear_data_rootfs_status_no_bundled)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 选择本地 Rootfs 归档文件 (.tar.gz / .tgz / .tar) 作为初始化文件
                FilledTonalButton(
                    onClick = {
                        rootfsArchivePickerLauncher.launch(
                            arrayOf(
                                "application/gzip",
                                "application/x-gzip",
                                "application/x-gtar",
                                "application/x-tar",
                                "application/x-compressed-tar",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_attach_file),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_clear_data_action_import_rootfs))
                }

                if (rootfsStatus.hasCustomArchive) {
                    TextButton(
                        onClick = onClearCustomRootfsArchive,
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_clear_data_action_clear_custom_rootfs))
                    }
                }

                OutlinedButton(
                    onClick = { confirmTarget = ClearDataConfirmTarget.ROOTFS_ONLY },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_clear_data_action_rootfs_only))
                }
            }
        }

        // 5. 按勾选批量清除 / 重置按钮
        Button(
            onClick = { confirmTarget = ClearDataConfirmTarget.SELECTED },
            enabled = !uiState.isBusy && (clearChatChecked || clearApiKeysChecked || clearChatGptChecked || resetRootfsChecked),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            if (uiState.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onError,
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(stringResource(R.string.settings_clear_data_action_selected))
        }
    }

    val activeTarget = confirmTarget
    if (activeTarget != null) {
        val messageRes = when (activeTarget) {
            ClearDataConfirmTarget.CHAT_ONLY -> R.string.settings_clear_data_confirm_chat_only
            ClearDataConfirmTarget.API_KEYS_ONLY -> R.string.settings_clear_data_confirm_api_keys_only
            ClearDataConfirmTarget.CHATGPT_ONLY -> R.string.settings_clear_data_confirm_chatgpt_only
            ClearDataConfirmTarget.ROOTFS_ONLY -> R.string.settings_clear_data_confirm_rootfs_only
            ClearDataConfirmTarget.SELECTED -> when {
                listOf(clearChatChecked, clearApiKeysChecked, clearChatGptChecked, resetRootfsChecked).count { it } > 1 ->
                    R.string.settings_clear_data_confirm_both
                clearChatChecked -> R.string.settings_clear_data_confirm_chat_only
                clearApiKeysChecked -> R.string.settings_clear_data_confirm_api_keys_only
                clearChatGptChecked -> R.string.settings_clear_data_confirm_chatgpt_only
                else -> R.string.settings_clear_data_confirm_rootfs_only
            }
        }
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text(stringResource(R.string.settings_clear_data_confirm_dialog_title)) },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmTarget = null
                        when (activeTarget) {
                            ClearDataConfirmTarget.CHAT_ONLY -> onClearChatRecordsData()
                            ClearDataConfirmTarget.API_KEYS_ONLY -> onClearAllApiKeysData()
                            ClearDataConfirmTarget.CHATGPT_ONLY -> onClearChatGptSessionData()
                            ClearDataConfirmTarget.ROOTFS_ONLY -> onResetRootfsData()
                            ClearDataConfirmTarget.SELECTED -> onClearSelectedData(
                                clearChatChecked,
                                clearApiKeysChecked,
                                clearChatGptChecked,
                                resetRootfsChecked,
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.settings_clear_data_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Preview(showBackground = true, name = "Settings Home - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Settings Home - Dark")
@Composable
private fun SettingsHomePreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.HOME,
            uiState = SettingsUiState(
                selectedProvider = LlmProvider.OPENAI,
                storedKeys = mapOf(
                    LlmProvider.OPENAI to "sk-proj-1234567890abcdef",
                    LlmProvider.DEEPSEEK to "sk-deepseek-99998888",
                ),
                savedModelConfigs = mapOf(
                    ModelRole.FAST_CHEAP to RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini"),
                    ModelRole.COMPLEX_PLANNING to RoleModelSelection(LlmProvider.OPENAI, "gpt-5"),
                ),
                isLoaded = true,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Preview(showBackground = true, name = "LLM Keys - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "LLM Keys - Dark")
@Composable
private fun LlmKeysScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.LLM_KEYS,
            uiState = SettingsUiState(
                selectedProvider = LlmProvider.OPENAI,
                inputText = "sk-proj-1234567890abcdef",
                storedKeys = mapOf(
                    LlmProvider.OPENAI to "sk-proj-1234567890abcdef",
                    LlmProvider.DEEPSEEK to "sk-deepseek-99998888",
                ),
                isLoaded = true,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Selection - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Model Selection - Dark")
@Composable
private fun ModelSelectionScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.MODEL_CONFIG,
            uiState = SettingsUiState(
                storedKeys = mapOf(
                    LlmProvider.OPENAI to "sk-proj-1234567890abcdef",
                    LlmProvider.ANTHROPIC to "sk-ant-example",
                    LlmProvider.GEMINI to "AIzaSyExampleKey",
                ),
                remoteModelsByProvider = mapOf(
                    ModelProviderTarget.providerCacheKey(LlmProvider.OPENAI) to listOf(
                        LlmModelOption(LlmProvider.OPENAI, "gpt-4o-mini"),
                        LlmModelOption(LlmProvider.OPENAI, "gpt-5"),
                    ),
                    ModelProviderTarget.providerCacheKey(LlmProvider.GEMINI) to listOf(
                        LlmModelOption(LlmProvider.GEMINI, "gemini-2.5-flash", "Gemini 2.5 Flash"),
                    ),
                ),
                loadingModelProviders = setOf(ModelProviderTarget.providerCacheKey(LlmProvider.ANTHROPIC)),
                draftModelConfigs = mapOf(
                    ModelRole.FAST_CHEAP to RoleModelSelection(LlmProvider.OPENAI, "gpt-4o-mini"),
                    ModelRole.COMPLEX_PLANNING to RoleModelSelection(LlmProvider.ANTHROPIC, null),
                    ModelRole.FAST_VISION to RoleModelSelection(LlmProvider.GEMINI, "gemini-2.5-flash"),
                    ModelRole.COMPLEX_VISION to RoleModelSelection(),
                ),
                isLoaded = true,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Selection Empty - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Model Selection Empty - Dark")
@Composable
private fun ModelSelectionEmptyScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.MODEL_CONFIG,
            uiState = SettingsUiState(
                storedKeys = emptyMap(),
                isLoaded = true,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Preview(showBackground = true, name = "Persona - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Persona - Dark")
@Composable
private fun PersonaScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.PERSONA,
            uiState = SettingsUiState(
                isLoaded = true,
                personaPresets = listOf(
                    PersonaPreset(
                        id = "gentle_maid.md",
                        name = "gentle_maid",
                        assetPath = "persona/gentle_maid.md",
                        content = "你是一位温柔体贴、说话轻声细语的女仆助手。",
                    ),
                    PersonaPreset(
                        id = "concise_partner.txt",
                        name = "concise_partner",
                        assetPath = "persona/concise_partner.txt",
                        content = "语气干练直接，性格冷静可靠。",
                    ),
                ),
                draftSelectedPresetId = "gentle_maid.md",
                draftPersonaText = "你是一位温柔体贴、说话轻声细语的女仆助手。",
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Preview(showBackground = true, name = "Privileges - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Privileges - Dark")
@Composable
private fun PrivilegesScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.PRIVILEGES,
            uiState = SettingsUiState(
                isLoaded = true,
                unlockedToolSetCount = 5,
                totalToolSetCount = 10,
                privilegeOverview = PrivilegeOverview(
                    shizukuStatus = ShizukuStatus(
                        isManagerInstalled = true,
                        isBinderAlive = true,
                        permissionGranted = true,
                        serverInfo = ShizukuServerInfo(
                            uid = 2000,
                            apiVersion = 13,
                            latestSupportedVersion = 13,
                            seLinuxContext = "u:r:shell:s0",
                            isPreV11 = false,
                        ),
                    ),
                    accessibilityConnected = true,
                    accessibilityEnabled = true,
                    availablePrivileges = setOf(
                        AndroidPrivilege.SHIZUKU,
                        AndroidPrivilege.ACCESSIBILITY,
                    ),
                ),
                lastScreenshotResult = ScreenCaptureResult(
                    filePath = "cache/screenshots/shot_1.jpg",
                    mimeType = "image/jpeg",
                    width = 720,
                    height = 1280,
                    byteSize = 184_320L,
                    backend = "accessibility",
                ),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Safety - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Model Safety - Dark")
@Composable
private fun ModelSafetyScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.MODEL_SAFETY,
            uiState = SettingsUiState(
                safetyConfig = ModelSafetyConfig.BALANCED,
                isLoaded = true,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Preview(showBackground = true, name = "Agent Steps - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Agent Steps - Dark")
@Composable
private fun AgentStepsScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.AGENT_STEPS,
            uiState = SettingsUiState(
                savedAgentStepConfig = AgentStepConfig.DEFAULT,
                isLoaded = true,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Composable
private fun TinyModelSelectionContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onRefreshTinyModels: () -> Unit,
    onSelectTinyModel: (TinyModelOption) -> Unit,
    onSelectTinyModelBackend: (TinyModelBackendPreference) -> Unit,
    onImportTinyModel: (String) -> Unit,
    onDeleteImportedTinyModel: (TinyModelOption) -> Unit,
    onPreloadTinyModel: () -> Unit,
    onUnloadTinyModel: () -> Unit,
) {
    val tinyModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportTinyModel(uri.toString())
        }
    }
    var pendingDeleteOption by remember { mutableStateOf<TinyModelOption?>(null) }

    val selectedOption = uiState.selectedTinyModelOption
    val runtimeState = uiState.tinyModelRuntimeState
    val isRuntimeBusy = runtimeState is TinyModelState.Preparing ||
        runtimeState is TinyModelState.Loading ||
        runtimeState is TinyModelState.Busy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_tiny_model_intro_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_tiny_model_intro_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_tiny_model_list_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (uiState.isScanningTinyModels || uiState.isImportingTinyModel) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }

                if (uiState.tinyModelOptions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_tiny_model_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    uiState.tinyModelOptions.forEachIndexed { index, option ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        val isSelected = selectedOption?.optionKey == option.optionKey
                        TinyModelOptionRow(
                            option = option,
                            selected = isSelected,
                            enabled = !isRuntimeBusy && !uiState.isImportingTinyModel,
                            onSelect = { onSelectTinyModel(option) },
                            onRequestDelete = if (option.sourceType == TinyModelSourceType.IMPORTED_LOCAL) {
                                { pendingDeleteOption = option }
                            } else {
                                null
                            },
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { tinyModelPickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !uiState.isImportingTinyModel && !isRuntimeBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.isImportingTinyModel) {
                                    R.string.settings_tiny_model_importing
                                } else {
                                    R.string.settings_tiny_model_import_action
                                },
                            ),
                        )
                    }
                    OutlinedButton(
                        onClick = onRefreshTinyModels,
                        enabled = !uiState.isScanningTinyModels && !uiState.isImportingTinyModel,
                    ) {
                        Text(stringResource(R.string.settings_tiny_model_rescan))
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_tiny_model_backend_title),
                    style = MaterialTheme.typography.titleSmall,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        TinyModelBackendPreference.AUTO to R.string.settings_tiny_model_backend_auto,
                        TinyModelBackendPreference.CPU to R.string.settings_tiny_model_backend_cpu,
                        TinyModelBackendPreference.GPU to R.string.settings_tiny_model_backend_gpu,
                    ).forEach { (pref, labelRes) ->
                        val selected = uiState.tinyModelSelection.backendPreference == pref
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectTinyModelBackend(pref) },
                            enabled = !isRuntimeBusy,
                            label = { Text(stringResource(labelRes)) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.settings_tiny_model_backend_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_tiny_model_runtime_title),
                    style = MaterialTheme.typography.titleSmall,
                )

                val stateLabel = when (runtimeState) {
                    TinyModelState.Uninitialized -> stringResource(R.string.settings_tiny_model_state_uninitialized)
                    is TinyModelState.ModelMissing -> stringResource(
                        R.string.settings_tiny_model_state_missing,
                        runtimeState.spec.fileName,
                    )
                    is TinyModelState.Preparing -> {
                        val progress = if (runtimeState.totalBytes > 0L) {
                            "${formatByteSize(runtimeState.bytesCopied)} / ${formatByteSize(runtimeState.totalBytes)}"
                        } else {
                            formatByteSize(runtimeState.bytesCopied)
                        }
                        stringResource(R.string.settings_tiny_model_state_preparing, progress)
                    }
                    is TinyModelState.Loading -> stringResource(
                        R.string.settings_tiny_model_state_loading,
                        runtimeState.backend.name,
                    )
                    is TinyModelState.Ready -> stringResource(
                        R.string.settings_tiny_model_state_ready,
                        runtimeState.activeBackend.name,
                    )
                    is TinyModelState.Busy -> stringResource(
                        R.string.settings_tiny_model_state_busy,
                        runtimeState.activeBackend.name,
                    )
                    is TinyModelState.Error -> stringResource(
                        R.string.settings_tiny_model_state_error,
                        runtimeState.message,
                    )
                    TinyModelState.Closed -> stringResource(R.string.settings_tiny_model_state_closed)
                }

                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (runtimeState is TinyModelState.Error || runtimeState is TinyModelState.ModelMissing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onPreloadTinyModel,
                        enabled = !isRuntimeBusy && uiState.tinyModelOptions.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_tiny_model_action_preload))
                    }
                    OutlinedButton(
                        onClick = onUnloadTinyModel,
                        enabled = runtimeState is TinyModelState.Ready || runtimeState is TinyModelState.Error,
                    ) {
                        Text(stringResource(R.string.settings_tiny_model_action_unload))
                    }
                }
            }
        }
    }

    val toDelete = pendingDeleteOption
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteOption = null },
            title = { Text(stringResource(R.string.settings_tiny_model_delete_imported)) },
            text = { Text(toDelete.spec.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteOption = null
                        onDeleteImportedTinyModel(toDelete)
                    },
                ) {
                    Text(stringResource(R.string.settings_clear_data_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteOption = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun TinyModelOptionRow(
    option: TinyModelOption,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRequestDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = if (enabled) onSelect else null,
            enabled = enabled,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = option.spec.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = option.sourceLocation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TinyModelBadge(
                    text = stringResource(
                        if (option.sourceType == TinyModelSourceType.BUNDLED_ASSET) {
                            R.string.settings_tiny_model_badge_bundled
                        } else {
                            R.string.settings_tiny_model_badge_imported
                        },
                    ),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                option.sizeBytes?.takeIf { it > 0L }?.let { bytes ->
                    TinyModelBadge(
                        text = formatByteSize(bytes),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TinyModelBadge(
                    text = stringResource(
                        if (option.spec.supportsGpu) {
                            R.string.settings_tiny_model_badge_gpu
                        } else {
                            R.string.settings_tiny_model_badge_cpu
                        },
                    ),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                TinyModelBadge(
                    text = stringResource(
                        if (option.isExtracted) {
                            R.string.settings_tiny_model_badge_extracted
                        } else {
                            R.string.settings_tiny_model_badge_pending_extract
                        },
                    ),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        if (onRequestDelete != null) {
            IconButton(
                onClick = onRequestDelete,
                enabled = enabled,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.settings_tiny_model_delete_imported),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TinyModelBadge(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private data class AcknowledgementEntry(
    val title: String,
    val subtitle: String,
    val url: String,
)

private val MODEL_ACKNOWLEDGEMENTS: List<AcknowledgementEntry> = listOf(
    AcknowledgementEntry(
        title = "s3nh / LFM2.5-350M-abliterated",
        subtitle = "内置端侧 TinyModel 模型来源（LFM2.5-350M-abliterated，转换用于 LiteRT-LM 本地推理）",
        url = "https://huggingface.co/s3nh/LFM2.5-350M-abliterated",
    ),
    AcknowledgementEntry(
        title = "Liquid AI · LFM2.5",
        subtitle = "面向端侧设备优化的高效混合架构开源基础语言模型系列",
        url = "https://huggingface.co/LiquidAI",
    ),
)

private val PROJECT_ACKNOWLEDGEMENTS: List<AcknowledgementEntry> = listOf(
    AcknowledgementEntry(
        title = "Google AI Edge · LiteRT-LM",
        subtitle = "Android 端侧大语言模型高性能推理运行时",
        url = "https://github.com/google-ai-edge/LiteRT-LM",
    ),
    AcknowledgementEntry(
        title = "JetBrains · Koog",
        subtitle = "Kotlin 多智能体编排与工具调用框架",
        url = "https://github.com/JetBrains/koog",
    ),
    AcknowledgementEntry(
        title = "PRoot / Termux",
        subtitle = "免 Root 用户态 Linux 容器环境运行支持",
        url = "https://github.com/termux/proot",
    ),
    AcknowledgementEntry(
        title = "Shizuku / Dhizuku",
        subtitle = "Android 高权限系统 API 与设备管理能力桥接框架",
        url = "https://github.com/RikkaApps/Shizuku",
    ),
)

@Composable
private fun AcknowledgementsContent(
    contentPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_acknowledgements_intro_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_acknowledgements_intro_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        AcknowledgementGroupCard(
            sectionTitle = stringResource(R.string.settings_acknowledgements_section_models),
            entries = MODEL_ACKNOWLEDGEMENTS,
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        )

        AcknowledgementGroupCard(
            sectionTitle = stringResource(R.string.settings_acknowledgements_section_projects),
            entries = PROJECT_ACKNOWLEDGEMENTS,
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun AcknowledgementGroupCard(
    sectionTitle: String,
    entries: List<AcknowledgementEntry>,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = sectionTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider()
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUrl(entry.url) }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = entry.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entry.url,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun formatByteSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f GB", gb)
}

@Preview(showBackground = true, name = "TinyModel Selection - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "TinyModel Selection - Dark")
@Composable
private fun TinyModelSelectionScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.TINY_MODEL,
            uiState = SettingsUiState(
                isLoaded = true,
                tinyModelOptions = listOf(
                    TinyModelOption(
                        spec = TinyModelCatalog.LFM2_5_350M_ABLITERATED_INT4,
                        sourceType = TinyModelSourceType.BUNDLED_ASSET,
                        sourceLocation = "assets/models/LFM2.5-350M-abliterated_int4.litertlm",
                        sizeBytes = 248_500_000L,
                        isExtracted = true,
                    ),
                ),
                tinyModelSelection = TinyModelSelection(
                    selectedModelId = TinyModelCatalog.LFM2_5_350M_ABLITERATED_INT4.id,
                    selectedAssetPath = TinyModelCatalog.LFM2_5_350M_ABLITERATED_INT4.assetPath,
                ),
                tinyModelRuntimeState = TinyModelState.Uninitialized,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Preview(showBackground = true, name = "Acknowledgements - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Acknowledgements - Dark")
@Composable
private fun AcknowledgementsScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.ACKNOWLEDGEMENTS,
            uiState = SettingsUiState(isLoaded = true),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}

@Composable
private fun SearchSettingsContent(
    uiState: SettingsUiState,
    contentPadding: PaddingValues,
    onSelectSearxngMode: (SearxngMode) -> Unit,
    onSaveSearxngBaseUrl: (String) -> Unit,
    onSaveLocalProotSearxngPort: (String) -> Unit,
    onSetLocalProotSearxngAutoStart: (Boolean) -> Unit,
    onInstallProotSearxng: () -> Unit,
    onStartProotSearxng: () -> Unit,
    onStopProotSearxng: () -> Unit,
    onUninstallProotSearxng: () -> Unit,
    onProbeAllSearchEngines: () -> Unit,
) {
    val config = uiState.searchSettingsConfig
    val prootStatus = uiState.prootSearxngStatus
    var externalUrlDraft by rememberSaveable(config.searxngBaseUrl) {
        mutableStateOf(config.searxngBaseUrl)
    }
    var prootPortDraft by rememberSaveable(config.safeLocalProotPort) {
        mutableStateOf(config.safeLocalProotPort.toString())
    }
    var showUninstallConfirm by rememberSaveable { mutableStateOf(false) }

    val isProotBusy = prootStatus.phase == ProotSearXngPhase.INSTALLING ||
        prootStatus.phase == ProotSearXngPhase.STARTING ||
        prootStatus.phase == ProotSearXngPhase.STOPPING ||
        prootStatus.phase == ProotSearXngPhase.UNINSTALLING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_search_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_search_intro_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_search_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        SearxngMode.DISABLED to R.string.settings_search_mode_disabled,
                        SearxngMode.EXTERNAL to R.string.settings_search_mode_external,
                        SearxngMode.PROOT_LOCAL to R.string.settings_search_mode_proot,
                    ).forEach { (mode, labelRes) ->
                        val selected = config.searxngMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectSearxngMode(mode) },
                            label = { Text(stringResource(labelRes)) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                if (config.searxngMode == SearxngMode.DISABLED) {
                    Text(
                        text = stringResource(R.string.settings_search_mode_disabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (config.searxngMode == SearxngMode.EXTERNAL) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_search_external_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_search_searxng_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = externalUrlDraft,
                        onValueChange = { externalUrlDraft = it },
                        label = { Text(stringResource(R.string.settings_search_searxng_dialog_label)) },
                        placeholder = { Text(stringResource(R.string.settings_search_searxng_dialog_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onSaveSearxngBaseUrl(externalUrlDraft) },
                        ) {
                            Text(stringResource(R.string.settings_search_searxng_save))
                        }
                        if (config.searxngBaseUrl.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    externalUrlDraft = ""
                                    onSaveSearxngBaseUrl("")
                                },
                            ) {
                                Text(stringResource(R.string.settings_search_searxng_clear))
                            }
                        }
                    }
                }
            }
        }

        if (config.searxngMode == SearxngMode.PROOT_LOCAL) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_search_proot_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        val (badgeTextRes, badgeContainer, badgeContent) = when {
                            isProotBusy -> Triple(
                                R.string.settings_search_proot_status_busy,
                                MaterialTheme.colorScheme.tertiaryContainer,
                                MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            prootStatus.phase == ProotSearXngPhase.ERROR -> Triple(
                                R.string.settings_search_proot_status_error,
                                MaterialTheme.colorScheme.errorContainer,
                                MaterialTheme.colorScheme.onErrorContainer,
                            )
                            prootStatus.isRunning -> Triple(
                                R.string.settings_search_proot_status_running,
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            prootStatus.isInstalled -> Triple(
                                R.string.settings_search_proot_status_stopped,
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            else -> Triple(
                                R.string.settings_search_proot_status_not_installed,
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TinyModelBadge(
                            text = stringResource(badgeTextRes),
                            containerColor = badgeContainer,
                            contentColor = badgeContent,
                        )
                    }

                    Text(
                        text = stringResource(R.string.settings_search_proot_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (prootStatus.message.isNotBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (prootStatus.phase == ProotSearXngPhase.ERROR) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isProotBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Text(
                                    text = prootStatus.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (prootStatus.phase == ProotSearXngPhase.ERROR) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = prootPortDraft,
                            onValueChange = { value ->
                                prootPortDraft = value.filter { it.isDigit() }.take(5)
                            },
                            label = { Text(stringResource(R.string.settings_search_proot_port_label)) },
                            singleLine = true,
                            enabled = !isProotBusy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = { onSaveLocalProotSearxngPort(prootPortDraft) },
                            enabled = !isProotBusy,
                        ) {
                            Text(stringResource(R.string.settings_search_proot_port_save))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isProotBusy) {
                                onSetLocalProotSearxngAutoStart(!config.autoStartLocalProot)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_search_proot_autostart_title),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.settings_search_proot_autostart_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = config.autoStartLocalProot,
                            onCheckedChange = onSetLocalProotSearxngAutoStart,
                            enabled = !isProotBusy,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!prootStatus.isInstalled) {
                            Button(
                                onClick = onInstallProotSearxng,
                                enabled = !isProotBusy && prootStatus.isProotAvailable,
                            ) {
                                Text(stringResource(R.string.settings_search_proot_action_install))
                            }
                        } else {
                            if (prootStatus.isRunning) {
                                Button(
                                    onClick = onStopProotSearxng,
                                    enabled = !isProotBusy,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                    ),
                                ) {
                                    Text(stringResource(R.string.settings_search_proot_action_stop))
                                }
                            } else {
                                Button(
                                    onClick = onStartProotSearxng,
                                    enabled = !isProotBusy,
                                ) {
                                    Text(stringResource(R.string.settings_search_proot_action_start))
                                }
                            }
                            OutlinedButton(
                                onClick = { showUninstallConfirm = true },
                                enabled = !isProotBusy,
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_search_proot_action_uninstall),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (prootStatus.lastLog.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.settings_search_proot_log_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = prootStatus.lastLog.takeLast(1200),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_search_probe_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = onProbeAllSearchEngines,
                        enabled = !uiState.isProbingSearchEngines,
                    ) {
                        if (uiState.isProbingSearchEngines) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_search_probe_running))
                        } else {
                            Text(stringResource(R.string.settings_search_probe_action))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.settings_search_probe_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.engineAvailabilityList.isNotEmpty()) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.engineAvailabilityList.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "${item.displayName} · ${item.category.displayName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (!item.available && item.reason.isNotBlank()) {
                                        Text(
                                            text = item.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TinyModelBadge(
                                    text = if (item.available) {
                                        stringResource(R.string.settings_search_probe_available, item.latencyMs)
                                    } else {
                                        stringResource(R.string.settings_search_probe_unavailable)
                                    },
                                    containerColor = if (item.available) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (item.available) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.settings_search_proot_uninstall_dialog_title)) },
            text = { Text(stringResource(R.string.settings_search_proot_uninstall_dialog_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUninstallConfirm = false
                        onUninstallProotSearxng()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_search_proot_uninstall_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Preview(showBackground = true, name = "Search Settings PRoot - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Search Settings PRoot - Dark")
@Composable
private fun SearchSettingsScreenPreview() {
    MaidTheme(dynamicColor = false) {
        SettingsScreenContent(
            currentDestination = SettingsDestination.SEARCH_SETTINGS,
            uiState = SettingsUiState(
                isLoaded = true,
                searchSettingsConfig = SearchSettingsConfig(
                    searxngMode = SearxngMode.PROOT_LOCAL,
                    localProotPort = 8888,
                    autoStartLocalProot = false,
                ),
                prootSearxngStatus = ProotSearXngStatus(
                    isProotAvailable = true,
                    isInstalled = true,
                    isRunning = false,
                    port = 8888,
                    phase = ProotSearXngPhase.IDLE,
                    message = "已安装，当前未运行（零内存占用）",
                ),
                engineAvailabilityList = listOf(
                    SearchEngineAvailability(
                        engineId = "searxng",
                        displayName = "SearXNG",
                        category = SearchEngineCategory.GENERAL_WEB,
                        available = false,
                        latencyMs = 0L,
                        reason = "当前未启动",
                    ),
                    SearchEngineAvailability(
                        engineId = "duckduckgo",
                        displayName = "DuckDuckGo",
                        category = SearchEngineCategory.GENERAL_WEB,
                        available = true,
                        latencyMs = 210L,
                        reason = "OK",
                    ),
                    SearchEngineAvailability(
                        engineId = "arxiv",
                        displayName = "arXiv",
                        category = SearchEngineCategory.ACADEMIC,
                        available = true,
                        latencyMs = 145L,
                        reason = "OK",
                    ),
                ),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onOpenLlmKeys = {},
            onOpenModelConfig = {},
            onOpenPersona = {},
            onOpenPrivileges = {},
            onSelectProvider = {},
            onInputChanged = {},
            onCustomBaseUrlChanged = {},
            onSaveApiKey = {},
            onClearApiKey = {},
            onSelectModelProvider = { _, _ -> },
            onSelectModelId = { _, _ -> },
            onRefreshProviderModels = { _, _ -> },
            onSaveModelConfigs = {},
            onClearAllModelConfigs = {},
            onRefreshPersonaPresets = {},
            onSelectPersonaPreset = {},
            onPersonaTextChanged = {},
            onSavePersona = {},
            onClearPersona = {},
            onRefreshPrivileges = {},
            onRequestPrivilege = {},
            onCaptureTestScreenshot = {},
        )
    }
}
