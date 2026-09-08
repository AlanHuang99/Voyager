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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.ui.text.asString
import com.voyagerfiles.viewmodel.OperationOutcome
import com.voyagerfiles.viewmodel.OperationResult

@Composable
internal fun OperationResultContent(result: OperationResult, onDismiss: () -> Unit) {
    val status = stringResource(when (result.outcome) {
        OperationOutcome.COMPLETED -> R.string.transfer_completed
        OperationOutcome.FAILED -> R.string.transfer_finished_with_errors
        OperationOutcome.CANCELLED -> R.string.transfer_cancelled
    })
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(result.progress.label.asString(), style = MaterialTheme.typography.labelMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            result.progress.totalItems?.let {
                Text(
                    stringResource(R.string.transfer_items_completed, result.progress.completedItems, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, stringResource(R.string.transfer_dismiss_result))
        }
    }
}
