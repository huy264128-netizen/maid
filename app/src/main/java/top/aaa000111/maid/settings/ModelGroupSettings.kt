package top.aaa000111.maid.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.aaa000111.maid.R
import top.aaa000111.maid.provider.ModelGroups
import top.aaa000111.maid.ui.ModelGroupPicker

@Composable
internal fun ModelGroupSettings(
    groups: ModelGroups,
    enabled: Boolean,
    onActivate: (String) -> Unit,
    onCreate: () -> Unit,
    creating: Boolean,
    name: String,
    duplicateName: Boolean,
    onNameChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelGroupPicker(
            groups = groups,
            enabled = enabled,
            onSelect = onActivate,
            onCreateGroup = onCreate.takeUnless { creating },
            creating = creating,
        )
        if (creating) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text(stringResource(R.string.model_group_name)) },
                singleLine = true,
                isError = duplicateName,
                supportingText = if (duplicateName) {
                    { Text(stringResource(R.string.model_group_duplicate)) }
                } else null,
            )
        }
    }
}
