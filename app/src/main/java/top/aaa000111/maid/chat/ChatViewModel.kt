package top.aaa000111.maid.chat

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.aaa000111.maid.R
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.PrivilegeManager
import top.aaa000111.maid.provider.LlmProviderRegistry
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.ModelGroups
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.settings.AgentStepConfigRepository
import top.aaa000111.maid.settings.PersonaPreset
import top.aaa000111.maid.settings.PersonaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val dispatcher: ChatTaskDispatcher = ChatBackgroundRuntime.current

    private val defaultSessionTitle: String =
        application.getString(R.string.chat_default_session_title)

    private val initialSession = dispatcher.preferredInitialSession(defaultSessionTitle)

    private val currentSessionId = MutableStateFlow(initialSession.id)
    private val inputText = MutableStateFlow("")
    private val pendingFileUris = MutableStateFlow<List<String>>(emptyList())
    private val pendingImageUris = MutableStateFlow<List<String>>(emptyList())

    private val sessionSnapshots = dispatcher.sessions.flatMapLatest { sessionList ->
        if (sessionList.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(sessionList.map { it.state }) { snapshots ->
                snapshots.toList()
            }
        }
    }

    val personaConfig = PersonaRepository.observePersonaConfig()
    private val _personaPresets = MutableStateFlow<List<PersonaPreset>?>(null)
    val personaPresets = _personaPresets.asStateFlow()
    private val _isSwitchingPersona = MutableStateFlow(false)
    val isSwitchingPersona = _isSwitchingPersona.asStateFlow()

    private val modelGroups = ModelConfigRepository.observeModelGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelGroups())
    private val isSwitchingModelGroup = MutableStateFlow(false)
    private val modelGroupError = MutableStateFlow(false)
    private val modelControls = combine(
        modelGroups, isSwitchingModelGroup, modelGroupError,
    ) { groups, switching, failed ->
        ChatModelGroupControlsState(groups, switching, failed)
    }

    private val chatModelReadinessFlow = modelGroups
        .map { groups ->
            groups.groups.firstOrNull { it.id == groups.activeId }
                ?.selections?.get(ModelRole.FAST_CHEAP) ?: RoleModelSelection()
        }
        .flatMapLatest { selection ->
            val chatProvider = selection.provider
            if (!selection.isComplete || chatProvider == null) {
                flowOf(false)
            } else {
                LlmProviderRegistry.observeAvailability(
                    provider = chatProvider,
                    customProviderId = selection.normalizedCustomProviderId,
                ).flatMapLatest { availability ->
                    flowOf(availability.isReady)
                }
            }
        }

    val uiState: StateFlow<ChatUiState> = combine(
        combine(sessionSnapshots, currentSessionId, dispatcher.runningSessionIds) { snapshots, activeId, runningIds ->
            Triple(snapshots, activeId, runningIds)
        },
        combine(inputText, pendingFileUris, pendingImageUris) { text, files, images ->
            Triple(text, files, images)
        },
        chatModelReadinessFlow,
        AgentStepConfigRepository.observeModelOutputDisplayMode(),
        modelControls,
    ) { (snapshots, activeId, runningIds), (text, files, images), hasReadyModel, outputDisplayMode, controls ->
        val effectiveActiveId = if (snapshots.any { it.id == activeId }) {
            activeId
        } else {
            snapshots.firstOrNull()?.id ?: activeId
        }
        if (effectiveActiveId != activeId && effectiveActiveId.isNotBlank()) {
            currentSessionId.value = effectiveActiveId
        }
        ChatUiState(
            sessions = snapshots,
            currentSessionId = effectiveActiveId,
            inputText = text,
            pendingFileUris = files,
            pendingImageUris = images,
            isModelConfigured = hasReadyModel,
            modelGroupControls = controls,
            isGenerating = effectiveActiveId in runningIds,
            modelOutputDisplayMode = outputDisplayMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(
            sessions = dispatcher.sessions.value.map { it.snapshot() },
            currentSessionId = initialSession.id,
            isGenerating = dispatcher.isSessionGenerating(initialSession.id),
        ),
    )

    fun refreshPersonaPresets() {
        viewModelScope.launch {
            try {
                _personaPresets.value = PersonaRepository.scanBuiltInPersonas()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (_personaPresets.value == null) _personaPresets.value = emptyList()
                Toast.makeText(getApplication<Application>(), R.string.chat_persona_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun selectPersonaPreset(preset: PersonaPreset) {
        if (_isSwitchingPersona.value || dispatcher.isSessionGenerating(currentSessionId.value)) return
        _isSwitchingPersona.value = true
        viewModelScope.launch {
            try {
                PersonaRepository.selectAndSavePreset(preset)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(getApplication<Application>(), R.string.settings_persona_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                _isSwitchingPersona.value = false
            }
        }
    }

    fun selectModelGroup(id: String) {
        val groups = modelGroups.value
        if (isSwitchingModelGroup.value || groups.activeId == id || groups.groups.none { it.id == id }) return
        isSwitchingModelGroup.value = true
        modelGroupError.value = false
        viewModelScope.launch {
            try {
                ModelConfigRepository.activateModelGroup(id)
                AppRuntime.syncModelExecutors()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                modelGroupError.value = true
            } finally {
                isSwitchingModelGroup.value = false
            }
        }
    }

    fun selectSession(sessionId: String) {
        if (currentSessionId.value == sessionId) return
        // 切换会话仅影响 GUI 当前展示，不中断任何后台运行的会话任务
        currentSessionId.value = sessionId
        dispatcher.markActiveSession(sessionId)
    }

    fun createNewSession() {
        // 新建会话仅切换 GUI 当前目标，不中断已有会话后台任务
        val newSession = dispatcher.createNewSession(defaultSessionTitle)
        currentSessionId.value = newSession.id
        dispatcher.markActiveSession(newSession.id)
    }

    fun deleteSession(sessionId: String) {
        val fallbackSession = dispatcher.deleteSession(
            sessionId = sessionId,
            defaultTitlePrefix = defaultSessionTitle,
        )
        if (currentSessionId.value == sessionId || dispatcher.findSession(currentSessionId.value) == null) {
            currentSessionId.value = fallbackSession.id
            dispatcher.markActiveSession(fallbackSession.id)
        }
    }

    fun onInputChanged(value: String) {
        inputText.value = value
    }

    fun appendVoiceText(sessionId: String, text: String) {
        if (currentSessionId.value != sessionId) return
        val draft = inputText.value.trimEnd()
        inputText.value = if (draft.isEmpty()) text else "$draft $text"
    }

    fun addFileUris(uris: List<String>) {
        val cleaned = uris.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        pendingFileUris.value = (pendingFileUris.value + cleaned).distinct()
    }

    fun removeFileUri(uri: String) {
        pendingFileUris.value = pendingFileUris.value.filterNot { it == uri }
    }

    fun addImageUris(uris: List<String>) {
        val cleaned = uris.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        pendingImageUris.value = (pendingImageUris.value + cleaned).distinct()
    }

    fun removeImageUri(uri: String) {
        pendingImageUris.value = pendingImageUris.value.filterNot { it == uri }
    }

    fun clearPendingAttachments() {
        pendingFileUris.value = emptyList()
        pendingImageUris.value = emptyList()
    }

    fun sendMessage() {
        if (isSwitchingModelGroup.value || _isSwitchingPersona.value) return
        val userInput = UserInput(
            text = inputText.value,
            fileUris = pendingFileUris.value,
            imageUris = pendingImageUris.value,
        ).normalized()
        if (userInput.isBlank) return

        val session = dispatcher.findSession(currentSessionId.value) ?: return
        val canAcceptExternalInput = session.hasPendingQuestion() || session.isPlanning()
        if (dispatcher.isSessionGenerating(session.id) && !canAcceptExternalInput) {
            return
        }

        inputText.value = ""
        clearPendingAttachments()
        dispatcher.submitInput(session = session, input = userInput)
    }

    fun stopGenerating() {
        dispatcher.stopSession(currentSessionId.value)
    }

    fun confirmPermissionRequest(itemId: String) {
        val session = dispatcher.findSession(currentSessionId.value) ?: return
        viewModelScope.launch {
            session.confirmPermissionRequest(itemId)
        }
    }

    fun requestPrivilegeFromCard(privilege: AndroidPrivilege) {
        if (!PrivilegeManager.isInitialized) return
        viewModelScope.launch {
            runCatching {
                PrivilegeManager.requestPrivilege(privilege)
            }
        }
    }

    fun openFile(
        attachmentId: String?,
        fileName: String,
        uri: String,
        fallbackUri: String = uri,
    ) {
        val session = dispatcher.findSession(currentSessionId.value)
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val accessed = attachmentId?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { session?.accessAttachment(it) }
                    ?: session?.accessAttachment(uri)
                Triple(
                    accessed?.uri ?: uri,
                    accessed?.originalUri ?: fallbackUri,
                    fileName.trim().ifEmpty { accessed?.normalizedFileName.orEmpty() },
                )
            }
            val app = getApplication<Application>()
            val result = SessionFileOpener.openFile(
                context = app,
                uri = resolved.first,
                fileName = resolved.third,
                fallbackUri = resolved.second,
            )
            if (!result.success) {
                Toast.makeText(
                    app,
                    app.getString(R.string.chat_file_open_failed, result.message),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
