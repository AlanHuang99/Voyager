package com.voyagerfiles.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voyagerfiles.audio.AudioToneInstaller
import com.voyagerfiles.ui.components.AudioToneMenuItems
import com.voyagerfiles.ui.components.rememberAudioToneAction
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.data.model.isNetwork
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.ui.components.ArchiveNameDialog
import com.voyagerfiles.ui.components.CreateItemDialog
import com.voyagerfiles.ui.components.DeleteChoiceDialog
import com.voyagerfiles.ui.components.DeleteChoiceDialogModel
import com.voyagerfiles.ui.components.DeleteConfirmDialog
import com.voyagerfiles.ui.components.DeleteDialogModel
import com.voyagerfiles.ui.components.FileDetailsSheet
import com.voyagerfiles.ui.components.dragFileSelection
import com.voyagerfiles.ui.components.FileGridItem
import com.voyagerfiles.ui.components.FileListItem
import com.voyagerfiles.ui.components.PathBreadcrumb
import com.voyagerfiles.ui.components.RenameDialog
import com.voyagerfiles.playback.WebDavPlaybackProvider
import com.voyagerfiles.ui.text.asString
import com.voyagerfiles.ui.text.resolve
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.util.ShareIntentPlan
import com.voyagerfiles.viewmodel.BrowserSession
import com.voyagerfiles.viewmodel.ClipboardOperation
import com.voyagerfiles.viewmodel.DeleteMode
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.OperationState
import com.voyagerfiles.util.FolderShortcuts
import kotlinx.coroutines.launch

internal const val BROWSER_SEARCH_TEST_TAG = "browser-search"

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit,
    isTelevision: Boolean =
        LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION,
    launchPlaybackIntent: ((Intent) -> Unit)? = null,
    onFindDuplicates: (String) -> Unit = {},
) {
    val state by viewModel.browseState.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val isCurrentFolderBookmarked = bookmarks.any {
        it.path == state.currentPath && it.source == state.source
    }
    val sessions by viewModel.sessions.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    var pullRefreshing by remember(state.currentPath, state.source, activeSession?.id) { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) pullRefreshing = false
    }
    val clipboardPaths by viewModel.clipboardPaths.collectAsState()
    val clipboardOp by viewModel.clipboardOperation.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val resolvedSnackbarMessage = snackbarMessage?.let { it.asString() }
    val useTrash by viewModel.useTrash.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val transferConflict by viewModel.transferConflict.collectAsState()
    val operationResult by viewModel.lastOperationResult.collectAsState()
    val context = LocalContext.current
    val shareFailedMessage = stringResource(R.string.browser_share_failed)
    val noFileHandlerMessage = stringResource(R.string.browser_no_file_handler)
    val fileOpenFailedMessage = stringResource(R.string.browser_file_open_failed)
    val archiveUnsupportedMessage = stringResource(R.string.browser_archive_unsupported)
    val bookmarkToggledMessage = stringResource(R.string.browser_bookmark_toggled)
    val rootLabel = stringResource(R.string.browser_root)
    val shortcutRequestedMessage = stringResource(R.string.shortcut_requested)
    val shortcutFailedMessage = stringResource(R.string.shortcut_unavailable)
    val archiveDefaultName = stringResource(R.string.browser_archive_default_name)
    val focusManager = LocalFocusManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val inputModeManager = LocalInputModeManager.current
    val firstItemFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val playbackIntentLauncher: (Intent) -> Unit = launchPlaybackIntent ?: context::startActivity
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.uploadDocuments(uris)
    }

    val tonePermissionMessage = stringResource(R.string.audio_tone_permission)
    val setAudioTone = rememberAudioToneAction(viewModel::setAudioTone) {
        scope.launch { snackbarHostState.showSnackbar(tonePermissionMessage) }
    }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showDetailsFor by remember { mutableStateOf<FileItem?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSelectionMoreMenu by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var showSessionsSheet by remember { mutableStateOf(false) }
    var archiveNameDialogDefault by remember { mutableStateOf<String?>(null) }
    var archiveToExtract by remember { mutableStateOf<FileItem?>(null) }
    var playbackFallbackFor by remember { mutableStateOf<FileItem?>(null) }

    val isSelectionMode = state.selectedFiles.isNotEmpty()
    val isNetwork = state.source.isNetwork
    val selectedItems = remember(state.files, state.selectedFiles) {
        state.files.filter { it.path in state.selectedFiles }
    }
    val archiveActions = remember(selectedItems) {
        BrowserArchiveActions.forSelection(selectedItems)
    }
    val sharePlan = remember(selectedItems) { ShareIntentPlan.forFiles(selectedItems) }
    val canOpenWith = remember(selectedItems) {
        selectedItems.singleOrNull()?.let { item ->
            !item.isDirectory &&
                (item.source == FileSource.LOCAL || item.source == FileSource.SAF || item.source == FileSource.WEBDAV)
        } == true
    }
    val toolbarModel = remember(isNetwork) { BrowserToolbarModel.forState(isNetwork) }
    val createMenuModel = remember(isNetwork) { BrowserCreateMenuModel.forState(isNetwork) }

    fun toggleSelection(path: String) {
        if (shouldPerformSelectionHaptic(state.selectedFiles, path)) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        viewModel.toggleSelection(path)
    }
    val selectionToolbarModel = remember(isNetwork, selectedItems.size, sharePlan, canOpenWith) {
        SelectionToolbarModel.forState(
            isRemote = isNetwork,
            selectionCount = selectedItems.size,
            canShare = sharePlan != null,
            canOpenWith = canOpenWith,
        )
    }
    val runningOperation = operationState as? OperationState.Running
    val firstVisiblePath = state.visibleFiles.firstOrNull()?.path

    LaunchedEffect(isTelevision, state.isLoading, firstVisiblePath, state.viewMode) {
        if (isTelevision && !state.isLoading && firstVisiblePath != null) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            firstItemFocusRequester.requestFocus()
        }
    }

    fun leaveBrowser() {
        onNavigateBack()
    }

    fun navigateTo(path: String) {
        focusManager.clearFocus(force = true)
        viewModel.navigateTo(path)
    }

    fun navigateUpOrLeave() {
        focusManager.clearFocus(force = true)
        if (!viewModel.navigateUp()) leaveBrowser()
    }

    fun closeSession(sessionId: String) {
        val closesOnlyActiveSession = sessionId == activeSession?.id && sessions.size <= 1
        viewModel.closeSession(sessionId)
        if (closesOnlyActiveSession) {
            showSessionsSheet = false
            onNavigateBack()
        }
    }

    fun shareSelected() {
        FileUtils.shareFiles(context, selectedItems).fold(
            onSuccess = { viewModel.clearSelection() },
            onFailure = {
                scope.launch {
                    snackbarHostState.showSnackbar(shareFailedMessage)
                }
            },
        )
    }

    fun openWebDavFile(file: FileItem, chooser: Boolean = false) {
        scope.launch {
            viewModel.prepareWebDavPlayback(file).fold(
                onSuccess = { uri ->
                    try {
                        val target = FileUtils.createRemotePlaybackIntent(uri, file)
                        playbackIntentLauncher(
                            if (chooser) Intent.createChooser(target, context.getString(R.string.action_open_with))
                            else target,
                        )
                        if (chooser) viewModel.clearSelection()
                    } catch (_: ActivityNotFoundException) {
                        WebDavPlaybackProvider.revoke(uri)
                        snackbarHostState.showSnackbar(noFileHandlerMessage)
                    } catch (_: Throwable) {
                        WebDavPlaybackProvider.revoke(uri)
                        snackbarHostState.showSnackbar(fileOpenFailedMessage)
                    }
                },
                onFailure = { playbackFallbackFor = file },
            )
        }
    }

    fun openSelectedWith() {
        val file = selectedItems.singleOrNull() ?: return
        if (file.source == FileSource.WEBDAV) {
            openWebDavFile(file, chooser = true)
            return
        }
        FileUtils.openFileWith(context, file).fold(
            onSuccess = { viewModel.clearSelection() },
            onFailure = { error ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (error is ActivityNotFoundException) {
                            noFileHandlerMessage
                        } else {
                            fileOpenFailedMessage
                        }
                    )
                }
            },
        )
    }

    fun handleDeviceFileTap(file: FileItem) {
        when (BrowserArchiveActions.tapAction(file)) {
            ArchiveTapAction.CONFIRM_EXTRACTION -> archiveToExtract = file
            ArchiveTapAction.SHOW_UNSUPPORTED -> {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        BrowserArchiveActions.unsupportedExtractionReason(listOf(file))
                            ?.resolve(context.resources)
                            ?: archiveUnsupportedMessage
                    )
                }
            }
            ArchiveTapAction.OPEN_EXTERNALLY -> {
                FileUtils.openFile(context, file).onFailure { error ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (error is ActivityNotFoundException) {
                                noFileHandlerMessage
                            } else {
                                fileOpenFailedMessage
                            }
                        )
                    }
                }
            }
        }
    }

    fun handleRemoteFileTap(file: FileItem) {
        when (remoteFileTapAction(file)) {
            RemoteFileTapAction.NAVIGATE -> navigateTo(file.path)
            RemoteFileTapAction.DOWNLOAD -> viewModel.downloadFile(file.path)
            RemoteFileTapAction.STREAM_WEBDAV -> openWebDavFile(file)
        }
    }

    // Show snackbar messages from ViewModel
    LaunchedEffect(snackbarMessage) {
        resolvedSnackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // Handle system back button
    BackHandler {
        when {
            isSelectionMode -> viewModel.clearSelection()
            else -> navigateUpOrLeave()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            pluralStringResource(
                                R.plurals.items_selected,
                                state.selectedFiles.size,
                                state.selectedFiles.size,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.content_desc_clear_selection))
                        }
                    },
                    actions = {
                        if (SelectionToolbarAction.COPY in selectionToolbarModel.primaryActions) {
                            IconButton(
                                onClick = { viewModel.copyToClipboard(state.selectedFiles.toList()) },
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.ContentCopy, stringResource(R.string.content_desc_copy))
                            }
                        }
                        if (SelectionToolbarAction.CUT in selectionToolbarModel.primaryActions) {
                            IconButton(
                                onClick = { viewModel.cutToClipboard(state.selectedFiles.toList()) },
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.ContentCut, stringResource(R.string.content_desc_cut))
                            }
                        }
                        if (SelectionToolbarAction.RENAME in selectionToolbarModel.primaryActions) {
                            IconButton(
                                onClick = { showRenameDialog = state.selectedFiles.single() },
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.content_desc_rename))
                            }
                        }
                        if (SelectionToolbarAction.SHARE in selectionToolbarModel.primaryActions) {
                            IconButton(
                                onClick = ::shareSelected,
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.Share, stringResource(R.string.content_desc_share))
                            }
                        }
                        if (SelectionToolbarAction.DELETE in selectionToolbarModel.primaryActions) {
                            IconButton(
                                onClick = { showDeleteDialog = true },
                                enabled = runningOperation == null,
                            ) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.content_desc_delete))
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { showSelectionMoreMenu = true },
                                enabled = runningOperation == null,
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    stringResource(R.string.content_desc_more_selection_actions),
                                )
                            }
                            DropdownMenu(
                                expanded = showSelectionMoreMenu,
                                onDismissRequest = { showSelectionMoreMenu = false },
                            ) {
                                selectedItems.singleOrNull()?.takeIf(AudioToneInstaller::isSupported)?.let { file ->
                                    AudioToneMenuItems(file) { selected, tone ->
                                        showSelectionMoreMenu = false
                                        setAudioTone(selected, tone)
                                    }
                                }
                                if (BrowserArchiveAction.COMPRESS_TO_ZIP in archiveActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.dialog_compress_zip)) },
                                        leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                        onClick = {
                                            archiveNameDialogDefault =
                                                BrowserArchiveActions.defaultZipName(
                                                    selectedItems = selectedItems,
                                                    existingNames = state.files
                                                        .mapTo(mutableSetOf()) { it.name },
                                                    fallbackBaseName = archiveDefaultName,
                                                )
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (BrowserArchiveAction.EXTRACT_HERE in archiveActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_extract_here)) },
                                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                        onClick = {
                                            viewModel.extractSelectedArchive()
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (BrowserArchiveAction.EXTRACTION_UNSUPPORTED in archiveActions) {
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(stringResource(R.string.action_extract_here))
                                                BrowserArchiveActions
                                                    .unsupportedExtractionReason(selectedItems)
                                                    ?.asString()
                                                    ?.let { reason ->
                                                        Text(
                                                            text = reason,
                                                            style = MaterialTheme.typography.bodySmall,
                                                        )
                                                    }
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                        enabled = false,
                                        onClick = {},
                                    )
                                }
                                if (SelectionToolbarAction.COPY in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_copy)) },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                        onClick = {
                                            viewModel.copyToClipboard(state.selectedFiles.toList())
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (SelectionToolbarAction.CUT in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_cut)) },
                                        leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
                                        onClick = {
                                            viewModel.cutToClipboard(state.selectedFiles.toList())
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (SelectionToolbarAction.SELECT_ALL in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_select_all_visible)) },
                                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                        onClick = {
                                            viewModel.selectAll()
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (SelectionToolbarAction.DOWNLOAD in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_download)) },
                                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                                        onClick = {
                                            viewModel.downloadSelected()
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (SelectionToolbarAction.RENAME in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_rename)) },
                                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                                        onClick = {
                                            showRenameDialog = state.selectedFiles.first()
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (SelectionToolbarAction.DETAILS in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.details_title)) },
                                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                                        onClick = {
                                            showDetailsFor = selectedItems.singleOrNull()
                                            showSelectionMoreMenu = false
                                        },
                                    )
                                }
                                if (SelectionToolbarAction.OPEN_WITH in selectionToolbarModel.overflowActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_open_with)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                        onClick = {
                                            showSelectionMoreMenu = false
                                            openSelectedWith()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
                            IconButton(onClick = ::navigateUpOrLeave) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { showSessionsSheet = true }) {
                                Icon(Icons.Filled.Folder, stringResource(R.string.content_desc_sessions))
                            }
                            IconButton(onClick = { viewModel.refresh() }) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.content_desc_refresh))
                            }
                            Box {
                                IconButton(onClick = { showViewMenu = true }) {
                                    Icon(
                                        imageVector = when (state.viewMode) {
                                            ViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                                            ViewMode.COMPACT -> Icons.Filled.ViewAgenda
                                            ViewMode.GRID -> Icons.Filled.GridView
                                        },
                                        contentDescription = stringResource(
                                            R.string.content_desc_view_options_current,
                                            stringResource(state.viewMode.labelRes),
                                        ),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showViewMenu,
                                    onDismissRequest = { showViewMenu = false },
                                ) {
                                    ViewMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(mode.labelRes)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = when (mode) {
                                                        ViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                                                        ViewMode.COMPACT -> Icons.Filled.ViewAgenda
                                                        ViewMode.GRID -> Icons.Filled.GridView
                                                    },
                                                    contentDescription = null,
                                                )
                                            },
                                            trailingIcon = {
                                                if (state.viewMode == mode) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        contentDescription = stringResource(R.string.content_desc_selected),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                viewModel.setViewMode(mode)
                                                showViewMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.content_desc_sort))
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                ) {
                                    SortBy.entries.forEach { sort ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(sort.labelRes)) },
                                            trailingIcon = {
                                                if (state.sortBy == sort) {
                                                    Icon(
                                                        if (state.sortOrder == SortOrder.ASCENDING) Icons.Filled.KeyboardArrowUp
                                                        else Icons.Filled.KeyboardArrowDown,
                                                        stringResource(
                                                            if (state.sortOrder == SortOrder.ASCENDING) {
                                                                R.string.browser_sort_ascending
                                                            } else {
                                                                R.string.browser_sort_descending
                                                            },
                                                        ),
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
                                                stringResource(
                                                    if (state.sortOrder == SortOrder.ASCENDING) {
                                                        R.string.browser_sort_descending_action
                                                    } else {
                                                        R.string.browser_sort_ascending_action
                                                    },
                                                ),
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
                                    Icon(Icons.Filled.MoreVert, stringResource(R.string.content_desc_more))
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                ) {
                                    if (BrowserToolbarAction.DISCONNECT in toolbarModel.overflowActions) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_disconnect)) },
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
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (state.showHidden) R.string.browser_hide_hidden
                                                    else R.string.browser_show_hidden,
                                                ),
                                            )
                                        },
                                        onClick = {
                                            viewModel.setShowHidden(!state.showHidden)
                                            showMoreMenu = false
                                        },
                                    )
                                    if (state.source == FileSource.LOCAL) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.duplicates_title)) },
                                            enabled = runningOperation == null,
                                            onClick = { showMoreMenu = false; onFindDuplicates(state.currentPath) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.shortcut_pin_folder)) },
                                            leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                            onClick = {
                                                showMoreMenu = false
                                                val path = state.currentPath
                                                scope.launch {
                                                    val requested = runCatching {
                                                        FolderShortcuts.requestPin(context, path)
                                                    }.getOrDefault(false)
                                                    snackbarHostState.showSnackbar(
                                                        if (requested) shortcutRequestedMessage else shortcutFailedMessage,
                                                    )
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(stringResource(
                                                    if (isCurrentFolderBookmarked) R.string.action_remove_bookmark
                                                    else R.string.action_bookmark_folder,
                                                ))
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (isCurrentFolderBookmarked) Icons.Filled.BookmarkRemove
                                                    else Icons.Filled.BookmarkAdd,
                                                    contentDescription = null,
                                                    modifier = Modifier.testTag(
                                                        if (isCurrentFolderBookmarked) "bookmark-remove-icon"
                                                        else "bookmark-add-icon",
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                if (isCurrentFolderBookmarked) {
                                                    viewModel.removeBookmark(state.currentPath, state.source)
                                                } else {
                                                    viewModel.addBookmark(
                                                        state.currentPath,
                                                        state.currentPath.substringAfterLast("/").ifEmpty { rootLabel },
                                                    )
                                                }
                                                showMoreMenu = false
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(bookmarkToggledMessage)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        PathBreadcrumb(
                            path = state.currentPath,
                            onNavigate = ::navigateTo,
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
                        pluralStringResource(
                            R.plurals.browser_clipboard_summary,
                            clipboardPaths.size,
                            clipboardPaths.size,
                            stringResource(
                                if (clipboardOp == ClipboardOperation.CUT) R.string.browser_clipboard_cut
                                else R.string.browser_clipboard_copy,
                            ),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = {
                        viewModel.clearClipboard()
                    }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    IconButton(
                        onClick = { viewModel.paste() },
                        enabled = runningOperation == null,
                    ) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            stringResource(R.string.browser_paste_here),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode && runningOperation == null) {
                Box {
                    FloatingActionButton(
                        onClick = { showCreateMenu = true },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.content_desc_create))
                    }
                    BrowserCreateMenu(
                        expanded = showCreateMenu,
                        model = createMenuModel,
                        onDismiss = { showCreateMenu = false },
                        onAction = { action ->
                            when (action) {
                                BrowserCreateAction.NEW_FOLDER -> showCreateFolderDialog = true
                                BrowserCreateAction.NEW_FILE -> showCreateFileDialog = true
                                BrowserCreateAction.UPLOAD_FILES -> uploadLauncher.launch(arrayOf("*/*"))
                            }
                        },
                    )
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
                OperationProgressContent(operation, onCancel = viewModel::cancelOperation)
            }
            if (runningOperation == null) {
                operationResult?.let { OperationResultContent(it, onDismiss = viewModel::dismissOperationResult) }
            }
            PullToRefreshBox(
                isRefreshing = pullRefreshing && state.isLoading,
                onRefresh = {
                    if (!state.isLoading) {
                        pullRefreshing = true
                        viewModel.refresh()
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                when {
                    state.isLoading && !pullRefreshing -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    state.error != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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
                                    stringResource(R.string.browser_load_failed),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    state.error?.asString().orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                TextButton(onClick = { viewModel.refresh() }) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    }

                    state.visibleFiles.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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
                                    stringResource(
                                        if (state.files.isEmpty()) R.string.browser_empty_folder
                                        else R.string.browser_no_matching_files,
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (state.files.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(onClick = viewModel::clearFilters) {
                                        Text(stringResource(R.string.browser_clear_search_filters))
                                    }
                                }
                            }
                        }
                    }

                    state.viewMode == ViewMode.LIST || state.viewMode == ViewMode.COMPACT -> {
                        val listState = rememberLazyListState()
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("browser-files")
                                .dragFileSelection(state.visibleFiles.map { it.path }, state.selectedFiles,
                                    enabled = !isTelevision && runningOperation == null, listState = listState,
                                    onSelection = viewModel::setDragSelection,
                                    onStartHaptic = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) }),
                        ) {
                            items(state.visibleFiles, key = { it.path }) { file ->
                                FileListItem(
                                    file = file,
                                    modifier = if (isTelevision && file.path == firstVisiblePath) {
                                        Modifier.focusRequester(firstItemFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                    compact = state.viewMode == ViewMode.COMPACT,
                                    isSelected = file.path in state.selectedFiles,
                                    isSelectionMode = isSelectionMode,
                                    enableRemoteSelect = isTelevision,
                                    enableDragSelection = !isTelevision && runningOperation == null,
                                    onClick = {
                                        if (isSelectionMode) {
                                            toggleSelection(file.path)
                                        } else if (isNetwork) {
                                            handleRemoteFileTap(file)
                                        } else if (file.isDirectory) {
                                            navigateTo(file.path)
                                        } else if (state.source == FileSource.LOCAL || state.source == FileSource.SAF) {
                                            handleDeviceFileTap(file)
                                        }
                                    },
                                    onLongClick = {
                                        toggleSelection(file.path)
                                    },
                                )
                            }
                        }
                    }

                    state.viewMode == ViewMode.GRID -> {
                        val gridState = rememberLazyGridState()
                        val gridPadding = with(LocalDensity.current) { 8.dp.toPx() }
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(100.dp),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize().testTag("browser-files")
                                .dragFileSelection(state.visibleFiles.map { it.path }, state.selectedFiles,
                                    enabled = !isTelevision && runningOperation == null, gridState = gridState,
                                    gridContentOffset = Offset(gridPadding, gridPadding),
                                    onSelection = viewModel::setDragSelection,
                                    onStartHaptic = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) }),
                        ) {
                            items(state.visibleFiles, key = { it.path }) { file ->
                                FileGridItem(
                                    file = file,
                                    modifier = if (isTelevision && file.path == firstVisiblePath) {
                                        Modifier.focusRequester(firstItemFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                    isSelected = file.path in state.selectedFiles,
                                    isSelectionMode = isSelectionMode,
                                    enableRemoteSelect = isTelevision,
                                    enableDragSelection = !isTelevision && runningOperation == null,
                                    onClick = {
                                        if (isSelectionMode) {
                                            toggleSelection(file.path)
                                        } else if (isNetwork) {
                                            handleRemoteFileTap(file)
                                        } else if (file.isDirectory) {
                                            navigateTo(file.path)
                                        } else if (state.source == FileSource.LOCAL || state.source == FileSource.SAF) {
                                            handleDeviceFileTap(file)
                                        }
                                    },
                                    onLongClick = {
                                        toggleSelection(file.path)
                                    },
                                )
                            }
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

    showDetailsFor?.let { file ->
        FileDetailsSheet(
            file = file,
            onDismiss = { showDetailsFor = null },
        )
    }

    transferConflict?.let { request ->
        com.voyagerfiles.ui.components.TransferConflictDialog(request) { response ->
            viewModel.resolveTransferConflict(request, response)
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

    archiveNameDialogDefault?.let { initialName ->
        ArchiveNameDialog(
            initialName = initialName,
            onDismiss = { archiveNameDialogDefault = null },
            onCreate = { name ->
                viewModel.createZipFromSelection(name)
                archiveNameDialogDefault = null
            },
        )
    }

    archiveToExtract?.let { archive ->
        AlertDialog(
            onDismissRequest = { archiveToExtract = null },
            title = { Text(stringResource(R.string.browser_extract_archive_title)) },
            text = {
                Text(stringResource(R.string.browser_extract_archive_message, archive.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.extractArchive(archive.path)
                        archiveToExtract = null
                    },
                ) {
                    Text(stringResource(R.string.browser_extract))
                }
            },
            dismissButton = {
                TextButton(onClick = { archiveToExtract = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    playbackFallbackFor?.let { file ->
        AlertDialog(
            onDismissRequest = { playbackFallbackFor = null },
            title = { Text(stringResource(R.string.playback_direct_unavailable_title)) },
            text = { Text(stringResource(R.string.playback_direct_unavailable_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        playbackFallbackFor = null
                        viewModel.downloadFile(file.path)
                    },
                ) {
                    Text(stringResource(R.string.action_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { playbackFallbackFor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDeleteDialog) {
        val count = state.selectedFiles.size
        val fileName = state.selectedFiles.firstOrNull()?.substringAfterLast("/") ?: ""
        if (state.source == FileSource.LOCAL && useTrash) {
            DeleteChoiceDialog(
                model = DeleteChoiceDialogModel.local(count, fileName),
                onDismiss = { showDeleteDialog = false },
                onMoveToTrash = {
                    showDeleteDialog = false
                    viewModel.deleteSelected(DeleteMode.TRASH)
                },
                onDeletePermanently = {
                    showDeleteDialog = false
                    viewModel.deleteSelected(DeleteMode.PERMANENT)
                },
            )
        } else {
            DeleteConfirmDialog(
                model = DeleteDialogModel.permanent(count, fileName),
                onDismiss = { showDeleteDialog = false },
                onConfirm = {
                    showDeleteDialog = false
                    viewModel.deleteSelected(DeleteMode.PERMANENT)
                },
            )
        }
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
internal fun OperationProgressContent(
    operation: OperationState.Running,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val progress = operation.progress
    val progressLabel = progress.label.asString()
    val completedItemsText = progress.totalItems?.takeIf { it > 0 }?.let {
        stringResource(R.string.transfer_items_completed, progress.completedItems, it) +
                if (progress.skippedItems > 0) " • " + stringResource(R.string.transfer_items_skipped, progress.skippedItems) else ""
    }
    Column(
        modifier = modifier.semantics {
            stateDescription = progress.stateDescription(progressLabel, completedItemsText)
        },
    ) {
        if (operation.cancellable && onCancel != null) {
            TextButton(onClick = onCancel, enabled = !operation.cancelling) {
                Text(stringResource(if (operation.cancelling) R.string.transfer_cancelling else R.string.action_cancel))
            }
        }
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            progressLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        progress.detailText(completedItemsText)?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
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
                    Icon(Icons.Filled.Close, stringResource(R.string.content_desc_clear_search))
                }
            }
        },
        placeholder = { Text(stringResource(R.string.browser_search_placeholder)) },
        singleLine = true,
        modifier = modifier.testTag(BROWSER_SEARCH_TEST_TAG),
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
                label = { Text(stringResource(filter.labelRes)) },
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
                    BrowserToolbarAction.VIEW_OPTIONS,
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
    VIEW_OPTIONS,
    SORT,
    DISCONNECT,
}

data class SelectionToolbarModel(
    val primaryActions: List<SelectionToolbarAction>,
    val overflowActions: List<SelectionToolbarAction>,
) {
    companion object {
        fun forState(
            isRemote: Boolean,
            selectionCount: Int,
            canShare: Boolean,
            canOpenWith: Boolean = false,
        ): SelectionToolbarModel {
            val isSingle = selectionCount == 1
            val primaryActions = when {
                isSingle && canShare -> listOf(
                    SelectionToolbarAction.SHARE,
                    SelectionToolbarAction.RENAME,
                    SelectionToolbarAction.DELETE,
                )
                isSingle -> listOf(
                    SelectionToolbarAction.COPY,
                    SelectionToolbarAction.RENAME,
                    SelectionToolbarAction.DELETE,
                )
                canShare -> listOf(
                    SelectionToolbarAction.COPY,
                    SelectionToolbarAction.SHARE,
                    SelectionToolbarAction.DELETE,
                )
                else -> listOf(
                    SelectionToolbarAction.COPY,
                    SelectionToolbarAction.DELETE,
                )
            }
            return SelectionToolbarModel(
                primaryActions = primaryActions,
                overflowActions = buildList {
                    if (SelectionToolbarAction.COPY !in primaryActions) {
                        add(SelectionToolbarAction.COPY)
                    }
                    add(SelectionToolbarAction.CUT)
                    add(SelectionToolbarAction.SELECT_ALL)
                    if (isRemote) add(SelectionToolbarAction.DOWNLOAD)
                    if (isSingle) add(SelectionToolbarAction.DETAILS)
                    if (canOpenWith) add(SelectionToolbarAction.OPEN_WITH)
                },
            )
        }
    }
}

enum class SelectionToolbarAction {
    COPY,
    CUT,
    DELETE,
    SELECT_ALL,
    DOWNLOAD,
    RENAME,
    SHARE,
    DETAILS,
    OPEN_WITH,
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
                stringResource(R.string.browser_sessions),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.browser_no_active_sessions),
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
                        stringResource(R.string.browser_session_active),
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
            Icon(Icons.Filled.Close, stringResource(R.string.content_desc_close_session))
        }
    }
}
