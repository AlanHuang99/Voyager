package com.voyagerfiles.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

enum class BrowserCreateAction(
    val label: String,
    val icon: ImageVector,
) {
    NEW_FOLDER("New Folder", Icons.Filled.CreateNewFolder),
    NEW_FILE("New File", Icons.AutoMirrored.Filled.NoteAdd),
    UPLOAD_FILES("Upload Files", Icons.Filled.UploadFile),
}

data class BrowserCreateMenuModel(
    val actions: List<BrowserCreateAction>,
) {
    companion object {
        fun forState(isRemote: Boolean): BrowserCreateMenuModel =
            BrowserCreateMenuModel(
                actions = buildList {
                    add(BrowserCreateAction.NEW_FOLDER)
                    add(BrowserCreateAction.NEW_FILE)
                    if (isRemote) add(BrowserCreateAction.UPLOAD_FILES)
                },
            )
    }
}

@Composable
fun BrowserCreateMenu(
    expanded: Boolean,
    model: BrowserCreateMenuModel,
    onDismiss: () -> Unit,
    onAction: (BrowserCreateAction) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        model.actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = { Icon(action.icon, null) },
                onClick = {
                    onDismiss()
                    onAction(action)
                },
            )
        }
    }
}
