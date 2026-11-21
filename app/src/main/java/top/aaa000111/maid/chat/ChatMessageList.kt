package top.aaa000111.maid.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aaa000111.maid.R
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog
import java.io.File

/**
 * 中间可上下滚动的聊天内容区域，按消息类型渲染用户气泡、Markdown 回复、工具状态与系统提示组件，
 * 并支持点击图片放大及双指缩放、拖拽平移、双击缩放/还原等简单手势操作。
 */
@Composable
fun ChatMessageList(
    items: List<ChatItem>,
    isGenerating: Boolean,
    isModelConfigured: Boolean,
    modifier: Modifier = Modifier,
    sessionState: ChatSessionState = ChatSessionState.Idle,
    activeToolName: String? = null,
    sessionAttachments: List<SessionAttachment> = emptyList(),
    onConfirmPermissionRequest: (String) -> Unit = {},
    onRequestPrivilege: (AndroidPrivilege) -> Unit = {},
    onOpenFile: ((attachmentId: String?, fileName: String, uri: String, fallbackUri: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var previewingImageUri by rememberSaveable { mutableStateOf<String?>(null) }

    val effectiveOpenFile: (String?, String, String, String) -> Unit = remember(onOpenFile, context) {
        { attachmentId, fileName, uri, fallbackUri ->
            if (onOpenFile != null) {
                onOpenFile(attachmentId, fileName, uri, fallbackUri)
            } else {
                SessionFileOpener.openFile(
                    context = context,
                    uri = uri,
                    fileName = fileName,
                    fallbackUri = fallbackUri,
                )
            }
        }
    }

    LaunchedEffect(items.size, items.lastOrNull()?.id, isGenerating, sessionState, activeToolName) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(items.lastIndex)
        }
    }

    if (items.isEmpty() && !isGenerating) {
        ChatEmptyState(
            isModelConfigured = isModelConfigured,
            modifier = modifier,
        )
        return
    }

    val attachmentByUriOrId = remember(sessionAttachments) {
        buildMap {
            sessionAttachments.forEach { att ->
                put(att.id, att)
                put(att.uri, att)
                put(att.originalUri, att)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.UserMessage -> UserMessageCard(
                    item = item,
                    attachmentLookup = attachmentByUriOrId,
                    onImageClick = { uri -> previewingImageUri = uri },
                    onFileClick = effectiveOpenFile,
                )
                is ChatItem.AssistantMessage -> AssistantMessageCard(
                    item = item,
                    attachmentLookup = attachmentByUriOrId,
                    onImageClick = { uri -> previewingImageUri = uri },
                    onFileClick = effectiveOpenFile,
                )
                is ChatItem.ToolStatusItem -> ToolStatusCard(item = item)
                is ChatItem.PermissionRequestItem -> PermissionRequestCard(
                    item = item,
                    onConfirm = { onConfirmPermissionRequest(item.id) },
                    onRequestPrivilege = onRequestPrivilege,
                )
                is ChatItem.FileCardItem -> FileMessageCard(
                    item = item,
                    attachmentLookup = attachmentByUriOrId,
                    onImageClick = { uri -> previewingImageUri = uri },
                    onOpenFile = effectiveOpenFile,
                )
                is ChatItem.SystemNoticeItem -> SystemNoticeCard(item = item)
            }
        }

        if (isGenerating) {
            item(key = "generating_indicator") {
                GeneratingIndicator(
                    sessionState = sessionState,
                    activeToolName = activeToolName,
                )
            }
        }
    }

    val activePreviewUri = previewingImageUri
    if (activePreviewUri != null) {
        val matchedAttachment = attachmentByUriOrId[activePreviewUri]
        val effectiveUri = matchedAttachment?.uri ?: activePreviewUri
        val titleText = matchedAttachment?.let { "${it.id} · ${it.normalizedFileName}" }
            ?: formatAttachmentUriLabel(activePreviewUri)
        ZoomableImageViewerDialog(
            uri = effectiveUri,
            fallbackUri = matchedAttachment?.originalUri ?: activePreviewUri,
            title = titleText,
            onDismiss = { previewingImageUri = null },
        )
    }
}

@Composable
private fun ChatEmptyState(
    isModelConfigured: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chat_session),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(28.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.chat_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!isModelConfigured) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.chat_empty_missing_key_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageCard(
    item: ChatItem.UserMessage,
    attachmentLookup: Map<String, SessionAttachment> = emptyMap(),
    onImageClick: (String) -> Unit = {},
    onFileClick: (attachmentId: String?, fileName: String, uri: String, fallbackUri: String) -> Unit = { _, _, _, _ -> },
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 6.dp,
            ),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.imageUris.isNotEmpty() || item.fileUris.isNotEmpty()) {
                    MessageAttachmentsSection(
                        imageUris = item.imageUris,
                        fileUris = item.fileUris,
                        attachmentLookup = attachmentLookup,
                        onImageClick = onImageClick,
                        onFileClick = onFileClick,
                    )
                }
                if (item.content.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageCard(
    item: ChatItem.AssistantMessage,
    attachmentLookup: Map<String, SessionAttachment> = emptyMap(),
    onImageClick: (String) -> Unit = {},
    onFileClick: (attachmentId: String?, fileName: String, uri: String, fallbackUri: String) -> Unit = { _, _, _, _ -> },
) {
    Surface(
        shape = RoundedCornerShape(
            topStart = 6.dp,
            topEnd = 20.dp,
            bottomStart = 20.dp,
            bottomEnd = 20.dp,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val headerText = when {
                item.provider != null -> {
                    val providerName = stringResource(item.provider.displayNameRes)
                    if (item.modelId.isNullOrBlank()) {
                        providerName
                    } else {
                        "$providerName · ${item.modelId}"
                    }
                }
                !item.senderLabel.isNullOrBlank() -> item.senderLabel
                !item.modelId.isNullOrBlank() -> item.modelId
                else -> stringResource(R.string.app_name)
            }
            Text(
                text = headerText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (item.markdown.isNotBlank()) {
                MarkdownText(
                    markdown = item.markdown,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    linkColor = MaterialTheme.colorScheme.primary,
                    syntaxHighlightColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    syntaxHighlightTextColor = MaterialTheme.colorScheme.onSurface,
                    headingBreakColor = MaterialTheme.colorScheme.outlineVariant,
                    isTextSelectable = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (item.imageUris.isNotEmpty() || item.fileUris.isNotEmpty()) {
                MessageAttachmentsSection(
                    imageUris = item.imageUris,
                    fileUris = item.fileUris,
                    attachmentLookup = attachmentLookup,
                    onImageClick = onImageClick,
                    onFileClick = onFileClick,
                )
            }
        }
    }
}

@Composable
private fun FileMessageCard(
    item: ChatItem.FileCardItem,
    attachmentLookup: Map<String, SessionAttachment> = emptyMap(),
    onImageClick: (String) -> Unit = {},
    onOpenFile: (attachmentId: String?, fileName: String, uri: String, fallbackUri: String) -> Unit = { _, _, _, _ -> },
) {
    val matched = item.attachmentId.takeIf { it.isNotBlank() }?.let { attachmentLookup[it] }
        ?: attachmentLookup[item.uri]
    val effectiveUri = matched?.uri ?: item.uri
    val effectiveFallbackUri = matched?.originalUri ?: item.uri
    val effectiveFileName = item.fileName.trim().ifEmpty {
        matched?.normalizedFileName ?: formatAttachmentUriLabel(effectiveUri)
    }
    val effectiveDescription = item.description.trim().ifEmpty {
        matched?.normalizedDescription.orEmpty()
    }
    val effectiveId = item.attachmentId.trim().ifEmpty { matched?.id.orEmpty() }
    val isImageCard = item.isImage || (matched?.isImage == true)
    val thumbnail = if (isImageCard) {
        rememberUriThumbnail(
            uri = effectiveUri,
            fallbackUri = effectiveFallbackUri,
            maxDimension = 240,
        )
    } else {
        null
    }

    val openAction: () -> Unit = {
        onOpenFile(
            effectiveId.takeIf { it.isNotEmpty() },
            effectiveFileName,
            effectiveUri,
            effectiveFallbackUri,
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isImageCard && thumbnail != null) {
                    onImageClick(effectiveUri)
                } else {
                    openAction()
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isImageCard && thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = effectiveFileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onImageClick(effectiveUri) },
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(
                                if (isImageCard) R.drawable.ic_image else R.drawable.ic_attach_file,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = effectiveFileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (effectiveId.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = effectiveId,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (effectiveDescription.isNotBlank() && effectiveDescription != effectiveFileName) {
                    Text(
                        text = effectiveDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (isImageCard) R.string.chat_file_card_image_label else R.string.chat_file_card_label,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FilledTonalButton(
                onClick = openAction,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_file_card_open),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun MessageAttachmentsSection(
    imageUris: List<String>,
    fileUris: List<String>,
    attachmentLookup: Map<String, SessionAttachment> = emptyMap(),
    onImageClick: (String) -> Unit = {},
    onFileClick: (attachmentId: String?, fileName: String, uri: String, fallbackUri: String) -> Unit = { _, _, _, _ -> },
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (imageUris.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                imageUris.forEach { uri ->
                    val matched = attachmentLookup[uri]
                    val displayUri = matched?.uri ?: uri
                    val thumbnail = rememberUriThumbnail(
                        uri = displayUri,
                        fallbackUri = matched?.originalUri ?: uri,
                        maxDimension = 320,
                    )
                    if (thumbnail != null) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImageClick(uri) },
                        ) {
                            Image(
                                bitmap = thumbnail,
                                contentDescription = matched?.normalizedFileName
                                    ?: formatAttachmentUriLabel(uri),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (matched != null) {
                                Surface(
                                    shape = RoundedCornerShape(topStart = 8.dp),
                                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                                    contentColor = Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                ) {
                                    Text(
                                        text = matched.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        AttachmentUriBadge(
                            iconRes = R.drawable.ic_image,
                            prefix = matched?.id ?: stringResource(R.string.chat_attachment_image_label),
                            uri = uri,
                            fileName = matched?.normalizedFileName,
                            onClick = { onImageClick(uri) },
                        )
                    }
                }
            }
        }
        if (fileUris.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fileUris.forEach { uri ->
                    val matched = attachmentLookup[uri]
                    val resolvedName = matched?.normalizedFileName ?: formatAttachmentUriLabel(uri)
                    AttachmentUriBadge(
                        iconRes = R.drawable.ic_attach_file,
                        prefix = matched?.id ?: stringResource(R.string.chat_attachment_file_label),
                        uri = uri,
                        fileName = resolvedName,
                        onClick = {
                            onFileClick(
                                matched?.id,
                                resolvedName,
                                matched?.uri ?: uri,
                                matched?.originalUri ?: uri,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentUriBadge(
    iconRes: Int,
    prefix: String,
    uri: String,
    fileName: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            val displayLabel = fileName?.takeIf { it.isNotBlank() } ?: formatAttachmentUriLabel(uri)
            val labelText = "$prefix: $displayLabel"
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}

/**
 * 全屏图片放大预览弹窗，支持：
 * - 双指捏合缩放（0.5x ～ 5.0x）；
 * - 单指/双指拖拽平移；
 * - 旋转或双击快速在 1.0x 与 2.5x 之间切换/重置；
 * - 顶部状态栏显示图片 ID/描述与一键重置/关闭按钮。
 */
@Composable
fun ZoomableImageViewerDialog(
    uri: String,
    fallbackUri: String = uri,
    title: String = "",
    onDismiss: () -> Unit,
) {
    val bitmap = rememberUriThumbnail(
        uri = uri,
        fallbackUri = fallbackUri,
        maxDimension = 2048,
    )
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var rotation by remember(uri) { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
        ) {
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uri) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.05f || offset != Offset.Zero || rotation != 0f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                        rotation = 0f
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        }
                        .pointerInput(uri) {
                            detectTransformGestures { _, pan, zoom, gestureRotate ->
                                val nextScale = (scale * zoom).coerceIn(0.5f, 5f)
                                scale = nextScale
                                offset += pan
                                if (kotlin.math.abs(gestureRotate) > 1.5f) {
                                    rotation = (rotation + gestureRotate) % 360f
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                rotationZ = rotation
                            },
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_image_viewer_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = uri,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // 顶部信息与操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title.ifBlank { stringResource(R.string.chat_attachment_image_label) },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (scale != 1f || offset != Offset.Zero || rotation != 0f) {
                        TextButton(
                            onClick = {
                                scale = 1f
                                offset = Offset.Zero
                                rotation = 0f
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.chat_image_viewer_reset),
                                color = Color.White,
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.18f),
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.chat_image_viewer_close),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }

            // 底部手势提示栏
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.chat_image_viewer_gesture_hint,
                        (scale * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberUriThumbnail(
    uri: String,
    fallbackUri: String = uri,
    maxDimension: Int = 320,
): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = uri,
        key2 = fallbackUri,
        key3 = maxDimension,
    ) {
        value = withContext(Dispatchers.IO) {
            val candidates = listOf(uri.trim(), fallbackUri.trim())
                .filter { it.isNotEmpty() }
                .distinct()
            var decoded: ImageBitmap? = null
            for (candidate in candidates) {
                val attempt = runCatching {
                    val parsed = Uri.parse(candidate)
                    val bytes = when {
                        candidate.startsWith("content://", ignoreCase = true) ->
                            context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                        candidate.startsWith("file:", ignoreCase = true) || candidate.startsWith("/") -> {
                            val file = if (candidate.startsWith("file:", ignoreCase = true)) {
                                File(parsed.path ?: return@runCatching null)
                            } else {
                                File(candidate)
                            }
                            if (file.exists() && file.canRead()) file.readBytes() else null
                        }
                        else -> null
                    } ?: return@runCatching null

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
                    val sample = if (maxEdge > maxDimension) {
                        (maxEdge / maxDimension).coerceAtLeast(1)
                    } else {
                        1
                    }
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.asImageBitmap()
                }.getOrNull()
                if (attempt != null) {
                    decoded = attempt
                    break
                }
            }
            decoded
        }
    }
    return bitmap
}

@Composable
private fun ToolStatusCard(item: ChatItem.ToolStatusItem) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.isCompleted) {
                Icon(
                    painter = painterResource(R.drawable.ic_build),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    if (item.isCompleted) R.string.chat_tool_done else R.string.chat_tool_running,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionRequestCard(
    item: ChatItem.PermissionRequestItem,
    onConfirm: () -> Unit,
    onRequestPrivilege: (AndroidPrivilege) -> Unit,
) {
    val badgeText = when {
        !item.isCompleted -> stringResource(R.string.chat_permission_request_badge_pending)
        item.isGranted -> stringResource(R.string.chat_permission_request_badge_granted)
        else -> stringResource(R.string.chat_permission_request_badge_denied)
    }
    val badgeColor = when {
        !item.isCompleted -> MaterialTheme.colorScheme.secondaryContainer
        item.isGranted -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val badgeContentColor = when {
        !item.isCompleted -> MaterialTheme.colorScheme.onSecondaryContainer
        item.isGranted -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_build),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_permission_request_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor,
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
                text = stringResource(
                    R.string.chat_permission_request_body,
                    item.toolsetTitle.ifBlank { item.toolsetId },
                    item.toolsetId,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (item.toolsetDescription.isNotBlank()) {
                Text(
                    text = item.toolsetDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!item.isCompleted) {
                Text(
                    text = stringResource(R.string.chat_permission_request_quick_grant_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.requiredPrivileges.forEach { privilege ->
                    val isMatched = privilege in item.matchedPrivileges
                    AssistChip(
                        onClick = {
                            if (!item.isCompleted) {
                                onRequestPrivilege(privilege)
                            }
                        },
                        enabled = !item.isCompleted,
                        label = {
                            Text(
                                text = ToolsetPermissionCatalog.privilegeDisplayName(privilege),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        leadingIcon = if (isMatched) {
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

            if (item.isCompleted && item.resultSummary.isNotBlank()) {
                val resultContainerColor = if (item.isGranted) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                }
                val resultContentColor = if (item.isGranted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = resultContainerColor,
                    contentColor = resultContentColor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = item.resultSummary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }

            if (!item.isCompleted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onConfirm) {
                        Text(text = stringResource(R.string.chat_permission_request_confirm))
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemNoticeCard(item: ChatItem.SystemNoticeItem) {
    val containerColor = if (item.isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (item.isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = item.message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun GeneratingIndicator(
    sessionState: ChatSessionState = ChatSessionState.Idle,
    activeToolName: String? = null,
) {
    val statusText = when (sessionState) {
        is ChatSessionState.Planning -> {
            val title = sessionState.currentCheckpointTitle.ifBlank { "思考中" }
            val base = if (sessionState.totalCheckpoints > 0) {
                stringResource(
                    R.string.chat_planning_status_with_total,
                    sessionState.checkpointIndex,
                    sessionState.totalCheckpoints,
                    title,
                )
            } else {
                stringResource(
                    R.string.chat_planning_status,
                    sessionState.checkpointIndex,
                    title,
                )
            }
            val withTool = if (!activeToolName.isNullOrBlank()) {
                base + stringResource(
                    R.string.chat_planning_calling_tool_suffix,
                    activeToolName,
                )
            } else {
                base
            }
            if (sessionState.queuedExternalInputCount > 0) {
                withTool + stringResource(
                    R.string.chat_planning_queued_suffix,
                    sessionState.queuedExternalInputCount,
                )
            } else {
                withTool
            }
        }

        is ChatSessionState.WaitingAnswer -> stringResource(R.string.chat_waiting_answer_status)
        ChatSessionState.Chatting, ChatSessionState.Idle -> {
            if (!activeToolName.isNullOrBlank()) {
                stringResource(R.string.chat_calling_tool_status, activeToolName)
            } else {
                stringResource(R.string.chat_generating)
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
