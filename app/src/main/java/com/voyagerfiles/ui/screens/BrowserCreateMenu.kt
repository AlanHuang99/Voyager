package com.voyagerfiles.ui.screens

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import com.voyagerfiles.R

enum class BrowserCreateAction(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    NEW_FOLDER(R.string.create_new_folder, Icons.Filled.CreateNewFolder),
    NEW_FILE(R.string.create_new_file, Icons.AutoMirrored.Filled.NoteAdd),
    UPLOAD_FILES(R.string.create_upload_files, Icons.Filled.UploadFile),
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
                text = { Text(stringResource(action.labelRes)) },
                leadingIcon = { Icon(action.icon, null) },
                onClick = {
                    onDismiss()
                    onAction(action)
                },
            )
        }
    }
}
