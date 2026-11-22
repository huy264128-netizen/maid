package top.aaa000111.maid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.aaa000111.maid.R
import top.aaa000111.maid.provider.ModelConfigGroup
import top.aaa000111.maid.provider.ModelGroups

@Composable
internal fun ModelGroupPicker(
    groups: ModelGroups,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    compact: Boolean = false,
    onCreateGroup: (() -> Unit)? = null,
    creating: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = groups.groups.firstOrNull { it.id == groups.activeId }
    val canOpen = enabled && (groups.groups.isNotEmpty() || onOpenSettings != null || onCreateGroup != null)
    Box {
        if (compact) {
            AssistChip(
                onClick = { expanded = true },
                enabled = canOpen,
                modifier = Modifier.widthIn(max = 200.dp),
                label = {
                    Text(modelGroupName(selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_expand_more),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        } else {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = canOpen,
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Text(
                    if (creating) stringResource(R.string.model_group_create)
                    else stringResource(R.string.model_group_selected, modelGroupName(selected)),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            groups.groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(modelGroupName(group), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = if (!creating && group.id == groups.activeId) {
                        { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.model_group_active)) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelect(group.id)
                    },
                )
            }
            if (onCreateGroup != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.model_group_create)) },
                    onClick = {
                        expanded = false
                        onCreateGroup()
                    },
                )
            }
            if (onOpenSettings != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.model_group_manage)) },
                    onClick = {
                        expanded = false
                        onOpenSettings()
                    },
                )
            }
        }
    }
}

@Composable
private fun modelGroupName(group: ModelConfigGroup?): String =
    group?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.model_group_default)
