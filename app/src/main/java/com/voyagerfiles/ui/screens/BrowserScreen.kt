package com.voyagerfiles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.data.model.isNetwork
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.ui.components.CreateItemDialog
import com.voyagerfiles.ui.components.DeleteConfirmDialog
import com.voyagerfiles.ui.components.DeleteDialogModel
import com.voyagerfiles.ui.components.FileGridItem
import com.voyagerfiles.ui.components.FileListItem
import com.voyagerfiles.ui.components.PathBreadcrumb
import com.voyagerfiles.ui.components.RenameDialog
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.viewmodel.BrowserSession
import com.voyagerfiles.viewmodel.ClipboardOperation
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.OperationState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.browseState.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val clipboardPaths by viewModel.clipboardPaths.collectAsState()
    val clipboardOp by viewModel.clipboardOperation.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val useTrash by viewModel.useTrash.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSelectionMoreMenu by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var showSessionsSheet by remember { mutableStateOf(false) }

    val isSelectionMode = state.selectedFiles.isNotEmpty()
    val isNetwork = state.source.isNetwork
    val toolbarModel = remember(isNetwork) { BrowserToolbarModel.forState(isNetwork) }
    val selectionToolbarModel = remember(isNetwork, state.selectedFiles.size) {
        SelectionToolbarModel.forState(isNetwork, state.selectedFiles.size)
    }
    val runningOperation = operationState as? OperationState.Running

    fun leaveBrowser() {
        onNavigateBack()
    }

    fun closeSession(sessionId: String) {
        val closesOnlyActiveSession = sessionId == activeSession?.id && sessions.size <= 1
        viewModel.closeSession(sessionId)
        if (closesOnlyActiveSession) {
            showSessionsSheet = false
            onNavigateBack()
        }
    }

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
            !viewModel.navigateUp() -> leaveBrowser()
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
                        if (SelectionToolbarAction.COPY in selectionToolbarModel.primaryActions) {
                            IconButton(
                                onClick = { viewModel.copyToClipboard(state.selectedFiles.toList()) },
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.ContentCopy, "Copy")
                            }
                        }
                        if (SelectionToolbarAction.CUT in selectionToolbarModel.primaryActions) {
                            IconButton(
                                onClick = { viewModel.cutToClipboard(state.selectedFiles.toList()) },
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.ContentCut, "Cut")
                            }
                        }
                        if (SelectionToolbarAction.DELETE in selectionToolbarModel.primaryActions) {
                            IconButton(
                                onClick = { showDeleteDialog = true },
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.Delete, "Delete")
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { showSelectionMoreMenu = true },
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.MoreVert, "More selection actions")
                            }
                            DropdownMenu(
                                expanded = showSelectionMoreMenu,
                                onDismissRequest = { showSelectionMoreMenu = false },
                            ) {
                                if (SelectionToolbarAction.SELECT_ALL in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text("Select all visible") },
                                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                        onClick = {
                                            viewModel.selectAll()
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (SelectionToolbarAction.DOWNLOAD in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text("Download") },
                                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                                        onClick = {
                                            viewModel.downloadSelected()
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (SelectionToolbarAction.RENAME in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                                        onClick = {
                                            showRenameDialog = state.selectedFiles.first()
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                if (!viewModel.navigateUp()) leaveBrowser()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { showSessionsSheet = true }) {
                                Icon(Icons.Filled.Folder, "Sessions")
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
                                    else Icons.AutoMirrored.Filled.ViewList,
                                    "Toggle view",
                                )
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, "Sort")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                ) {
                                    SortBy.entries.forEach { sort ->
                                        DropdownMenuItem(
                                            text = { Text(sort.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                            trailingIcon = {
                                                if (state.sortBy == sort) {
                                                    Icon(
                                                        if (state.sortOrder == SortOrder.ASCENDING) Icons.Filled.KeyboardArrowUp
                                                        else Icons.Filled.KeyboardArrowDown,
                                                        if (state.sortOrder == SortOrder.ASCENDING) "Ascending" else "Descending",
                                                    )
                                                }
                                            },
                                            onClick = {
                                                viewModel.setSortBy(sort)
                                                showSortMenu = false
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (state.sortOrder == SortOrder.ASCENDING) "Sort descending"
                                                else "Sort ascending",
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (state.sortOrder == SortOrder.ASCENDING) Icons.Filled.KeyboardArrowDown
                                                else Icons.Filled.KeyboardArrowUp,
                                                null,
                                            )
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(
                                                if (state.sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING
                                                else SortOrder.ASCENDING,
                                            )
                                            showSortMenu = false
                                        },
                                    )
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
                                    if (BrowserToolbarAction.DISCONNECT in toolbarModel.overflowActions) {
                                        DropdownMenuItem(
                                            text = { Text("Disconnect") },
                                            leadingIcon = { Icon(Icons.Filled.Close, null) },
                                            onClick = {
                                                showMoreMenu = false
                                                val closesOnlyActiveSession = sessions.size <= 1
                                                viewModel.disconnectRemote()
                                                if (closesOnlyActiveSession) onNavigateBack()
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (state.showHidden) "Hide hidden files" else "Show hidden files") },
                                        onClick = {
                                            viewModel.setShowHidden(!state.showHidden)
                                            showMoreMenu = false
                                        },
                                    )
                                    if (state.source == FileSource.LOCAL) {
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
                            }
                        }
                        PathBreadcrumb(
                            path = state.currentPath,
                            onNavigate = { viewModel.navigateTo(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                        BrowserFilterControls(
                            query = state.searchQuery,
                            selectedFilter = state.fileTypeFilter,
                            onQueryChange = viewModel::setSearchQuery,
                            onFilterChange = viewModel::setFileTypeFilter,
                        )
                    }
                }
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
                    IconButton(
                        onClick = { viewModel.paste() },
                        enabled = runningOperation == null,
                    ) {
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
            if (!isSelectionMode && runningOperation == null) {
                Box {
                    if (!isNetwork) {
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
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
                                onClick = {
                                    showCreateMenu = false
                                    showCreateFileDialog = true
                                },
                            )
                        }
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

                state.visibleFiles.isEmpty() -> {
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
                                if (state.files.isEmpty()) "Empty folder" else "No matching files",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.files.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = viewModel::clearFilters) {
                                    Text("Clear search and filters")
                                }
                            }
                        }
                    }
                }

                state.viewMode == ViewMode.LIST -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.visibleFiles, key = { it.path }) { file ->
                            FileListItem(
                                file = file,
                                isSelected = file.path in state.selectedFiles,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        viewModel.toggleSelection(file.path)
                                    } else if (file.isDirectory) {
                                        viewModel.navigateTo(file.path)
                                    } else if (isNetwork) {
                                        viewModel.downloadFile(file.path)
                                    } else if (state.source == FileSource.LOCAL || state.source == FileSource.SAF) {
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
                        items(state.visibleFiles, key = { it.path }) { file ->
                            FileGridItem(
                                file = file,
                                isSelected = file.path in state.selectedFiles,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        viewModel.toggleSelection(file.path)
                                    } else if (file.isDirectory) {
                                        viewModel.navigateTo(file.path)
                                    } else if (isNetwork) {
                                        viewModel.downloadFile(file.path)
                                    } else if (state.source == FileSource.LOCAL || state.source == FileSource.SAF) {
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

    if (showSessionsSheet) {
        SessionSwitcherSheet(
            sessions = sessions,
            activeSessionId = activeSession?.id,
            onSelect = { sessionId ->
                viewModel.activateSession(sessionId)
                showSessionsSheet = false
            },
            onClose = { sessionId -> closeSession(sessionId) },
            onDismiss = { showSessionsSheet = false },
        )
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
        val count = state.selectedFiles.size
        val fileName = state.selectedFiles.firstOrNull()?.substringAfterLast("/") ?: ""
        DeleteConfirmDialog(
            model = if (state.source == FileSource.LOCAL && useTrash) {
                DeleteDialogModel.localTrash(count, fileName)
            } else {
                DeleteDialogModel.permanent(count, fileName)
            },
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

@Composable
private fun BrowserFilterControls(
    query: String,
    selectedFilter: FileTypeFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (FileTypeFilter) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrowserSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    modifier = Modifier.weight(0.42f),
                )
                FileTypeFilterRow(
                    selectedFilter = selectedFilter,
                    onFilterChange = onFilterChange,
                    contentPadding = PaddingValues(start = 8.dp),
                    modifier = Modifier.weight(0.58f),
                )
            }
        } else {
            Column {
                BrowserSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                FileTypeFilterRow(
                    selectedFilter = selectedFilter,
                    onFilterChange = onFilterChange,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BrowserSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, "Clear search")
                }
            }
        },
        placeholder = { Text("Search this folder") },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun FileTypeFilterRow(
    selectedFilter: FileTypeFilter,
    onFilterChange: (FileTypeFilter) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(FileTypeFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterChange(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

data class BrowserToolbarModel(
    val primaryActions: List<BrowserToolbarAction>,
    val overflowActions: List<BrowserToolbarAction>,
) {
    companion object {
        fun forState(isRemote: Boolean): BrowserToolbarModel =
            BrowserToolbarModel(
                primaryActions = listOf(
                    BrowserToolbarAction.SESSIONS,
                    BrowserToolbarAction.REFRESH,
                    BrowserToolbarAction.TOGGLE_VIEW,
                    BrowserToolbarAction.SORT,
                ),
                overflowActions = if (isRemote) {
                    listOf(BrowserToolbarAction.DISCONNECT)
                } else {
                    emptyList()
                },
            )
    }
}

enum class BrowserToolbarAction {
    SESSIONS,
    REFRESH,
    TOGGLE_VIEW,
    SORT,
    DISCONNECT,
}

data class SelectionToolbarModel(
    val primaryActions: List<SelectionToolbarAction>,
    val overflowActions: List<SelectionToolbarAction>,
) {
    companion object {
        fun forState(isRemote: Boolean, selectionCount: Int): SelectionToolbarModel =
            SelectionToolbarModel(
                primaryActions = listOf(
                    SelectionToolbarAction.COPY,
                    SelectionToolbarAction.CUT,
                    SelectionToolbarAction.DELETE,
                ),
                overflowActions = buildList {
                    add(SelectionToolbarAction.SELECT_ALL)
                    if (isRemote) add(SelectionToolbarAction.DOWNLOAD)
                    if (selectionCount == 1) add(SelectionToolbarAction.RENAME)
                },
            )
    }
}

enum class SelectionToolbarAction {
    COPY,
    CUT,
    DELETE,
    SELECT_ALL,
    DOWNLOAD,
    RENAME,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSwitcherSheet(
    sessions: List<BrowserSession>,
    activeSessionId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Sessions",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    "No active sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        isActive = session.id == activeSessionId,
                        onSelect = { onSelect(session.id) },
                        onClose = { onClose(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: BrowserSession,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (session.source == FileSource.LOCAL || session.source == FileSource.SAF) {
                Icons.Filled.Folder
            } else {
                Icons.Filled.Cloud
            },
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isActive) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                session.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, "Close session")
        }
    }
}
