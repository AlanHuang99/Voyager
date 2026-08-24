package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.voyagerfiles.R
import com.voyagerfiles.ui.text.UiText
import com.voyagerfiles.ui.text.asString

data class DeleteChoiceDialogModel(
    val title: UiText,
    val message: UiText,
    val trashLabel: UiText = UiText.Resource(R.string.dialog_move_to_trash),
    val permanentLabel: UiText = UiText.Resource(R.string.dialog_delete_permanently),
) {
    companion object {
        fun local(count: Int, fileName: String): DeleteChoiceDialogModel =
            DeleteChoiceDialogModel(
                title = if (count == 1) {
                    UiText.Resource(R.string.dialog_delete_named_title, listOf(UiText.Dynamic(fileName)))
                } else {
                    UiText.Plural(R.plurals.dialog_delete_items_title, count, listOf(count))
                },
                message = UiText.Resource(R.string.dialog_delete_choice_message),
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
        title = { Text(model.title.asString()) },
        text = { Text(model.message.asString()) },
        confirmButton = {
            TextButton(onClick = onMoveToTrash) { Text(model.trashLabel.asString()) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(UiText.Resource(R.string.action_cancel).asString()) }
                TextButton(onClick = onDeletePermanently) {
                    Text(model.permanentLabel.asString(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
