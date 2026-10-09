package com.voyagerfiles.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.data.archive.ArchiveExtractionReport
import com.voyagerfiles.ui.components.ArchiveReportDialog
import com.voyagerfiles.ui.text.asString
import com.voyagerfiles.viewmodel.OperationOutcome
import com.voyagerfiles.viewmodel.OperationResult

@Composable
internal fun OperationResultContent(
    result: OperationResult,
    onDismiss: () -> Unit,
    onRemoveExtraction: ((ArchiveExtractionReport) -> Unit)? = null,
) {
    val status = stringResource(when (result.outcome) {
        OperationOutcome.COMPLETED -> R.string.transfer_completed
        OperationOutcome.FAILED -> R.string.transfer_finished_with_errors
        OperationOutcome.CANCELLED -> R.string.transfer_cancelled
    })
    val report = result.archiveReport
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(result.progress.label.asString(), style = MaterialTheme.typography.labelMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (report == null && result.progress.skippedItems > 0) {
                Text(stringResource(R.string.transfer_items_skipped, result.progress.skippedItems), style = MaterialTheme.typography.bodySmall)
            }
            result.progress.totalItems?.let {
                Text(
                    stringResource(R.string.transfer_items_completed, result.progress.completedItems, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (report != null) ArchiveReportSummary(report, onRemoveExtraction)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, stringResource(R.string.transfer_dismiss_result))
        }
    }
}

@Composable
private fun ArchiveReportSummary(
    report: ArchiveExtractionReport,
    onRemoveExtraction: ((ArchiveExtractionReport) -> Unit)?,
) {
    var showDetails by remember(report) { mutableStateOf(false) }
    if (report.renamedCount > 0) {
        Text(
            pluralStringResource(R.plurals.archive_entries_renamed, report.renamedCount, report.renamedCount),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (report.notExtractedCount > 0) {
        Text(
            pluralStringResource(R.plurals.archive_entries_not_extracted, report.notExtractedCount, report.notExtractedCount),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val canRemove = !report.complete && onRemoveExtraction != null
    val hasDetails = report.renamedCount > 0 || report.notExtractedCount > 0
    if (hasDetails || canRemove) {
        Row {
            if (hasDetails) {
                TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.archive_details)) }
            }
            if (canRemove) {
                // Hands over to the browser's delete confirmation, which shows the folder and offers Trash.
                TextButton(onClick = { onRemoveExtraction?.invoke(report) }) { Text(stringResource(R.string.archive_remove_extracted)) }
            }
        }
    }
    if (showDetails) ArchiveReportDialog(report, onDismiss = { showDetails = false })
}
