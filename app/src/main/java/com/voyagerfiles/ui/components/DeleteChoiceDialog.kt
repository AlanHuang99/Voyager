package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

data class DeleteChoiceDialogModel(
    val title: String,
    val message: String,
    val trashLabel: String = "Move to Trash",
    val permanentLabel: String = "Delete permanently",
) {
    companion object {
        fun local(count: Int, fileName: String): DeleteChoiceDialogModel =
            DeleteChoiceDialogModel(
                title = if (count == 1) "Delete \"$fileName\"?" else "Delete $count items?",
                message = "Move the selection to Trash so it can be restored later, or delete it permanently. Permanent deletion cannot be undone.",
            )
    }
}

@Composable
fun DeleteChoiceDialog(
    model: DeleteChoiceDialogModel,
    onDismiss: () -> Unit,
    onMoveToTrash: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.title) },
        text = { Text(model.message) },
        confirmButton = {
            TextButton(onClick = onMoveToTrash) { Text(model.trashLabel) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onDeletePermanently) {
                    Text(model.permanentLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
