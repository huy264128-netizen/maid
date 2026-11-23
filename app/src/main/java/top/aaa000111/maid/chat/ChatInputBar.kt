package top.aaa000111.maid.chat

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.aaa000111.maid.R
import top.aaa000111.maid.ui.theme.MaidTheme

/**
 * 底部独立的聊天输入组件，包含多行消息输入框、图片/文件附件选择与发送/停止按钮。
 */
@Composable
fun ChatInputBar(
    inputText: String,
    isGenerating: Boolean,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasPendingQuestion: Boolean = false,
    isPlanning: Boolean = false,
    pendingFileUris: List<String> = emptyList(),
    pendingImageUris: List<String> = emptyList(),
    onPickImagesClick: () -> Unit = {},
    onPickFilesClick: () -> Unit = {},
    onRemoveFileUri: (String) -> Unit = {},
    onRemoveImageUri: (String) -> Unit = {},
    isModelUpdating: Boolean = false,
    modelControls: @Composable () -> Unit = {},
    personaControl: @Composable () -> Unit = {},
    voiceInput: ChatVoiceInput? = null,
) {
    val hasAttachments = pendingFileUris.isNotEmpty() || pendingImageUris.isNotEmpty()
    val allowExternalInput = hasPendingQuestion || isPlanning
    val allowEditingAndSend = !isGenerating || allowExternalInput
    val canSend = (inputText.isNotBlank() || hasAttachments) && allowEditingAndSend && !isModelUpdating
    val showSendForExternalInput = allowExternalInput && canSend
    val showStopAction = isGenerating && !showSendForExternalInput
    val showVoiceAction = voiceInput != null && !isGenerating && !isModelUpdating &&
        !hasAttachments && inputText.isBlank()
    val isRecordingVoice = !isGenerating && voiceInput?.recording == true
    val isProcessingVoice = !isGenerating && voiceInput?.processing == true
    var previewingImageUri by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hasAttachments) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pendingImageUris.forEach { uri ->
                            AttachmentPreviewChip(
                                label = formatAttachmentUriLabel(uri),
                                iconRes = R.drawable.ic_image,
                                enabled = allowEditingAndSend,
                                onClick = { previewingImageUri = uri },
                                onRemove = { onRemoveImageUri(uri) },
                            )
                        }
                        pendingFileUris.forEach { uri ->
                            AttachmentPreviewChip(
                                label = formatAttachmentUriLabel(uri),
                                iconRes = R.drawable.ic_attach_file,
                                enabled = allowEditingAndSend,
                                onClick = { onRemoveFileUri(uri) },
                                onRemove = { onRemoveFileUri(uri) },
                            )
                        }
                    }
                }

                // 输入框上方工具栏：左侧附件按钮，右侧人设和模型配置组选单。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = onPickImagesClick,
                            enabled = allowEditingAndSend,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_image),
                                contentDescription = stringResource(R.string.chat_attach_image),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        IconButton(
                            onClick = onPickFilesClick,
                            enabled = allowEditingAndSend,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_attach_file),
                                contentDescription = stringResource(R.string.chat_attach_file),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (inputText.isNotEmpty() && allowEditingAndSend) {
                        TextButton(onClick = { onInputChanged("") }) {
                            Text(text = stringResource(R.string.chat_clear_input))
                        }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    ) {
                        Box(modifier = Modifier.weight(1f, fill = false)) { personaControl() }
                        Box(modifier = Modifier.weight(1f, fill = false)) { modelControls() }
                    }
                }

                // 主输入区 + 发送/停止按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChanged,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = stringResource(
                                    when {
                                        hasPendingQuestion -> R.string.chat_input_placeholder_question
                                        isPlanning -> R.string.chat_input_placeholder_planning
                                        else -> R.string.chat_input_placeholder
                                    },
                                ),
                            )
                        },
                        minLines = 1,
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                    )

                    FilledIconButton(
                        onClick = {
                            when {
                                isRecordingVoice || showVoiceAction -> voiceInput?.onClick()
                                showStopAction -> onStopClick()
                                else -> onSendClick()
                            }
                        },
                        enabled = !isProcessingVoice &&
                            (isRecordingVoice || showVoiceAction || showStopAction || canSend),
                        colors = if (isRecordingVoice || showStopAction) {
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            IconButtonDefaults.filledIconButtonColors()
                        },
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .size(48.dp),
                    ) {
                        if (isProcessingVoice) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                painter = painterResource(
                                    when {
                                        isRecordingVoice || showStopAction -> R.drawable.ic_stop
                                        showVoiceAction -> R.drawable.ic_mic
                                        else -> R.drawable.ic_send
                                    },
                                ),
                                contentDescription = stringResource(
                                    when {
                                        isRecordingVoice -> R.string.chat_voice_stop
                                        showStopAction -> R.string.chat_stop
                                        showVoiceAction -> R.string.chat_voice_start
                                        else -> R.string.chat_send
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    val activePreviewUri = previewingImageUri
    if (activePreviewUri != null) {
        ZoomableImageViewerDialog(
            uri = activePreviewUri,
            title = formatAttachmentUriLabel(activePreviewUri),
            onDismiss = { previewingImageUri = null },
        )
    }
}

@Composable
private fun AttachmentPreviewChip(
    label: String,
    iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = true,
        enabled = enabled,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        trailingIcon = {
            Spacer(modifier = Modifier.width(2.dp))
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.size(18.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.chat_remove_attachment),
                    modifier = Modifier.size(14.dp),
                )
            }
        },
    )
}

internal fun formatAttachmentUriLabel(uri: String): String {
    val trimmed = uri.trim()
    val displayName = SessionAttachmentStorage.inferDisplayName(trimmed)
    return displayName.ifEmpty { trimmed }
}

@Preview(showBackground = true, name = "ChatInputBar - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "ChatInputBar - Dark")
@Composable
private fun ChatInputBarPreview() {
    MaidTheme(dynamicColor = false) {
        ChatInputBar(
            inputText = "帮我看看这张图和日志",
            pendingImageUris = listOf("content://media/external/images/media/1024"),
            pendingFileUris = listOf("content://com.android.providers.downloads/document/crash_log.txt"),
            isGenerating = false,
            onInputChanged = {},
            onSendClick = {},
            onStopClick = {},
        )
    }
}
