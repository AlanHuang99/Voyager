package com.voyagerfiles.ui.components

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.viewmodel.ConflictDecision
import com.voyagerfiles.viewmodel.ConflictResponse
import com.voyagerfiles.viewmodel.TransferConflictDecisions
import java.text.DateFormat
import java.util.Date

@Composable
fun TransferConflictDialog(
    request: TransferConflictDecisions.Request,
    onDecision: (ConflictResponse) -> Unit,
) {
    var applyToAll by remember(request) { mutableStateOf(false) }
    val conflict = request.conflict
    AlertDialog(
        onDismissRequest = { onDecision(ConflictResponse(ConflictDecision.CANCEL)) },
        title = { Text(stringResource(R.string.transfer_conflict_title, conflict.source.name)) },
        text = {
            Column {
                Text(stringResource(R.string.transfer_conflict_source, metadata(conflict.source.size, conflict.source.lastModified)))
                Text(stringResource(R.string.transfer_conflict_destination, metadata(conflict.destination.size.takeIf { it >= 0 }, conflict.destination.lastModified)))
                Row(Modifier.fillMaxWidth().toggleable(value = applyToAll, role = Role.Checkbox, onValueChange = { applyToAll = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyToAll, onCheckedChange = null)
                    Text(stringResource(R.string.transfer_conflict_apply_all))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(ConflictResponse(ConflictDecision.REPLACE, applyToAll)) }) {
                Text(stringResource(R.string.transfer_conflict_replace))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDecision(ConflictResponse(ConflictDecision.CANCEL)) }) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(onClick = { onDecision(ConflictResponse(ConflictDecision.SKIP, applyToAll)) }) {
                    Text(stringResource(R.string.transfer_conflict_skip))
                }
            }
        },
    )
}

@Composable
private fun metadata(size: Long?, time: Date?): String {
    val unknown = stringResource(R.string.transfer_conflict_unknown)
    return "${size?.let(FileItem::formatFileSize) ?: unknown} • ${time?.takeIf { it.time > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it) } ?: unknown}"
}
