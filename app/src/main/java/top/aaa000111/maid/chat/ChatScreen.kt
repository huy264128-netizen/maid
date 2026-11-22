package top.aaa000111.maid.chat

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.aaa000111.maid.R
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.settings.PersonaConfig
import top.aaa000111.maid.ui.theme.MaidTheme

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val persona by viewModel.personaConfig.collectAsStateWithLifecycle(initialValue = PersonaConfig())
    val presets by viewModel.personaPresets.collectAsStateWithLifecycle()
    val switchingPersona by viewModel.isSwitchingPersona.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshPersonaPresets() }

    ChatScreenContent(
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        onOpenTerminal = onOpenTerminal,
        onSelectSession = viewModel::selectSession,
        onCreateNewSession = viewModel::createNewSession,
        onDeleteSession = viewModel::deleteSession,
        onInputChanged = viewModel::onInputChanged,
        onAddFileUris = viewModel::addFileUris,
        onRemoveFileUri = viewModel::removeFileUri,
        onAddImageUris = viewModel::addImageUris,
        onRemoveImageUri = viewModel::removeImageUri,
        onSendMessage = viewModel::sendMessage,
        onStopGenerating = viewModel::stopGenerating,
        onConfirmPermissionRequest = viewModel::confirmPermissionRequest,
        onRequestPrivilege = viewModel::requestPrivilegeFromCard,
        onOpenFile = viewModel::openFile,
        onSelectModelGroup = viewModel::selectModelGroup,
        isSwitchingPersona = switchingPersona,
        personaControl = {
            ChatPersonaPicker(
                presets = presets,
                persona = persona,
                enabled = !uiState.isGenerating && !switchingPersona,
                onOpen = viewModel::refreshPersonaPresets,
                onSelect = viewModel::selectPersonaPreset,
            )
        },
    )
}

/**
 * 主聊天界面：纵向容器依次包含
 * 1. 顶部横向栏（左侧切换 Session 按钮，右侧 PRoot 终端按钮与设置按钮）
 * 2. 中间可上下滚动的聊天内容区
 * 3. 底部独立的输入组件
 */
@Composable
fun ChatScreenContent(
    uiState: ChatUiState,
    onOpenSettings: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCreateNewSession: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGenerating: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onAddFileUris: (List<String>) -> Unit = {},
    onRemoveFileUri: (String) -> Unit = {},
    onAddImageUris: (List<String>) -> Unit = {},
    onRemoveImageUri: (String) -> Unit = {},
    onConfirmPermissionRequest: (String) -> Unit = {},
    onRequestPrivilege: (AndroidPrivilege) -> Unit = {},
    onSelectModelGroup: (String) -> Unit = {},
    isSwitchingPersona: Boolean = false,
    personaControl: @Composable () -> Unit = {},
    onOpenFile: (attachmentId: String?, fileName: String, uri: String, fallbackUri: String) -> Unit = { _, _, _, _ -> },
) {
    var showSessionSheet by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            onAddImageUris(uris.map { it.toString() })
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            onAddFileUris(uris.map { it.toString() })
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. 顶部横向容器：左边切换 Session 按钮，右边 PRoot 终端按钮与设置按钮
            ChatTopBar(
                currentSessionTitle = uiState.currentSession?.title
                    ?: stringResource(R.string.chat_default_session_title),
                onSwitchSessionClick = { showSessionSheet = true },
                onTerminalClick = onOpenTerminal,
                onSettingsClick = onOpenSettings,
            )

            // 2. 中间可上下滚动的聊天内容区域
            ChatMessageList(
                items = uiState.visibleMessages,
                isGenerating = uiState.isGenerating,
                isModelConfigured = uiState.isModelConfigured,
                sessionState = uiState.currentSessionState,
                activeToolName = uiState.activeToolName,
                sessionAttachments = uiState.currentSession?.attachments.orEmpty(),
                onConfirmPermissionRequest = onConfirmPermissionRequest,
                onRequestPrivilege = onRequestPrivilege,
                onOpenFile = onOpenFile,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            // 3. 底部独立拆出的用户输入组件
            ChatInputBar(
                inputText = uiState.inputText,
                hasPendingQuestion = uiState.pendingQuestion != null,
                isPlanning = uiState.isPlanning,
                pendingFileUris = uiState.pendingFileUris,
                pendingImageUris = uiState.pendingImageUris,
                isGenerating = uiState.isGenerating,
                onInputChanged = onInputChanged,
                onPickImagesClick = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onPickFilesClick = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onRemoveFileUri = onRemoveFileUri,
                onRemoveImageUri = onRemoveImageUri,
                onSendClick = onSendMessage,
                onStopClick = onStopGenerating,
                isModelUpdating = uiState.modelGroupControls.isSwitching || isSwitchingPersona,
                personaControl = personaControl,
                modelControls = {
                    ChatModelControls(
                        state = uiState.modelGroupControls,
                        enabled = !uiState.isGenerating,
                        onSelectGroup = onSelectModelGroup,
                        onOpenSettings = onOpenSettings,
                    )
                },
            )
        }
    }

    if (showSessionSheet) {
        SessionPickerBottomSheet(
            sessions = uiState.sessions,
            currentSessionId = uiState.currentSessionId,
            onDismiss = { showSessionSheet = false },
            onSelectSession = { sessionId ->
                showSessionSheet = false
                onSelectSession(sessionId)
            },
            onCreateNewSession = {
                showSessionSheet = false
                onCreateNewSession()
            },
            onDeleteSession = onDeleteSession,
        )
    }
}

@Composable
private fun ChatTopBar(
    currentSessionTitle: String,
    onSwitchSessionClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilledTonalButton(
                    onClick = onSwitchSessionClick,
                    modifier = Modifier.widthIn(max = 240.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_session),
                        contentDescription = stringResource(R.string.chat_switch_session),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentSessionTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTerminalClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = stringResource(R.string.chat_open_terminal),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.chat_open_settings),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionPickerBottomSheet(
    sessions: List<ChatSession.Snapshot>,
    currentSessionId: String,
    onDismiss: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCreateNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.chat_sessions_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onCreateNewSession) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.chat_new_session))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = sessions, key = { it.id }) { session ->
                    val isSelected = session.id == currentSessionId
                    val count = session.itemCount
                    ListItem(
                        headlineContent = {
                            Text(
                                text = session.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(text = stringResource(R.string.chat_session_message_count, count))
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_chat_session),
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(onClick = { onDeleteSession(session.id) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.chat_delete_session),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        colors = if (isSelected) {
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(
                                    alpha = 0.45f,
                                ),
                            )
                        } else {
                            ListItemDefaults.colors()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSession(session.id) },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Chat - Populated Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Chat - Populated Dark")
@Composable
private fun ChatScreenPreview() {
    val sampleMessages = listOf(
        ChatItem.UserMessage(id = "1", content = "帮我写一个 Compose MD3 列表示例"),
        ChatItem.ToolStatusItem(
            id = "2",
            title = "准备调用 OpenAI",
            detail = "已从本地安全存储读取加密凭证",
            isCompleted = true,
        ),
        ChatItem.AssistantMessage(
            id = "3",
            provider = LlmProvider.OPENAI,
            markdown = """
                当然可以，这里是一个简单的 **LazyColumn** 示例：

                - 使用 `LazyColumn` 渲染纵向列表
                - 配合 Material 3 `ListItem` 展示内容

                ```kotlin
                LazyColumn {
                    items(items) { item ->
                        Text(item)
                    }
                }
                ```
            """.trimIndent(),
        ),
    )
    val sampleSession = ChatSession(
        id = "session-1",
        title = "Kotlin Compose 讨论",
        items = sampleMessages,
    ).snapshot()

    MaidTheme(dynamicColor = false) {
        ChatScreenContent(
            uiState = ChatUiState(
                sessions = listOf(sampleSession),
                currentSessionId = sampleSession.id,
                inputText = "",
                isModelConfigured = true,
                isGenerating = false,
            ),
            onOpenSettings = {},
            onSelectSession = {},
            onCreateNewSession = {},
            onInputChanged = {},
            onSendMessage = {},
            onStopGenerating = {},
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Chat - Empty Dark")
@Composable
private fun ChatEmptyDarkPreview() {
    val sampleSession = ChatSession(id = "session-1", title = "新对话").snapshot()
    MaidTheme(dynamicColor = false, darkTheme = true) {
        ChatScreenContent(
            uiState = ChatUiState(
                sessions = listOf(sampleSession),
                currentSessionId = sampleSession.id,
                inputText = "",
                isModelConfigured = false,
                isGenerating = false,
            ),
            onOpenSettings = {},
            onSelectSession = {},
            onCreateNewSession = {},
            onInputChanged = {},
            onSendMessage = {},
            onStopGenerating = {},
        )
    }
}
