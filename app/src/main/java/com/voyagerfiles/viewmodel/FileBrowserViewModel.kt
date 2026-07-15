package com.voyagerfiles.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.BrowseState
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.TrashEntry
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.data.model.isNetwork
import com.voyagerfiles.data.remote.saf.SafFileProvider
import com.voyagerfiles.data.repository.FileDownloader
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.FileProviderFactory
import com.voyagerfiles.data.repository.ConnectionRepository
import com.voyagerfiles.data.repository.LocalTrashManager
import com.voyagerfiles.security.AndroidCredentialCipher
import com.voyagerfiles.ui.theme.AppTheme
import com.voyagerfiles.util.FileNameValidationResult
import com.voyagerfiles.util.FileNameValidator
import com.voyagerfiles.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class FileBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val db = AppDatabase.getInstance(application)
    private val connectionDao = db.connectionDao()
    private val connectionRepository = ConnectionRepository(connectionDao, AndroidCredentialCipher())
    private val bookmarkDao = db.bookmarkDao()
    private val trashManager = LocalTrashManager(
        FileUtils.getStorageVolumes(application).mapNotNull { it.path?.let(::File) },
    )

    private var fileProvider: FileProvider = FileProviderFactory.createLocal()
    private var browserSessionRootPath: String? = null
    private val sessionProviders = mutableMapOf<String, FileProvider>()
    private val loadGuard = DirectoryLoadGuard()

    private val _browseState = MutableStateFlow(BrowseState())
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    private val _sessions = MutableStateFlow<List<BrowserSession>>(emptyList())
    val sessions: StateFlow<List<BrowserSession>> = _sessions.asStateFlow()

    private val _activeSession = MutableStateFlow<BrowserSession?>(null)
    val activeSession: StateFlow<BrowserSession?> = _activeSession.asStateFlow()

    private val _clipboardPaths = MutableStateFlow<List<String>>(emptyList())
    val clipboardPaths: StateFlow<List<String>> = _clipboardPaths.asStateFlow()

    private val _clipboardOperation = MutableStateFlow(ClipboardOperation.NONE)
    val clipboardOperation: StateFlow<ClipboardOperation> = _clipboardOperation.asStateFlow()
    private var clipboardProvider: FileProvider? = null

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()

    private val _trashState = MutableStateFlow(TrashState())
    val trashState: StateFlow<TrashState> = _trashState.asStateFlow()

    val theme = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.SYSTEM)
    val useTrash = prefs.useTrash.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val limitedAccessAccepted = prefs.limitedAccessAccepted.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val connections = connectionRepository.connections.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val bookmarks = bookmarkDao.getAllBookmarks().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            runCatching { connectionRepository.migratePlaintextCredentials() }
                .onFailure {
                    showSnackbar("Could not secure saved connection passwords. Edit and save them again.")
                }
        }
        viewModelScope.launch {
            prefs.showHidden.collect { show ->
                _browseState.update { it.copy(showHidden = show) }
                if (_browseState.value.currentPath != "/") refreshFiles()
            }
        }
        viewModelScope.launch {
            prefs.sortBy.collect { sort ->
                _browseState.update { it.copy(sortBy = sort) }
                if (_browseState.value.files.isNotEmpty()) resortFiles()
            }
        }
        viewModelScope.launch {
            prefs.sortOrder.collect { order ->
                _browseState.update { it.copy(sortOrder = order) }
                if (_browseState.value.files.isNotEmpty()) resortFiles()
            }
        }
        viewModelScope.launch {
            prefs.viewMode.collect { mode ->
                _browseState.update { it.copy(viewMode = mode) }
            }
        }
        // Load initial path
        viewModelScope.launch {
            val defaultPath = prefs.defaultPath.first()
            navigateToPath(defaultPath)
        }
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            val normalizedPath = BrowserNavigationBounds.normalizePath(path)
            val rootPath = browserSessionRootPath
            if (
                rootPath != null &&
                _browseState.value.source != FileSource.SAF &&
                !BrowserNavigationBounds.isPathAtOrInsideRoot(normalizedPath, rootPath)
            ) {
                showSnackbar("This location is outside the current session")
                return@launch
            }
            navigateToPath(normalizedPath)
        }
    }

    fun openLocalRoot(path: String) {
        viewModelScope.launch {
            val normalizedPath = BrowserNavigationBounds.normalizePath(path)
            val sessionId = localSessionId(normalizedPath)
            if (_sessions.value.none { it.id == sessionId }) {
                sessionProviders[sessionId] = FileProviderFactory.createLocal()
                _sessions.update { sessions ->
                    sessions + BrowserSession(
                        id = sessionId,
                        title = titleForLocalPath(normalizedPath),
                        source = FileSource.LOCAL,
                        rootPath = normalizedPath,
                        currentPath = normalizedPath,
                    )
                }
            } else {
                _sessions.update { sessions ->
                    sessions.map { session ->
                        if (session.id == sessionId) session.copy(currentPath = normalizedPath) else session
                    }
                }
            }
            activateSessionInternal(sessionId)
        }
    }

    fun openSafRoot(treeUri: Uri) {
        viewModelScope.launch {
            val rootPath = SafFileProvider.rootDocumentUri(treeUri).toString()
            val sessionId = safSessionId(rootPath)
            if (_sessions.value.none { it.id == sessionId }) {
                sessionProviders[sessionId] = FileProviderFactory.createSaf(getApplication(), treeUri)
                _sessions.update { sessions ->
                    sessions + BrowserSession(
                        id = sessionId,
                        title = SafFileProvider.titleForTreeUri(treeUri),
                        source = FileSource.SAF,
                        rootPath = rootPath,
                        currentPath = rootPath,
                    )
                }
            } else {
                _sessions.update { sessions ->
                    sessions.map { session ->
                        if (session.id == sessionId) session.copy(currentPath = rootPath) else session
                    }
                }
            }
            activateSessionInternal(sessionId)
        }
    }

    fun navigateUp(): Boolean {
        val currentPath = BrowserNavigationBounds.normalizePath(_browseState.value.currentPath)
        val parent = fileProvider.getParentPath(currentPath)
        if (BrowserNavigationBounds.canNavigateToParent(currentPath, parent, browserSessionRootPath)) {
            navigateTo(checkNotNull(parent))
            return true
        }
        return false
    }

    private suspend fun navigateToPath(path: String) {
        val normalizedPath = BrowserNavigationBounds.normalizePath(path)
        val sessionId = _activeSession.value?.id
        if (sessionId != null) {
            updateSession(sessionId) { it.copy(currentPath = normalizedPath) }
        }
        _browseState.update {
            it.copy(
                currentPath = normalizedPath,
                isLoading = true,
                error = null,
                selectedFiles = emptySet(),
                searchQuery = "",
                fileTypeFilter = FileTypeFilter.ALL,
            )
        }
        loadFiles(normalizedPath, sessionId, fileProvider)
    }

    fun refresh() {
        refreshFiles()
    }

    private fun refreshFiles() {
        viewModelScope.launch {
            loadFiles(_browseState.value.currentPath, _activeSession.value?.id, fileProvider)
        }
    }

    private suspend fun loadFiles(
        path: String,
        sessionId: String? = _activeSession.value?.id,
        provider: FileProvider = fileProvider,
    ) {
        val requestId = loadGuard.nextRequest(sessionId)
        _browseState.update { it.copy(isLoading = true, error = null) }
        provider.listFiles(path).fold(
            onSuccess = { files ->
                if (!loadGuard.isCurrent(requestId, sessionId) || !isCurrentLoad(sessionId)) return@fold
                val filtered = if (_browseState.value.showHidden) files
                else files.filter { !it.isHidden }
                val sorted = sortFiles(filtered)
                _browseState.update { state ->
                    val next = state.copy(files = sorted, isLoading = false)
                    next.copy(selectedFiles = next.reconciledSelection)
                }
            },
            onFailure = { error ->
                if (!loadGuard.isCurrent(requestId, sessionId) || !isCurrentLoad(sessionId)) return@fold
                _browseState.update {
                    it.copy(
                        error = OperationMessages.reason(error),
                        isLoading = false,
                        files = emptyList(),
                    )
                }
            },
        )
    }

    private fun resortFiles() {
        _browseState.update { state ->
            state.copy(files = sortFiles(state.files))
        }
    }

    private fun sortFiles(files: List<FileItem>): List<FileItem> {
        val state = _browseState.value
        val comparator = when (state.sortBy) {
            SortBy.NAME -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.TYPE -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (state.sortOrder == SortOrder.DESCENDING) comparator.reversed() else comparator
        return files.sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(ordered))
    }

    fun toggleSelection(path: String) {
        _browseState.update { state ->
            val newSelection = state.selectedFiles.toMutableSet()
            if (path in newSelection) newSelection.remove(path) else newSelection.add(path)
            state.copy(selectedFiles = newSelection)
        }
    }

    fun selectAll() {
        _browseState.update { state ->
            state.copy(selectedFiles = state.visibleFiles.map { it.path }.toSet())
        }
    }

    fun setSearchQuery(query: String) {
        _browseState.update { state ->
            val next = state.copy(searchQuery = query)
            next.copy(selectedFiles = next.reconciledSelection)
        }
    }

    fun setFileTypeFilter(filter: FileTypeFilter) {
        _browseState.update { state ->
            val next = state.copy(fileTypeFilter = filter)
            next.copy(selectedFiles = next.reconciledSelection)
        }
    }

    fun clearFilters() {
        _browseState.update { state ->
            state.copy(
                searchQuery = "",
                fileTypeFilter = FileTypeFilter.ALL,
                selectedFiles = emptySet(),
            )
        }
    }

    fun clearSelection() {
        _browseState.update { it.copy(selectedFiles = emptySet()) }
    }

    fun createDirectory(name: String) {
        val validatedName = validFileNameOrNotify(name) ?: return
        val provider = fileProvider
        val path = _browseState.value.currentPath
        launchOperation("Creating folder") {
            provider.createDirectory(path, validatedName).fold(
                onSuccess = {
                    showSnackbar("Folder created")
                    refreshFiles()
                },
                onFailure = { showSnackbar(OperationMessages.failure("Create folder", it)) },
            )
        }
    }

    fun createFile(name: String) {
        val validatedName = validFileNameOrNotify(name) ?: return
        val provider = fileProvider
        val path = _browseState.value.currentPath
        launchOperation("Creating file") {
            provider.createFile(path, validatedName).fold(
                onSuccess = {
                    showSnackbar("File created")
                    refreshFiles()
                },
                onFailure = { showSnackbar(OperationMessages.failure("Create file", it)) },
            )
        }
    }

    fun deleteSelected() {
        val state = _browseState.value
        val selectedPaths = state.selectedFiles.toList()
        if (selectedPaths.isEmpty()) return
        val moveToTrash = state.source == FileSource.LOCAL && useTrash.value
        val provider = fileProvider
        launchOperation(if (moveToTrash) "Moving to Trash" else "Deleting") {
            val count = selectedPaths.size
            var failed = 0
            var firstError: Throwable? = null
            for (path in selectedPaths) {
                val result = if (moveToTrash) trashManager.moveToTrash(path).map { Unit } else provider.delete(path)
                result.onFailure { error ->
                    failed++
                    if (firstError == null) firstError = error
                }
            }
            clearSelection()
            refreshFiles()
            if (failed > 0) {
                showSnackbar(
                    OperationMessages.partial(
                        failed = failed,
                        total = count,
                        action = if (moveToTrash) "moved to Trash" else "deleted",
                        error = checkNotNull(firstError),
                    )
                )
            } else {
                val action = if (moveToTrash) "moved to Trash" else "permanently deleted"
                showSnackbar("$count item${if (count > 1) "s" else ""} $action")
            }
        }
    }

    fun rename(oldPath: String, newName: String) {
        val validatedName = validFileNameOrNotify(newName) ?: return
        val provider = fileProvider
        launchOperation("Renaming") {
            provider.rename(oldPath, validatedName).fold(
                onSuccess = {
                    showSnackbar("Renamed to $validatedName")
                    refreshFiles()
                },
                onFailure = { showSnackbar(OperationMessages.failure("Rename", it)) },
            )
        }
    }

    fun copyToClipboard(paths: List<String>) {
        _clipboardPaths.value = paths
        _clipboardOperation.value = ClipboardOperation.COPY
        clipboardProvider = fileProvider
        clearSelection()
        showSnackbar("${paths.size} item${if (paths.size > 1) "s" else ""} copied")
    }

    fun cutToClipboard(paths: List<String>) {
        _clipboardPaths.value = paths
        _clipboardOperation.value = ClipboardOperation.CUT
        clipboardProvider = fileProvider
        clearSelection()
        showSnackbar("${paths.size} item${if (paths.size > 1) "s" else ""} cut")
    }

    fun clearClipboard() {
        _clipboardPaths.value = emptyList()
        _clipboardOperation.value = ClipboardOperation.NONE
        clipboardProvider = null
    }

    fun paste() {
        val paths = _clipboardPaths.value.toList()
        val operation = _clipboardOperation.value
        if (paths.isEmpty() || operation == ClipboardOperation.NONE) return
        val destPath = _browseState.value.currentPath
        val sourceProvider = clipboardProvider ?: fileProvider
        val destinationProvider = fileProvider
        launchOperation("Pasting") {
            var failed = 0
            var firstError: Throwable? = null
            for (sourcePath in paths) {
                val result = when (operation) {
                    ClipboardOperation.COPY -> {
                        if (sourceProvider === destinationProvider) {
                            destinationProvider.copy(sourcePath, destPath)
                        } else {
                            FileOperationCoordinator.copyPath(sourceProvider, destinationProvider, sourcePath, destPath)
                        }
                    }
                    ClipboardOperation.CUT -> {
                        if (sourceProvider === destinationProvider) {
                            destinationProvider.move(sourcePath, destPath)
                        } else {
                            FileOperationCoordinator.movePath(sourceProvider, destinationProvider, sourcePath, destPath)
                        }
                    }
                    ClipboardOperation.NONE -> Result.success(Unit)
                }
                result.onFailure { error ->
                    failed++
                    if (firstError == null) firstError = error
                }
            }
            if (operation == ClipboardOperation.CUT && failed == 0) {
                clearClipboard()
            }
            refreshFiles()
            if (failed > 0) {
                showSnackbar(
                    OperationMessages.partial(
                        failed = failed,
                        total = paths.size,
                        action = "pasted",
                        error = checkNotNull(firstError),
                    )
                )
            } else {
                showSnackbar("Pasted successfully")
            }
        }
    }

    fun downloadFile(path: String) {
        downloadPaths(listOf(path), clearSelection = false)
    }

    fun downloadSelected() {
        downloadPaths(_browseState.value.selectedFiles.toList(), clearSelection = true)
    }

    private fun downloadPaths(paths: List<String>, clearSelection: Boolean) {
        if (paths.isEmpty()) return
        val state = _browseState.value
        val provider = fileProvider
        launchOperation("Downloading") {
            if (!state.source.isNetwork) {
                showSnackbar("This file is already on this device")
                return@launchOperation
            }

            val items = runCatching {
                paths.map { path ->
                    state.files.firstOrNull { it.path == path }
                        ?: provider.getFileInfo(path).getOrThrow()
                }
            }.getOrElse { error ->
                showSnackbar(OperationMessages.failure("Download", error))
                return@launchOperation
            }
            if (items.any { it.path == state.currentPath }) {
                showSnackbar("Choose files or folders inside this location")
                return@launchOperation
            }

            if (clearSelection) clearSelection()
            showSnackbar("Downloading ${items.size} item${if (items.size == 1) "" else "s"}...")

            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            FileDownloader.download(provider, items, downloads).fold(
                onSuccess = { result ->
                    val count = result.downloadedFiles + result.downloadedDirectories
                    showSnackbar(
                        "Downloaded $count item${if (count == 1) "" else "s"} to Downloads"
                    )
                },
                onFailure = { error ->
                    showSnackbar(OperationMessages.failure("Download", error))
                },
            )
        }
    }

    // Connection management
    fun connectToRemote(connection: RemoteConnection) {
        viewModelScope.launch {
            val sessionId = remoteSessionId(connection.id)
            if (_sessions.value.none { it.id == sessionId }) {
                val normalizedPath = BrowserNavigationBounds.normalizePath(connection.remotePath)
                val source = sourceForProtocol(connection.protocol)
                sessionProviders[sessionId] = FileProviderFactory.createRemote(
                    context = getApplication(),
                    connection = connection,
                )
                _sessions.update { sessions ->
                    sessions + BrowserSession(
                        id = sessionId,
                        title = connection.name,
                        source = source,
                        rootPath = normalizedPath,
                        currentPath = normalizedPath,
                        connectionId = connection.id,
                        host = connection.host,
                    )
                }
            }
            connectionRepository.updateLastConnected(connection.id, System.currentTimeMillis())
            activateSessionInternal(sessionId)
        }
    }

    fun disconnectRemote() {
        closeActiveSession()
    }

    fun activateSession(sessionId: String) {
        viewModelScope.launch {
            activateSessionInternal(sessionId)
        }
    }

    fun closeActiveSession() {
        _activeSession.value?.let { closeSession(it.id) }
    }

    fun closeSession(sessionId: String) {
        viewModelScope.launch {
            closeSessionInternal(sessionId)
        }
    }

    fun saveConnection(connection: RemoteConnection) {
        viewModelScope.launch {
            runCatching { connectionRepository.save(connection) }.fold(
                onSuccess = {
                    showSnackbar(if (connection.id == 0L) "Connection saved" else "Connection updated")
                },
                onFailure = {
                    showSnackbar("Could not protect and save the connection")
                },
            )
        }
    }

    fun deleteConnection(connection: RemoteConnection) {
        viewModelScope.launch {
            runCatching { connectionRepository.delete(connection) }.fold(
                onSuccess = { showSnackbar("Connection deleted") },
                onFailure = { showSnackbar("Could not delete the connection") },
            )
        }
    }

    fun refreshTrash() {
        viewModelScope.launch { loadTrashEntries() }
    }

    fun toggleTrashSelection(id: String) {
        _trashState.update { state ->
            val selected = state.selectedIds.toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            state.copy(selectedIds = selected)
        }
    }

    fun selectAllTrash() {
        _trashState.update { state -> state.copy(selectedIds = state.entries.mapTo(mutableSetOf()) { it.id }) }
    }

    fun clearTrashSelection() {
        _trashState.update { it.copy(selectedIds = emptySet()) }
    }

    fun restoreSelectedTrash() {
        val entries = selectedTrashEntries()
        if (entries.isEmpty()) return
        launchOperation("Restoring from Trash") {
            val failures = runTrashActions(entries, trashManager::restore)
            loadTrashEntries()
            showTrashResult(
                entries = entries,
                failures = failures,
                successMessage = { count -> "$count item${if (count == 1) "" else "s"} restored" },
                failureAction = "restore",
            )
        }
    }

    fun deleteSelectedTrashPermanently() {
        val entries = selectedTrashEntries()
        if (entries.isEmpty()) return
        launchOperation("Deleting permanently") {
            val failures = runTrashActions(entries, trashManager::deletePermanently)
            loadTrashEntries()
            showTrashResult(
                entries = entries,
                failures = failures,
                successMessage = { count -> "$count item${if (count == 1) "" else "s"} permanently deleted" },
                failureAction = "delete",
            )
        }
    }

    fun emptyTrash() {
        launchOperation("Emptying Trash") {
            trashManager.empty().fold(
                onSuccess = { removed ->
                    loadTrashEntries()
                    showSnackbar(if (removed == 0) "Trash is already empty" else "Trash emptied")
                },
                onFailure = { error ->
                    loadTrashEntries()
                    showSnackbar("Could not empty Trash: ${error.message ?: "Unknown error"}")
                },
            )
        }
    }

    fun toggleBookmark(path: String, name: String) {
        if (_browseState.value.source != FileSource.LOCAL) {
            showSnackbar("Bookmarks are available for local folders")
            return
        }
        viewModelScope.launch {
            if (bookmarkDao.isBookmarked(path)) {
                bookmarkDao.deleteByPath(path)
            } else {
                bookmarkDao.insert(
                    Bookmark(
                        name = name,
                        path = path,
                        source = _browseState.value.source,
                    )
                )
            }
        }
    }

    // Snackbar
    private fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    private fun launchOperation(label: String, block: suspend () -> Unit) {
        val active = _operationState.value as? OperationState.Running
        if (active != null) {
            showSnackbar("${active.label} is already in progress")
            return
        }
        _operationState.value = OperationState.Running(label)
        viewModelScope.launch {
            try {
                block()
            } catch (error: Throwable) {
                showSnackbar(OperationMessages.failure(label, error))
            } finally {
                _operationState.value = OperationState.Idle
            }
        }
    }

    private suspend fun loadTrashEntries() {
        _trashState.update { it.copy(isLoading = true, error = null) }
        runCatching { trashManager.listEntries() }.fold(
            onSuccess = { entries ->
                _trashState.update { state ->
                    state.copy(
                        entries = entries,
                        selectedIds = state.selectedIds.intersect(entries.mapTo(mutableSetOf()) { it.id }),
                        isLoading = false,
                        error = null,
                    )
                }
            },
            onFailure = { error ->
                _trashState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Could not load Trash",
                    )
                }
            },
        )
    }

    private fun selectedTrashEntries(): List<TrashEntry> {
        val state = _trashState.value
        return state.entries.filter { it.id in state.selectedIds }
    }

    private suspend fun runTrashActions(
        entries: List<TrashEntry>,
        action: suspend (TrashEntry) -> Result<Unit>,
    ): List<Throwable> = buildList {
        entries.forEach { entry -> action(entry).exceptionOrNull()?.let(::add) }
    }

    private fun showTrashResult(
        entries: List<TrashEntry>,
        failures: List<Throwable>,
        successMessage: (Int) -> String,
        failureAction: String,
    ) {
        val succeeded = entries.size - failures.size
        when {
            failures.isEmpty() -> showSnackbar(successMessage(succeeded))
            succeeded > 0 -> showSnackbar("$succeeded succeeded; ${failures.size} could not $failureAction")
            else -> showSnackbar("Could not $failureAction: ${failures.first().message ?: "Unknown error"}")
        }
    }

    private fun validFileNameOrNotify(name: String): String? =
        when (val result = FileNameValidator.validate(name)) {
            is FileNameValidationResult.Valid -> result.name
            is FileNameValidationResult.Invalid -> {
                showSnackbar(result.message)
                null
            }
        }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    // Settings
    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { prefs.setTheme(theme) }
    }

    fun setShowHidden(show: Boolean) {
        viewModelScope.launch { prefs.setShowHidden(show) }
    }

    fun setUseTrash(useTrash: Boolean) {
        viewModelScope.launch { prefs.setUseTrash(useTrash) }
    }

    fun setLimitedAccessAccepted(accepted: Boolean) {
        viewModelScope.launch { prefs.setLimitedAccessAccepted(accepted) }
    }

    fun setSortBy(sortBy: SortBy) {
        viewModelScope.launch { prefs.setSortBy(sortBy) }
    }

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { prefs.setSortOrder(order) }
    }

    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { prefs.setViewMode(mode) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            sessionProviders.values.forEach { it.disconnect() }
            if (sessionProviders.isEmpty()) fileProvider.disconnect()
        }
    }

    private suspend fun activateSessionInternal(sessionId: String): Boolean {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return false
        val provider = sessionProviders[sessionId] ?: return false
        fileProvider = provider
        browserSessionRootPath = session.rootPath
        _activeSession.value = session
        _browseState.update {
            it.copy(
                currentPath = session.currentPath,
                source = session.source,
                selectedFiles = emptySet(),
                error = null,
                searchQuery = "",
                fileTypeFilter = FileTypeFilter.ALL,
            )
        }
        loadFiles(session.currentPath, session.id, provider)
        return true
    }

    private suspend fun closeSessionInternal(sessionId: String) {
        val closedSession = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        sessionProviders.remove(sessionId)?.disconnect()
        val wasActive = _activeSession.value?.id == sessionId
        val remainingSessions = _sessions.value.filterNot { it.id == sessionId }
        _sessions.value = remainingSessions

        if (!wasActive) {
            refreshActiveSessionSnapshot()
            return
        }

        val nextSession = remainingSessions.lastOrNull()
        if (nextSession != null) {
            activateSessionInternal(nextSession.id)
            return
        }

        fileProvider = FileProviderFactory.createLocal()
        browserSessionRootPath = null
        _activeSession.value = null
        _browseState.update {
            it.copy(
                currentPath = closedSession.rootPath,
                files = emptyList(),
                isLoading = false,
                error = null,
                selectedFiles = emptySet(),
                source = FileSource.LOCAL,
                searchQuery = "",
                fileTypeFilter = FileTypeFilter.ALL,
            )
        }
    }

    private fun updateSession(sessionId: String, transform: (BrowserSession) -> BrowserSession) {
        _sessions.update { sessions ->
            sessions.map { session ->
                if (session.id == sessionId) transform(session) else session
            }
        }
        refreshActiveSessionSnapshot()
    }

    private fun refreshActiveSessionSnapshot() {
        val activeId = _activeSession.value?.id ?: return
        _activeSession.value = _sessions.value.firstOrNull { it.id == activeId }
    }

    private fun isCurrentLoad(sessionId: String?): Boolean =
        sessionId == _activeSession.value?.id

    private fun localSessionId(rootPath: String): String =
        "local:${BrowserNavigationBounds.normalizePath(rootPath)}"

    private fun remoteSessionId(connectionId: Long): String =
        "remote:$connectionId"

    private fun safSessionId(rootPath: String): String =
        "saf:$rootPath"

    private fun titleForLocalPath(path: String): String =
        BrowserNavigationBounds.normalizePath(path).substringAfterLast("/").ifEmpty { "Local Files" }

    private fun sourceForProtocol(protocol: ConnectionProtocol): FileSource = when (protocol) {
        ConnectionProtocol.SFTP -> FileSource.SFTP
        ConnectionProtocol.FTP -> FileSource.FTP
        ConnectionProtocol.SMB -> FileSource.SMB
        ConnectionProtocol.WEBDAV -> FileSource.WEBDAV
    }
}

enum class ClipboardOperation {
    NONE, COPY, CUT,
}
