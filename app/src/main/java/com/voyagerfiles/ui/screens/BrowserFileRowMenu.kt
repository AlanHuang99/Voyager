package com.voyagerfiles.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.audio.AudioTone
import com.voyagerfiles.audio.AudioToneInstaller
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.ui.components.AudioToneMenuItems
import com.voyagerfiles.ui.text.asString
import com.voyagerfiles.util.ShareIntentPlan

/** Actions offered by a list row's own menu, in the order they are shown. */
enum class FileRowAction {
    OPEN_WITH,
    SHARE,
    DOWNLOAD,
    COPY,
    CUT,
    RENAME,
    COMPRESS_TO_ZIP,
    EXTRACT_HERE,
    EXTRACTION_UNSUPPORTED,
    FOLDER_SHORTCUTS,
    AUDIO_TONES,
    DETAILS,
    DELETE,
}

/** Single-file actions derived from the selection toolbar and archive action rules. */
data class FileRowMenuModel(val actions: List<FileRowAction>) {
    companion object {
        fun forFile(file: FileItem, isRemote: Boolean): FileRowMenuModel {
            val items = listOf(file)
            val selection = SelectionToolbarModel.forState(
                isRemote = isRemote,
                selectionCount = 1,
                canShare = ShareIntentPlan.forFiles(items) != null,
                canOpenWith = canOpenWith(file),
            )
            val toolbarActions = (selection.primaryActions + selection.overflowActions).toSet()
            val archiveActions = BrowserArchiveActions.forSelection(items)
            val available = buildSet {
                toolbarActions.forEach { action ->
                    when (action) {
                        SelectionToolbarAction.COPY -> add(FileRowAction.COPY)
                        SelectionToolbarAction.CUT -> add(FileRowAction.CUT)
                        SelectionToolbarAction.DELETE -> add(FileRowAction.DELETE)
                        SelectionToolbarAction.DOWNLOAD -> add(FileRowAction.DOWNLOAD)
                        SelectionToolbarAction.RENAME -> add(FileRowAction.RENAME)
                        SelectionToolbarAction.SHARE -> add(FileRowAction.SHARE)
                        SelectionToolbarAction.DETAILS -> add(FileRowAction.DETAILS)
                        SelectionToolbarAction.OPEN_WITH -> add(FileRowAction.OPEN_WITH)
                        SelectionToolbarAction.SELECT_ALL -> Unit
                    }
                }
                archiveActions.forEach { action ->
                    when (action) {
                        BrowserArchiveAction.COMPRESS_TO_ZIP -> add(FileRowAction.COMPRESS_TO_ZIP)
                        BrowserArchiveAction.EXTRACT_HERE -> add(FileRowAction.EXTRACT_HERE)
                        BrowserArchiveAction.EXTRACTION_UNSUPPORTED -> add(FileRowAction.EXTRACTION_UNSUPPORTED)
                    }
                }
                if (file.isDirectory && file.source == FileSource.LOCAL) add(FileRowAction.FOLDER_SHORTCUTS)
                if (AudioToneInstaller.isSupported(file)) add(FileRowAction.AUDIO_TONES)
            }
            return FileRowMenuModel(FileRowAction.entries.filter { it in available })
        }
    }
}

internal fun canOpenWith(item: FileItem): Boolean =
    !item.isDirectory &&
        (item.source == FileSource.LOCAL || item.source == FileSource.SAF || item.source == FileSource.WEBDAV)

internal const val FILE_ROW_MENU_BUTTON_TEST_TAG = "file-row-menu-button"

@Composable
internal fun BrowserFileRowMenu(
    file: FileItem,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isRemote: Boolean,
    enabled: Boolean,
    bookmarked: Boolean,
    onAction: (FileRowAction) -> Unit,
    onBookmark: () -> Unit,
    onPin: () -> Unit,
    onFindDuplicates: () -> Unit,
    onAudioTone: (FileItem, AudioTone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = remember(file, isRemote) { FileRowMenuModel.forFile(file, isRemote) }

    fun choose(action: FileRowAction) {
        onExpandedChange(false)
        onAction(action)
    }

    // The whole area the row gives us is the button; the ripple stays an icon-sized circle.
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 20.dp),
                onClick = { onExpandedChange(true) },
            )
            .testTag(FILE_ROW_MENU_BUTTON_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MoreVert,
            stringResource(R.string.content_desc_more_options_named, file.name),
            tint = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f),
        )
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            model.actions.forEach { action ->
                when (action) {
                    FileRowAction.OPEN_WITH ->
                        RowMenuItem(R.string.action_open_with, Icons.AutoMirrored.Filled.OpenInNew) { choose(action) }
                    FileRowAction.SHARE ->
                        RowMenuItem(R.string.action_share, Icons.Filled.Share) { choose(action) }
                    FileRowAction.DOWNLOAD ->
                        RowMenuItem(R.string.action_download, Icons.Filled.Download) { choose(action) }
                    FileRowAction.COPY ->
                        RowMenuItem(R.string.action_copy, Icons.Filled.ContentCopy) { choose(action) }
                    FileRowAction.CUT ->
                        RowMenuItem(R.string.action_cut, Icons.Filled.ContentCut) { choose(action) }
                    FileRowAction.RENAME ->
                        RowMenuItem(R.string.action_rename, Icons.Filled.DriveFileRenameOutline) { choose(action) }
                    FileRowAction.COMPRESS_TO_ZIP ->
                        RowMenuItem(R.string.dialog_compress_zip, Icons.Filled.Archive) { choose(action) }
                    FileRowAction.EXTRACT_HERE ->
                        RowMenuItem(R.string.action_extract_here, Icons.Filled.FolderOpen) { choose(action) }
                    FileRowAction.EXTRACTION_UNSUPPORTED -> DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.action_extract_here))
                                BrowserArchiveActions.unsupportedExtractionReason(listOf(file))
                                    ?.asString()
                                    ?.let { reason ->
                                        Text(text = reason, style = MaterialTheme.typography.bodySmall)
                                    }
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                        enabled = false,
                        onClick = {},
                    )
                    FileRowAction.FOLDER_SHORTCUTS -> FolderMenuItems(
                        bookmarked = bookmarked,
                        canFindDuplicates = enabled,
                        onBookmark = { onExpandedChange(false); onBookmark() },
                        onPin = { onExpandedChange(false); onPin() },
                        onFindDuplicates = { onExpandedChange(false); onFindDuplicates() },
                    )
                    FileRowAction.AUDIO_TONES -> AudioToneMenuItems(file) { selected, tone ->
                        onExpandedChange(false)
                        onAudioTone(selected, tone)
                    }
                    FileRowAction.DETAILS ->
                        RowMenuItem(R.string.details_title, Icons.Filled.Info) { choose(action) }
                    FileRowAction.DELETE ->
                        RowMenuItem(R.string.action_delete, Icons.Filled.Delete) { choose(action) }
                }
            }
        }
    }
}

@Composable
private fun RowMenuItem(label: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
    )
}
