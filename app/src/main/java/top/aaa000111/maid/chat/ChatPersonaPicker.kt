package top.aaa000111.maid.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import top.aaa000111.maid.settings.PersonaConfig
import top.aaa000111.maid.settings.PersonaPreset

@Composable
internal fun ChatPersonaPicker(
    presets: List<PersonaPreset>?,
    persona: PersonaConfig,
    enabled: Boolean,
    onOpen: () -> Unit,
    onSelect: (PersonaPreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedId = persona.selectedPresetId
    val selectedName = presets?.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(
            if (persona.isConfigured && selectedId == null) R.string.chat_persona_custom
            else R.string.chat_persona_label,
        )
    Box {
        AssistChip(
            onClick = {
                onOpen()
                expanded = true
            },
            enabled = enabled,
            label = { Text(selectedName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = stringResource(R.string.chat_persona_picker),
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            if (presets.isNullOrEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(
                            if (presets == null) R.string.settings_persona_presets_scanning
                            else R.string.chat_persona_no_presets,
                        ))
                    },
                    enabled = false,
                    onClick = {},
                )
            } else {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = if (preset.id == selectedId) {
                            {
                                Icon(
                                    painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.chat_persona_selected),
                                )
                            }
                        } else null,
                        onClick = {
                            expanded = false
                            if (preset.id != selectedId) onSelect(preset)
                        },
                    )
                }
            }
        }
    }
}
