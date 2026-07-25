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
    val validationError = (validation as? FileNameValidationResult.Invalid)?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress to ZIP") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Archive name") },
                isError = validationError != null,
                supportingText = validationError?.let { message -> { Text(message) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { validatedName?.let(onCreate) },
                enabled = validatedName != null,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
                    FileNameValidationResult.Invalid("Names cannot contain backslashes")
                validation.name.endsWith(".zip", ignoreCase = true) -> validation
                else -> FileNameValidationResult.Invalid("Name must end with .zip")
            }
        }
    }
