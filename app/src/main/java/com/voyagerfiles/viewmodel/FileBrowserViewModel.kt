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
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.data.model.isNetwork
import com.voyagerfiles.data.remote.saf.SafFileProvider
import com.voyagerfiles.data.repository.FileDownloader
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.FileProviderFactory
import com.voyagerfiles.ui.theme.AppTheme
import com.voyagerfiles.util.FileNameValidationResult
import com.voyagerfiles.util.FileNameValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FileBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val db = AppDatabase.getInstance(application)
    private val connectionDao = db.connectionDao()
    private val bookmarkDao = db.bookmarkDao()

    private var fileProvider: FileProvider = FileProviderFactory.createLocal()
    private var browserSessionRootPath: String? = null
    private val sessionProviders = mutableMapOf<String, FileProvider>()

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

    val theme = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.SYSTEM)
    val connections = connectionDao.getAllConnections().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val bookmarks = bookmarkDao.getAllBookmarks().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
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
        _browseState.update { it.copy(isLoading = true, error = null) }
        provider.listFiles(path).fold(
            onSuccess = { files ->
                if (!isCurrentLoad(sessionId)) return@fold
                val filtered = if (_browseState.value.showHidden) files
                else files.filter { !it.isHidden }
                val sorted = sortFiles(filtered)
                _browseState.update { it.copy(files = sorted, isLoading = false) }
            },
            onFailure = { error ->
                if (!isCurrentLoad(sessionId)) return@fold
                _browseState.update {
                    it.copy(
                        error = error.message ?: "Failed to list files",
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
            state.copy(selectedFiles = state.files.map { it.path }.toSet())
        }
    }

    fun clearSelection() {
        _browseState.update { it.copy(selectedFiles = emptySet()) }
    }

    fun createDirectory(name: String) {
        val validatedName = validFileNameOrNotify(name) ?: return
        viewModelScope.launch {
            fileProvider.createDirectory(_browseState.value.currentPath, validatedName).fold(
                onSuccess = {
                    showSnackbar("Folder created")
                    refreshFiles()
                },
                onFailure = { showSnackbar("Failed to create folder: ${it.message}") },
            )
        }
    }

    fun createFile(name: String) {
        val validatedName = validFileNameOrNotify(name) ?: return
        viewModelScope.launch {
            fileProvider.createFile(_browseState.value.currentPath, validatedName).fold(
                onSuccess = {
                    showSnackbar("File created")
                    refreshFiles()
                },
                onFailure = { showSnackbar("Failed to create file: ${it.message}") },
            )
        }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val count = _browseState.value.selectedFiles.size
            var failed = 0
            for (path in _browseState.value.selectedFiles) {
                fileProvider.delete(path).onFailure { failed++ }
            }
            clearSelection()
            refreshFiles()
            if (failed > 0) {
                showSnackbar("$failed of $count items failed to delete")
            } else {
                showSnackbar("$count item${if (count > 1) "s" else ""} deleted")
            }
        }
    }

    fun rename(oldPath: String, newName: String) {
        val validatedName = validFileNameOrNotify(newName) ?: return
        viewModelScope.launch {
            fileProvider.rename(oldPath, validatedName).fold(
                onSuccess = {
                    showSnackbar("Renamed to $validatedName")
                    refreshFiles()
                },
                onFailure = { showSnackbar("Rename failed: ${it.message}") },
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
        viewModelScope.launch {
            val destPath = _browseState.value.currentPath
            val sourceProvider = clipboardProvider ?: fileProvider
            val destinationProvider = fileProvider
            var failed = 0
            for (sourcePath in _clipboardPaths.value) {
                val result = when (_clipboardOperation.value) {
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
                result.onFailure { failed++ }
            }
            if (_clipboardOperation.value == ClipboardOperation.CUT && failed == 0) {
                clearClipboard()
            }
            refreshFiles()
            if (failed > 0) {
                showSnackbar("$failed items failed to paste")
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
        viewModelScope.launch {
            val state = _browseState.value
            if (paths.isEmpty()) return@launch
            if (!state.source.isNetwork) {
                showSnackbar("This file is already on this device")
                return@launch
            }

            val provider = fileProvider
            val items = runCatching {
                paths.map { path ->
                    state.files.firstOrNull { it.path == path }
                        ?: provider.getFileInfo(path).getOrThrow()
                }
            }.getOrElse { error ->
                showSnackbar("Download failed: ${error.message}")
                return@launch
            }
            if (items.any { it.path == state.currentPath }) {
                showSnackbar("Choose files or folders inside this location")
                return@launch
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
                    showSnackbar("Download failed: ${error.message}")
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
                sessionProviders[sessionId] = FileProviderFactory.createRemote(connection)
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
            connectionDao.updateLastConnected(connection.id, System.currentTimeMillis())
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
            if (connection.id == 0L) {
                connectionDao.insert(connection)
                showSnackbar("Connection saved")
            } else {
                connectionDao.update(connection)
                showSnackbar("Connection updated")
            }
        }
    }

    fun deleteConnection(connection: RemoteConnection) {
        viewModelScope.launch {
            connectionDao.delete(connection)
            showSnackbar("Connection deleted")
        }
    }

    fun toggleBookmark(path: String, name: String) {
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
