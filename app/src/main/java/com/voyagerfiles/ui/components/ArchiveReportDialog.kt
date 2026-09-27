package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.data.archive.ArchiveExtractionReport
import com.voyagerfiles.ui.text.asString
import com.voyagerfiles.viewmodel.OperationMessages

/** Lists what an extraction renamed or left out, so nothing happens without the user knowing. */
@Composable
fun ArchiveReportDialog(report: ArchiveExtractionReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_details_title)) },
        text = {
            val listState = rememberLazyListState()
            // The dialog gives the text only the space between title and button, so the list may take
            // all of it; dividers show where content continues, as Material 3 does for scrolling dialogs.
            Column {
                HorizontalDivider(Modifier.alpha(if (listState.canScrollBackward) 1f else 0f))
                LazyColumn(Modifier.weight(1f, fill = false), state = listState) {
                    if (report.notExtracted.isNotEmpty()) {
                        item { Section(stringResource(R.string.archive_details_not_extracted)) }
                        items(report.notExtracted) { failed ->
                            Text(failed.entryPath, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                OperationMessages.reason(failed.error).asString(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        item { More(report.notExtractedCount - report.notExtracted.size) }
                    }
                    if (report.renamed.isNotEmpty()) {
                        item { Section(stringResource(R.string.archive_details_renamed)) }
                        items(report.renamed) { renamed ->
                            Text(
                                "${renamed.entryPath} → ${renamed.newName}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        item { More(report.renamedCount - report.renamed.size) }
                    }
                }
                HorizontalDivider(Modifier.alpha(if (listState.canScrollForward) 1f else 0f))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun More(hidden: Int) {
    if (hidden > 0) Text(pluralStringResource(R.plurals.archive_details_more, hidden, hidden), style = MaterialTheme.typography.bodySmall)
}

/** Removing cannot be undone, so the result card asks once before deleting the extracted files. */
@Composable
fun RemoveExtractionDialog(report: ArchiveExtractionReport, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_remove_title)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.archive_remove_message,
                    report.extractedFiles,
                    report.extractedFiles,
                    report.root.name,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.archive_remove_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
