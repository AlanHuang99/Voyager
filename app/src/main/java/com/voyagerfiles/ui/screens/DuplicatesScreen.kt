package com.voyagerfiles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.ui.components.DeleteChoiceDialog
import com.voyagerfiles.ui.components.DeleteChoiceDialogModel
import com.voyagerfiles.ui.text.asString
import com.voyagerfiles.viewmodel.DuplicateViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(path: String, onNavigateBack: () -> Unit, viewModel: DuplicateViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val excludedKeywords by viewModel.excludedDirectoryKeywords.collectAsState()
    var confirmRemoval by remember { mutableStateOf(false) }
    var showExclusions by rememberSaveable { mutableStateOf(false) }
    var exclusionText by rememberSaveable { mutableStateOf("") }
    BackHandler { if (!state.removing) onNavigateBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.duplicates_title)) }, navigationIcon = {
            IconButton(onClick = onNavigateBack, enabled = !state.removing) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(path, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.duplicates_description))
                TextButton(onClick = {
                    exclusionText = excludedKeywords.sorted().joinToString(", ")
                    showExclusions = true
                }, enabled = !state.scanning && !state.removing) {
                    Text(stringResource(R.string.duplicates_exclude_folders))
                }
                Row {
                    TextButton(onClick = { viewModel.scan(path) }, enabled = !state.scanning && !state.removing) {
                        Text(stringResource(R.string.duplicates_scan))
                    }
                    if (state.scanning) TextButton(onClick = viewModel::cancelScan) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = { confirmRemoval = true }, enabled = state.selected.isNotEmpty() && !state.removing && !state.scanning) {
                        Text(stringResource(R.string.duplicates_remove, state.selected.size))
                    }
                }
                if (state.scanning || state.removing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (state.removing) R.string.duplicates_removing else R.string.duplicates_scanning))
                    if (state.scanning) Text(stringResource(R.string.duplicates_examined, state.progress.examined))
                }
                state.message?.let { Text(it.asString()) }
            }
            state.result?.let { scan ->
                item {
                    Text(stringResource(R.string.duplicates_summary, scan.groups.size, scan.filesExamined))
                    Text(stringResource(R.string.duplicates_scan_coverage, scan.unreadable, scan.missing, scan.changed, scan.linksSkipped, scan.excludedDirectories),
                        style = MaterialTheme.typography.bodySmall)
                    if (scan.limited) Text(stringResource(R.string.duplicates_limited), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.duplicates_keep_one), style = MaterialTheme.typography.bodySmall)
                }
                scan.groups.forEach { group ->
                    item(key = "group-${group.id}") {
                        Text(stringResource(R.string.duplicates_group, group.files.size, FileItem.formatFileSize(group.files.first().size)), style = MaterialTheme.typography.titleSmall)
                    }
                    items(group.files, key = { it.path }) { file ->
                        Row(Modifier.fillMaxWidth().toggleable(value = file.path in state.selected, enabled = !state.removing, role = Role.Checkbox) { viewModel.toggle(file.path) }.padding(vertical = 4.dp)) {
                            Checkbox(checked = file.path in state.selected, onCheckedChange = null)
                            Column {
                                Text(File(file.path).name)
                                Text(file.path, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showExclusions) {
        AlertDialog(
            onDismissRequest = { showExclusions = false },
            title = { Text(stringResource(R.string.duplicates_exclude_folders)) },
            text = {
                OutlinedTextField(
                    value = exclusionText,
                    onValueChange = { exclusionText = it },
                    label = { Text(stringResource(R.string.duplicates_exclusion_keywords)) },
                    placeholder = { Text(stringResource(R.string.duplicates_exclusion_example)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setExcludedDirectoryKeywords(exclusionText.split(',').toSet())
                    showExclusions = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showExclusions = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (confirmRemoval && !state.removing) {
        DeleteChoiceDialog(DeleteChoiceDialogModel.local(state.selected.size, state.selected.firstOrNull()?.let { File(it).name }.orEmpty()),
            onDismiss = { confirmRemoval = false },
            onMoveToTrash = { confirmRemoval = false; viewModel.remove(useTrash = true) },
            onDeletePermanently = { confirmRemoval = false; viewModel.remove(useTrash = false) })
    }
}
