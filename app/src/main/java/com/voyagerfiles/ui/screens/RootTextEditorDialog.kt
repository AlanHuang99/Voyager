package com.voyagerfiles.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.data.repository.RootFileProvider
import com.voyagerfiles.viewmodel.RootTextEditorState

@Composable
internal fun RootTextEditorDialog(state: RootTextEditorState, onChange: (String) -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    var confirmDiscard by rememberSaveable(state.path) { mutableStateOf(false) }
    val dismiss = { if (!state.busy) { if (state.changed) confirmDiscard = true else onClose() } }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.root_editor_title)) },
        text = {
            Column {
                Text(state.path, style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.root_editor_description), style = MaterialTheme.typography.bodySmall)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.document != null) {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= RootFileProvider.MAX_TEXT_BYTES) onChange(it) },
                        enabled = !state.busy,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 400.dp),
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = state.changed && !state.busy) { Text(stringResource(R.string.root_editor_save)) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            text = { Text(stringResource(R.string.root_editor_discard)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
