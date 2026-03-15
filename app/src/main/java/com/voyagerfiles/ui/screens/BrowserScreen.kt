package com.voyagerfiles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.ui.components.CreateItemDialog
import com.voyagerfiles.ui.components.DeleteConfirmDialog
import com.voyagerfiles.ui.components.FileGridItem
import com.voyagerfiles.ui.components.FileListItem
import com.voyagerfiles.ui.components.PathBreadcrumb
import com.voyagerfiles.ui.components.RenameDialog
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.viewmodel.ClipboardOperation
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.browseState.collectAsState()
    val clipboardPaths by viewModel.clipboardPaths.collectAsState()
    val clipboardOp by viewModel.clipboardOperation.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }

    val isSelectionMode = state.selectedFiles.isNotEmpty()

    // Show snackbar messages from ViewModel
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // Handle system back button
    BackHandler {
        when {
            isSelectionMode -> viewModel.clearSelection()
            !viewModel.navigateUp() -> onNavigateBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${state.selectedFiles.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, "Select all")
                        }
                        IconButton(onClick = { viewModel.copyToClipboard(state.selectedFiles.toList()) }) {
                            Icon(Icons.Filled.ContentCopy, "Copy")
                        }
                        IconButton(onClick = { viewModel.cutToClipboard(state.selectedFiles.toList()) }) {
                            Icon(Icons.Filled.ContentCut, "Cut")
                        }
                        if (state.selectedFiles.size == 1) {
                            IconButton(onClick = {
                                showRenameDialog = state.selectedFiles.first()
                            }) {
                                Icon(Icons.Filled.DriveFileRenameOutline, "Rename")
                            }
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, "Delete")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = {
                        PathBreadcrumb(
                            path = state.currentPath,
                            onNavigate = { viewModel.navigateTo(it) },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!viewModel.navigateUp()) onNavigateBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        // Show disconnect button for remote connections
                        if (state.source != FileSource.LOCAL) {
                            TextButton(onClick = {
                                viewModel.disconnectRemote()
                                onNavigateBack()
                            }) {
                                Text("Disconnect", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, "Refresh")
                        }
                        IconButton(onClick = {
                            viewModel.setViewMode(
                                if (state.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                            )
                        }) {
                            Icon(
                                if (state.viewMode == ViewMode.LIST) Icons.Filled.GridView
                                else Icons.Filled.ViewList,
                                "Toggle view",
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Filled.Sort, "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortBy.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = {
                                            Row {
                                                Text(
                                                    sort.name.lowercase().replaceFirstChar { it.uppercase() },
                                                    color = if (state.sortBy == sort)
                                                        MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        },
                                        onClick = {
                                            if (state.sortBy == sort) {
                                                viewModel.setSortOrder(
                                                    if (state.sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING
                                                    else SortOrder.ASCENDING
                                                )
                                            } else {
                                                viewModel.setSortBy(sort)
                                            }
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Filled.MoreVert, "More")
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (state.showHidden) "Hide hidden files" else "Show hidden files") },
                                    onClick = {
                                        viewModel.setShowHidden(!state.showHidden)
                                        showMoreMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Bookmark this folder") },
                                    leadingIcon = { Icon(Icons.Filled.BookmarkAdd, null) },
                                    onClick = {
                                        viewModel.toggleBookmark(
                                            state.currentPath,
                                            state.currentPath.substringAfterLast("/").ifEmpty { "Root" },
                                        )
                                        showMoreMenu = false
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Bookmark toggled")
                                        }
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = clipboardPaths.isNotEmpty() && clipboardOp != ClipboardOperation.NONE,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "${clipboardPaths.size} item${if (clipboardPaths.size > 1) "s" else ""} (${clipboardOp.name.lowercase()})",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = {
                        viewModel.clearClipboard()
                    }) {
                        Text("Cancel")
                    }
                    IconButton(onClick = { viewModel.paste() }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            "Paste here",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                Box {
                    FloatingActionButton(
                        onClick = { showCreateMenu = true },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, "Create")
                    }
                    DropdownMenu(
                        expanded = showCreateMenu,
                        onDismissRequest = { showCreateMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("New Folder") },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                            onClick = {
                                showCreateMenu = false
                                showCreateFolderDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("New File") },
                            leadingIcon = { Icon(Icons.Filled.NoteAdd, null) },
                            onClick = {
                                showCreateMenu = false
                                showCreateFileDialog = true
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Could not load files",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                state.error ?: "Unknown error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = { viewModel.refresh() }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                state.files.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Empty folder",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                state.viewMode == ViewMode.LIST -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.files, key = { it.path }) { file ->
                            FileListItem(
                                file = file,
                                isSelected = file.path in state.selectedFiles,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        viewModel.toggleSelection(file.path)
                                    } else if (file.isDirectory) {
                                        viewModel.navigateTo(file.path)
                                    } else if (state.source == FileSource.LOCAL) {
                                        try {
                                            FileUtils.openFile(context, file)
                                        } catch (e: Exception) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "No app found to open this file"
                                                )
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleSelection(file.path)
                                },
                            )
                        }
                    }
                }

                state.viewMode == ViewMode.GRID -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(100.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.files, key = { it.path }) { file ->
                            FileGridItem(
                                file = file,
                                isSelected = file.path in state.selectedFiles,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        viewModel.toggleSelection(file.path)
                                    } else if (file.isDirectory) {
                                        viewModel.navigateTo(file.path)
                                    } else if (state.source == FileSource.LOCAL) {
                                        try {
                                            FileUtils.openFile(context, file)
                                        } catch (e: Exception) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "No app found to open this file"
                                                )
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleSelection(file.path)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showCreateFolderDialog) {
        CreateItemDialog(
            isDirectory = true,
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name ->
                viewModel.createDirectory(name)
                showCreateFolderDialog = false
            },
        )
    }

    if (showCreateFileDialog) {
        CreateItemDialog(
            isDirectory = false,
            onDismiss = { showCreateFileDialog = false },
            onCreate = { name ->
                viewModel.createFile(name)
                showCreateFileDialog = false
            },
        )
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            fileName = state.selectedFiles.firstOrNull()?.substringAfterLast("/") ?: "",
            count = state.selectedFiles.size,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteSelected()
                showDeleteDialog = false
            },
        )
    }

    showRenameDialog?.let { path ->
        RenameDialog(
            currentName = path.substringAfterLast("/"),
            onDismiss = { showRenameDialog = null },
            onRename = { newName ->
                viewModel.rename(path, newName)
                showRenameDialog = null
                viewModel.clearSelection()
            },
        )
    }
}
