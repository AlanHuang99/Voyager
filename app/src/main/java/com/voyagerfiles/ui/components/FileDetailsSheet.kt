package com.voyagerfiles.ui.components

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
import androidx.compose.ui.unit.dp
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
    val rows = buildList {
        add("Name" to file.name)
        add("Type" to if (file.isDirectory) "Folder" else file.mimeType)
        if (!file.isDirectory) add("Size" to file.formattedSize)
        add("Modified" to modified)
        add("Source" to file.source.displayLabel)
        add("Path" to file.path)
        file.owner?.takeIf(String::isNotBlank)?.let { add("Owner" to it) }
        file.permissions?.takeIf(String::isNotBlank)?.let { add("Permissions" to it) }
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
                Text("Details", style = MaterialTheme.typography.headlineSmall)
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

private val FileSource.displayLabel: String
    get() = when (this) {
        FileSource.LOCAL -> "Local storage"
        FileSource.SAF -> "Document tree"
        FileSource.SFTP -> "SFTP"
        FileSource.FTP -> "FTP"
        FileSource.SMB -> "SMB"
        FileSource.WEBDAV -> "WebDAV"
    }
