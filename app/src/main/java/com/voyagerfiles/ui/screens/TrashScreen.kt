package com.voyagerfiles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voyagerfiles.data.model.TrashEntry
import com.voyagerfiles.ui.components.DeleteConfirmDialog
import com.voyagerfiles.ui.components.DeleteDialogModel
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.OperationState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.trashState.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val runningOperation = operationState as? OperationState.Running
    val isSelectionMode = state.selectedIds.isNotEmpty()
    var showPermanentDeleteDialog by remember { mutableStateOf(false) }
    var showEmptyDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshTrash() }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    BackHandler {
        if (isSelectionMode) viewModel.clearTrashSelection() else onNavigateBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isSelectionMode) "${state.selectedIds.size} selected" else "Trash")
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSelectionMode) viewModel.clearTrashSelection() else onNavigateBack()
                        },
                    ) {
                        Icon(
                            if (isSelectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            if (isSelectionMode) "Clear selection" else "Back",
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(
                            onClick = viewModel::restoreSelectedTrash,
                            enabled = runningOperation == null,
                        ) {
                            Icon(Icons.Filled.Restore, "Restore selected")
                        }
                        IconButton(
                            onClick = { showPermanentDeleteDialog = true },
                            enabled = runningOperation == null,
                        ) {
                            Icon(Icons.Filled.DeleteForever, "Delete selected permanently")
                        }
                    } else if (state.entries.isNotEmpty()) {
                        TextButton(
                            onClick = { showEmptyDialog = true },
                            enabled = runningOperation == null,
                        ) {
                            Text("Empty")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isSelectionMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            runningOperation?.let { operation ->
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = operation.label },
                )
                Text(
                    operation.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            when {
                state.isLoading && state.entries.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Could not load Trash", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                state.error ?: "Unknown error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = viewModel::refreshTrash) { Text("Retry") }
                        }
                    }
                }

                state.entries.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Trash is empty", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Deleted local items appear here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.entries, key = TrashEntry::id) { entry ->
                            TrashEntryRow(
                                entry = entry,
                                selected = entry.id in state.selectedIds,
                                enabled = runningOperation == null,
                                onToggle = { viewModel.toggleTrashSelection(entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPermanentDeleteDialog) {
        val selected = state.entries.filter { it.id in state.selectedIds }
        DeleteConfirmDialog(
            model = DeleteDialogModel.permanent(
                count = selected.size,
                fileName = selected.singleOrNull()?.displayName.orEmpty(),
            ),
            onDismiss = { showPermanentDeleteDialog = false },
            onConfirm = {
                showPermanentDeleteDialog = false
                viewModel.deleteSelectedTrashPermanently()
            },
        )
    }

    if (showEmptyDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyDialog = false },
            title = { Text("Empty Trash?") },
            text = { Text("Permanently delete every item in Trash? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyDialog = false
                        viewModel.emptyTrash()
                    },
                ) { Text("Empty Trash") }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun TrashEntryRow(
    entry: TrashEntry,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = if (selected) "Deselect ${entry.displayName}" else "Select ${entry.displayName}"
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.originalPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Deleted ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.deletedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
