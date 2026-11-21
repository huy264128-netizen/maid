package top.aaa000111.maid.proot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.vtfx.compose.Terminal
import com.google.vtfx.compose.TerminalStyle
import com.google.vtfx.compose.rememberTerminalState
import com.google.vtfx.core.KeyModifiers
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.LinkPattern
import com.google.vtfx.core.TerminalKey
import top.aaa000111.maid.R
import top.aaa000111.maid.ui.theme.MaidTheme

/**
 * 基于 Google `vtfx` (`com.google.vtfx.compose.Terminal`) 的交互式 PRoot 终端界面。
 * 终端底层直接连接全局单一 `rootfs` (`<filesDir>/rootfs`) 的持久 [ProotConnection]。
 */
class ProotTerminalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaidTheme {
                ProotTerminalScreen(
                    onNavigateBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun ProotTerminalScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(context) {
        ProotVtfxTerminalManager.getOrCreate(context)
    }
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    val connectionStatus by ProotConnectionManager.status.collectAsStateWithLifecycle()
    val terminalState = rememberTerminalState()

    LaunchedEffect(controller) {
        controller.ensureConnected()
    }

    val isConnected = connectionStatus.isConnected || uiState.isConnected
    val generation = if (connectionStatus.sessionGeneration > 0L) {
        connectionStatus.sessionGeneration
    } else {
        uiState.sessionGeneration
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212),
        contentColor = Color(0xFFE5E5E5),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            ProotTerminalTopBar(
                title = uiState.title.ifBlank { stringResource(R.string.proot_terminal_title) },
                isConnected = isConnected,
                sessionGeneration = generation,
                columns = uiState.columns,
                rows = uiState.rows,
                onNavigateBack = onNavigateBack,
                onClearClick = controller::clearTerminal,
                onReconnectClick = controller::reconnectProot,
            )

            Terminal(
                session = controller.session,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = terminalState,
                style = TerminalStyle(fontSize = 14.sp),
                linkPatterns = listOf(LinkPattern.Urls),
            )

            ProotVtfxKeyBar(
                stickyModifiers = uiState.stickyModifiers,
                directInput = uiState.directInput,
                onKey = controller::sendKey,
                onToggleCtrl = controller::toggleCtrl,
                onToggleAlt = controller::toggleAlt,
                onToggleDirectInput = controller::toggleDirectInput,
            )
        }
    }
}

@Composable
private fun ProotTerminalTopBar(
    title: String,
    isConnected: Boolean,
    sessionGeneration: Long,
    columns: Int,
    rows: Int,
    onNavigateBack: () -> Unit,
    onClearClick: () -> Unit,
    onReconnectClick: () -> Unit,
) {
    Surface(
        color = Color(0xFF1C1C1E),
        contentColor = Color(0xFFE5E5E5),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.settings_navigate_back),
                        tint = Color(0xFFE5E5E5),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE5E5E5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                ),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) {
                                stringResource(
                                    R.string.proot_terminal_status_connected,
                                    sessionGeneration.coerceAtLeast(1L),
                                    columns,
                                    rows,
                                )
                            } else {
                                stringResource(
                                    R.string.proot_terminal_status_disconnected,
                                    columns,
                                    rows,
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF9E9E9E),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                TextButton(onClick = onClearClick) {
                    Text(
                        text = stringResource(R.string.proot_terminal_clear),
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp,
                    )
                }

                TextButton(onClick = onReconnectClick) {
                    Text(
                        text = stringResource(R.string.proot_terminal_reconnect),
                        color = Color(0xFF81C784),
                        fontSize = 12.sp,
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF2C2C2E))
        }
    }
}

/**
 * 终端底部辅助按键栏（参考 `vtfx-sample/KeyBar.kt`）：
 * 提供 `Esc`、`Tab`、`Ctrl`、`Alt`、方向键、`Ctrl+C` 与 `Direct` 输入模式开关，
 * 且不会抢占 `vtfx` `Terminal` 视图的软键盘焦点。
 */
@Composable
private fun ProotVtfxKeyBar(
    stickyModifiers: KeyModifiers,
    directInput: Boolean,
    onKey: (KeyStroke) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleDirectInput: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AuxiliaryKeyButton("Esc") {
            onKey(KeyStroke.Special(TerminalKey.Escape))
        }
        AuxiliaryKeyButton("Tab") {
            onKey(KeyStroke.Special(TerminalKey.Tab))
        }
        AuxiliaryKeyButton(
            label = "Ctrl",
            active = stickyModifiers.ctrl,
            toggle = true,
            onClick = onToggleCtrl,
        )
        AuxiliaryKeyButton(
            label = "Alt",
            active = stickyModifiers.alt,
            toggle = true,
            onClick = onToggleAlt,
        )
        AuxiliaryKeyButton("←", description = "Left arrow") {
            onKey(KeyStroke.Special(TerminalKey.Left))
        }
        AuxiliaryKeyButton("↓", description = "Down arrow") {
            onKey(KeyStroke.Special(TerminalKey.Down))
        }
        AuxiliaryKeyButton("↑", description = "Up arrow") {
            onKey(KeyStroke.Special(TerminalKey.Up))
        }
        AuxiliaryKeyButton("→", description = "Right arrow") {
            onKey(KeyStroke.Special(TerminalKey.Right))
        }
        AuxiliaryKeyButton("Ctrl+C", description = "Interrupt") {
            onKey(
                KeyStroke.Character(
                    codePoint = 'c'.code,
                    modifiers = KeyModifiers.of(ctrl = true),
                    unshiftedCodePoint = 'c'.code,
                    baseLayoutCodePoint = 'c'.code,
                ),
            )
        }
        AuxiliaryKeyButton(
            label = "Direct",
            active = directInput,
            toggle = true,
            onClick = onToggleDirectInput,
        )
    }
}

@Composable
private fun AuxiliaryKeyButton(
    label: String,
    description: String? = null,
    active: Boolean = false,
    toggle: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (active) Color(0xFF3A5A7C) else Color(0xFF2C2C2E)
    Box(
        modifier = Modifier
            .semantics {
                role = if (toggle) Role.Switch else Role.Button
                if (toggle) selected = active
                if (description != null) contentDescription = description
            }
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFFE5E5E5),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}
