package top.aaa000111.maid.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import top.aaa000111.maid.R
import top.aaa000111.maid.ui.ModelGroupPicker

@Composable
fun ChatModelControls(
    state: ChatModelGroupControlsState,
    enabled: Boolean,
    onSelectGroup: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
    ) {
        ModelGroupPicker(
            groups = state.groups,
            enabled = enabled && !state.isSwitching,
            onSelect = onSelectGroup,
            onOpenSettings = onOpenSettings,
            compact = true,
        )
        if (state.switchFailed) {
            Text(
                stringResource(R.string.model_group_switch_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
