package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.BrowseState
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.FileProviderFactory
import com.voyagerfiles.ui.theme.AppTheme
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

    private val _browseState = MutableStateFlow(BrowseState())
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    private val _clipboardPaths = MutableStateFlow<List<String>>(emptyList())
    val clipboardPaths: StateFlow<List<String>> = _clipboardPaths.asStateFlow()

    private val _clipboardOperation = MutableStateFlow(ClipboardOperation.NONE)
    val clipboardOperation: StateFlow<ClipboardOperation> = _clipboardOperation.asStateFlow()

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
            navigateTo(defaultPath)
        }
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            _browseState.update { it.copy(currentPath = path, isLoading = true, error = null, selectedFiles = emptySet()) }
            loadFiles(path)
        }
    }

    fun navigateUp(): Boolean {
        val parent = fileProvider.getParentPath(_browseState.value.currentPath)
        if (parent != null) {
            navigateTo(parent)
            return true
        }
        return false
    }

    fun refresh() {
        refreshFiles()
    }

    private fun refreshFiles() {
        viewModelScope.launch {
            loadFiles(_browseState.value.currentPath)
        }
    }

    private suspend fun loadFiles(path: String) {
        _browseState.update { it.copy(isLoading = true, error = null) }
        fileProvider.listFiles(path).fold(
            onSuccess = { files ->
                val filtered = if (_browseState.value.showHidden) files
                else files.filter { !it.isHidden }
                val sorted = sortFiles(filtered)
                _browseState.update { it.copy(files = sorted, isLoading = false) }
            },
            onFailure = { error ->
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
        viewModelScope.launch {
            fileProvider.createDirectory(_browseState.value.currentPath, name).fold(
                onSuccess = {
                    showSnackbar("Folder created")
                    refreshFiles()
                },
                onFailure = { showSnackbar("Failed to create folder: ${it.message}") },
            )
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch {
            fileProvider.createFile(_browseState.value.currentPath, name).fold(
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
        viewModelScope.launch {
            fileProvider.rename(oldPath, newName).fold(
                onSuccess = {
                    showSnackbar("Renamed to $newName")
                    refreshFiles()
                },
                onFailure = { showSnackbar("Rename failed: ${it.message}") },
            )
        }
    }

    fun copyToClipboard(paths: List<String>) {
        _clipboardPaths.value = paths
        _clipboardOperation.value = ClipboardOperation.COPY
        clearSelection()
        showSnackbar("${paths.size} item${if (paths.size > 1) "s" else ""} copied")
    }

    fun cutToClipboard(paths: List<String>) {
        _clipboardPaths.value = paths
        _clipboardOperation.value = ClipboardOperation.CUT
        clearSelection()
        showSnackbar("${paths.size} item${if (paths.size > 1) "s" else ""} cut")
    }

    fun clearClipboard() {
        _clipboardPaths.value = emptyList()
        _clipboardOperation.value = ClipboardOperation.NONE
    }

    fun paste() {
        viewModelScope.launch {
            val destPath = _browseState.value.currentPath
            var failed = 0
            for (sourcePath in _clipboardPaths.value) {
                val result = when (_clipboardOperation.value) {
                    ClipboardOperation.COPY -> fileProvider.copy(sourcePath, destPath)
                    ClipboardOperation.CUT -> fileProvider.move(sourcePath, destPath)
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

    // Connection management
    fun connectToRemote(connection: RemoteConnection) {
        viewModelScope.launch {
            fileProvider.disconnect()
            fileProvider = FileProviderFactory.createRemote(connection)
            val source = when (connection.protocol) {
                com.voyagerfiles.data.model.ConnectionProtocol.SFTP -> FileSource.SFTP
                com.voyagerfiles.data.model.ConnectionProtocol.FTP -> FileSource.FTP
                com.voyagerfiles.data.model.ConnectionProtocol.SMB -> FileSource.SMB
                com.voyagerfiles.data.model.ConnectionProtocol.WEBDAV -> FileSource.WEBDAV
            }
            _browseState.update { it.copy(source = source) }
            connectionDao.updateLastConnected(connection.id, System.currentTimeMillis())
            navigateTo(connection.remotePath)
        }
    }

    fun disconnectRemote() {
        viewModelScope.launch {
            fileProvider.disconnect()
            fileProvider = FileProviderFactory.createLocal()
            _browseState.update { it.copy(source = FileSource.LOCAL) }
            val path = prefs.defaultPath.first()
            navigateTo(path)
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
        viewModelScope.launch { fileProvider.disconnect() }
    }
}

enum class ClipboardOperation {
    NONE, COPY, CUT,
}
