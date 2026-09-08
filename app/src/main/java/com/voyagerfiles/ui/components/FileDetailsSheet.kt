package com.voyagerfiles.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailsSheet(
    file: FileItem,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modified = remember(file.lastModified) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(file.lastModified)
    }
    val nameLabel = stringResource(R.string.details_name)
    val typeLabel = stringResource(R.string.details_type)
    val sizeLabel = stringResource(R.string.details_size)
    val modifiedLabel = stringResource(R.string.details_modified)
    val sourceLabel = stringResource(R.string.details_source)
    val pathLabel = stringResource(R.string.details_path)
    val ownerLabel = stringResource(R.string.details_owner)
    val permissionsLabel = stringResource(R.string.details_permissions)
    val folderLabel = stringResource(R.string.details_folder)
    val fileSourceLabel = stringResource(file.source.displayLabelRes)
    val rows = buildList {
        add(nameLabel to file.name)
        add(typeLabel to if (file.isDirectory) folderLabel else file.mimeType)
        if (!file.isDirectory) add(sizeLabel to file.formattedSize)
        add(modifiedLabel to modified)
        add(sourceLabel to fileSourceLabel)
        add(pathLabel to file.path)
        file.owner?.takeIf(String::isNotBlank)?.let { add(ownerLabel to it) }
        file.permissions?.takeIf(String::isNotBlank)?.let { add(permissionsLabel to it) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(R.string.details_title), style = MaterialTheme.typography.headlineSmall)
            }
            items(rows, key = { it.first }) { (label, value) ->
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@get:StringRes
private val FileSource.displayLabelRes: Int
    get() = when (this) {
        FileSource.LOCAL -> R.string.details_source_local
        FileSource.SAF -> R.string.details_source_saf
        FileSource.SFTP -> R.string.protocol_sftp
        FileSource.FTP -> R.string.protocol_ftp
        FileSource.SMB -> R.string.protocol_smb
        FileSource.WEBDAV -> R.string.protocol_webdav
        FileSource.ROOT -> R.string.root_title
    }
