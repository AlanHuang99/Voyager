package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.voyagerfiles.R
import com.voyagerfiles.util.FileNameValidationResult
import com.voyagerfiles.util.FileNameValidator

@Composable
fun ArchiveNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val validation = validateZipArchiveName(name)
    val validatedName = (validation as? FileNameValidationResult.Valid)?.name
    val validationErrorRes = (validation as? FileNameValidationResult.Invalid)?.messageRes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_compress_zip)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.dialog_archive_name)) },
                isError = validationErrorRes != null,
                supportingText = validationErrorRes?.let { messageRes ->
                    { Text(stringResource(messageRes)) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { validatedName?.let(onCreate) },
                enabled = validatedName != null,
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

internal fun validateZipArchiveName(name: String): FileNameValidationResult =
    when (val validation = FileNameValidator.validate(name)) {
        is FileNameValidationResult.Invalid -> validation
        is FileNameValidationResult.Valid -> {
            when {
                '\\' in validation.name ->
                    FileNameValidationResult.Invalid(R.string.name_no_backslashes)
                validation.name.endsWith(".zip", ignoreCase = true) -> validation
                else -> FileNameValidationResult.Invalid(R.string.name_must_end_zip)
            }
        }
    }
