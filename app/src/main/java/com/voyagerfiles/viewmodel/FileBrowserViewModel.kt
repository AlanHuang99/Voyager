package com.voyagerfiles.viewmodel

import com.voyagerfiles.app.VoyagerApp
import kotlinx.coroutines.CancellationException

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voyagerfiles.R
import com.voyagerfiles.data.archive.ArchiveFormat
import com.voyagerfiles.data.archive.ArchiveProgress
import com.voyagerfiles.data.archive.ArchiveService
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.BrowseState
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.data.model.HomeLayout
import com.voyagerfiles.data.model.HomeSection
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.model.SessionAutoCloseTimeout
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.TrashEntry
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.data.model.isNetwork
import com.voyagerfiles.data.remote.saf.SafFileProvider
import com.voyagerfiles.data.remote.webdav.WebDavFileProvider
import com.voyagerfiles.data.repository.ConnectionRepository
import com.voyagerfiles.data.repository.DownloadProgress
import com.voyagerfiles.data.repository.FileDownloader
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.FileProviderFactory
import com.voyagerfiles.data.repository.LocalTrashManager
import com.voyagerfiles.data.repository.StreamTransferProgress
import com.voyagerfiles.security.AndroidCredentialCipher
import com.voyagerfiles.playback.PlaybackEntry
import com.voyagerfiles.playback.WebDavPlaybackProvider
import com.voyagerfiles.ui.theme.AppTheme
import com.voyagerfiles.ui.text.UiText
import com.voyagerfiles.util.FileNameValidationResult
import com.voyagerfiles.util.FileNameValidator
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.util.UploadSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun interface RemoteFileProviderFactory {
    fun create(context: Application, connection: RemoteConnection): FileProvider
}

fun interface WebDavPlaybackPreparer {
    suspend fun prepare(context: Application, provider: FileProvider, file: FileItem): Result<Uri>
}

private val defaultRemoteFileProviderFactory = RemoteFileProviderFactory { context, connection ->
    FileProviderFactory.createRemote(context, connection)
}

private val defaultWebDavPlaybackPreparer = WebDavPlaybackPreparer { context, provider, file ->
    runCatching {
        val webDavProvider = provider as? WebDavFileProvider
            ?: error("The active provider is not WebDAV")
        val prepared = webDavProvider.createPlaybackSource(file.path).getOrThrow()
        try {
            WebDavPlaybackProvider.register(
                context,
                PlaybackEntry(file.name, file.mimeType, prepared.metadata.size, prepared.source),
            )
        } catch (error: Throwable) {
            prepared.source.close()
            throw error
        }
    }
}

class FileBrowserViewModel @JvmOverloads constructor(
    application: Application,
    private val remoteProviderFactory: RemoteFileProviderFactory = defaultRemoteFileProviderFactory,
    private val playbackPreparer: WebDavPlaybackPreparer = defaultWebDavPlaybackPreparer,
) : AndroidViewModel(application) {

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
    private var initialNavigationJob: Job? = null
    private val sessionProviders = mutableMapOf<String, FileProvider>()
    private val loadGuard = DirectoryLoadGuard()
    private val sessionAutoCloseTracker = SessionAutoCloseTracker()
    private var backgroundedSessionIds = emptySet<String>()
    private var deferredAutoCloseSessionIds = emptySet<String>()

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

    private val _snackbarMessage = MutableStateFlow<UiText?>(null)
    val snackbarMessage: StateFlow<UiText?> = _snackbarMessage.asStateFlow()

    private val operationController = (application as VoyagerApp).transfers
    val operationState: StateFlow<OperationState> = operationController.state

    private val _sessionClosureGeneration = MutableStateFlow(0L)
    val sessionClosureGeneration: StateFlow<Long> = _sessionClosureGeneration.asStateFlow()

    private val _trashState = MutableStateFlow(TrashState())
    val trashState: StateFlow<TrashState> = _trashState.asStateFlow()

    val theme = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.SYSTEM)
    val useTrash = prefs.useTrash.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoCloseSessions = prefs.autoCloseSessions.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val sessionAutoCloseTimeout = prefs.sessionAutoCloseTimeout.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SessionAutoCloseTimeout.FIFTEEN_MINUTES,
    )
    val homeLayout = prefs.homeLayout.stateInWithLoading(viewModelScope)
    val limitedAccessAccepted = prefs.limitedAccessAccepted.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val connections = connectionRepository.connections.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val bookmarks = bookmarkDao.getAllBookmarks().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            runCatching { connectionRepository.migratePlaintextCredentials() }
                .onFailure {
                    showSnackbar(UiText.Resource(R.string.credential_migration_failed))
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
        initialNavigationJob = viewModelScope.launch {
            val defaultPath = prefs.defaultPath.first()
            navigateToPath(defaultPath)
        }
    }

    fun navigateTo(path: String) {
        cancelInitialNavigation()
        viewModelScope.launch {
            val normalizedPath = BrowserNavigationBounds.normalizePath(path)
            val rootPath = browserSessionRootPath
            if (
                rootPath != null &&
                _browseState.value.source != FileSource.SAF &&
                !BrowserNavigationBounds.isPathAtOrInsideRoot(normalizedPath, rootPath)
            ) {
                showSnackbar(UiText.Resource(R.string.location_outside_session))
                return@launch
            }
            navigateToPath(normalizedPath)
        }
    }

    fun openLocalRoot(path: String) {
        cancelInitialNavigation()
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
        cancelInitialNavigation()
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
        launchOperation(R.string.progress_creating_folder, R.string.operation_create_folder) {
            provider.createDirectory(path, validatedName).fold(
                onSuccess = {
                    showSnackbar(UiText.Resource(R.string.folder_created))
                    refreshFiles()
                },
                onFailure = { showSnackbar(OperationMessages.failure(R.string.operation_create_folder, it)) },
            )
        }
    }

    fun createFile(name: String) {
        val validatedName = validFileNameOrNotify(name) ?: return
        val provider = fileProvider
        val path = _browseState.value.currentPath
        launchOperation(R.string.progress_creating_file, R.string.operation_create_file) {
            provider.createFile(path, validatedName).fold(
                onSuccess = {
                    showSnackbar(UiText.Resource(R.string.file_created))
                    refreshFiles()
                },
                onFailure = { showSnackbar(OperationMessages.failure(R.string.operation_create_file, it)) },
            )
        }
    }

    fun uploadDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val destinationProvider = fileProvider
        val destinationPath = _browseState.value.currentPath
        val contentResolver = getApplication<Application>().contentResolver
        val progressLabel = UiText.Resource(R.string.progress_uploading)
        launchOperation(R.string.progress_uploading, R.string.operation_upload) {
            val sources = withContext(Dispatchers.IO) {
                uris.map { uri -> UploadSourceFactory.fromUri(contentResolver, uri) }
            }
            val validatedSources = sources.map { source ->
                when (val result = FileNameValidator.validate(source.name)) {
                    is FileNameValidationResult.Valid -> source.copy(name = result.name)
                    is FileNameValidationResult.Invalid -> {
                        throw UiTextException(UiText.Resource(result.messageRes))
                    }
                }
            }
            var failed = 0
            var firstError: Throwable? = null
            var completed = 0
            for (source in validatedSources) {
                updateOperationProgress(
                    TransferProgress(
                        label = progressLabel,
                        completedItems = completed,
                        totalItems = validatedSources.size,
                        currentItemName = source.name,
                        totalBytes = source.size,
                    ),
                )
                val progressThrottle = StreamProgressThrottle()
                var latestStreamProgress: StreamTransferProgress? = null
                val publishStreamProgress: (StreamTransferProgress) -> Unit = { streamProgress ->
                    latestStreamProgress = streamProgress
                    if (progressThrottle.shouldPublish(streamProgress)) {
                        updateOperationProgress(
                            TransferProgress(
                                label = progressLabel,
                                completedItems = completed,
                                totalItems = validatedSources.size,
                                currentItemName = source.name,
                                copiedBytes = streamProgress.bytesTransferred,
                                totalBytes = streamProgress.totalBytes,
                                elapsedNanos = streamProgress.elapsedNanos,
                            ),
                        )
                    }
                }
                FileOperationCoordinator.uploadFile(
                    source = source,
                    destinationProvider = destinationProvider,
                    destinationDirectoryPath = destinationPath,
                    onProgress = publishStreamProgress,
                ).onSuccess {
                    completed++
                    val streamProgress = latestStreamProgress
                    updateOperationProgress(
                        TransferProgress(
                            label = progressLabel,
                            completedItems = completed,
                            totalItems = validatedSources.size,
                            currentItemName = source.name,
                            copiedBytes = streamProgress?.bytesTransferred ?: 0,
                            totalBytes = streamProgress?.totalBytes ?: source.size,
                            elapsedNanos = streamProgress?.elapsedNanos ?: 0,
                        ),
                    )
                }.onFailure { error ->
                    failed++
                    if (firstError == null) firstError = error
                }
            }
            refreshFiles()
            if (failed > 0) {
                showSnackbar(
                    OperationMessages.partial(
                        failed = failed,
                        total = validatedSources.size,
                        action = R.string.action_uploaded,
                        error = checkNotNull(firstError),
                    )
                )
            } else {
                val count = validatedSources.size
                showSnackbar(UiText.Plural(R.plurals.files_uploaded, count, listOf(count)))
            }
        }
    }

    fun deleteSelected(mode: DeleteMode? = null) {
        val state = _browseState.value
        val selectedPaths = state.selectedFiles.toList()
        if (selectedPaths.isEmpty()) return
        val resolvedMode = mode ?: if (state.source == FileSource.LOCAL && useTrash.value) {
            DeleteMode.TRASH
        } else {
            DeleteMode.PERMANENT
        }
        if (resolvedMode == DeleteMode.TRASH && state.source != FileSource.LOCAL) {
            showSnackbar(UiText.Resource(R.string.trash_direct_local_only))
            return
        }
        val moveToTrash = resolvedMode == DeleteMode.TRASH
        val provider = fileProvider
        launchOperation(
            if (moveToTrash) R.string.progress_moving_to_trash else R.string.progress_deleting,
            if (moveToTrash) R.string.operation_trash else R.string.operation_delete,
        ) {
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
                        action = if (moveToTrash) R.string.action_moved_to_trash else R.string.action_deleted,
                        error = checkNotNull(firstError),
                    )
                )
            } else {
                showSnackbar(
                    UiText.Plural(
                        if (moveToTrash) R.plurals.items_trashed else R.plurals.items_deleted,
                        count,
                        listOf(count),
                    ),
                )
            }
        }
    }

    fun rename(oldPath: String, newName: String) {
        val validatedName = validFileNameOrNotify(newName) ?: return
        val provider = fileProvider
        launchOperation(R.string.progress_renaming, R.string.operation_rename) {
            provider.rename(oldPath, validatedName).fold(
                onSuccess = {
                    showSnackbar(UiText.Resource(R.string.renamed_to, listOf(UiText.Dynamic(validatedName))))
                    refreshFiles()
                },
                onFailure = { showSnackbar(OperationMessages.failure(R.string.operation_rename, it)) },
            )
        }
    }

    fun createZipFromSelection(archiveName: String) {
        val validatedName = validFileNameOrNotify(archiveName) ?: return
        if ('\\' in validatedName) {
            showSnackbar(UiText.Resource(R.string.name_no_backslashes))
            return
        }
        if (ArchiveFormat.detect(validatedName) != ArchiveFormat.ZIP) {
            showSnackbar(UiText.Resource(R.string.name_must_end_zip))
            return
        }
        val state = _browseState.value
        val selectedItems = state.files.filter { it.path in state.selectedFiles }
        if (selectedItems.isEmpty()) return
        val provider = fileProvider
        val destinationDirectory = state.currentPath
        val publishProgress = archiveProgressPublisher(R.string.progress_compressing)

        launchOperation(R.string.progress_compressing, R.string.operation_compress) {
            ArchiveService.createZip(
                provider = provider,
                selectedItems = selectedItems,
                destinationDirectory = destinationDirectory,
                archiveName = validatedName,
                onProgress = publishProgress,
            ).fold(
                onSuccess = { archive ->
                    clearSelection()
                    refreshFiles()
                    showSnackbar(
                        UiText.Resource(R.string.archive_created, listOf(UiText.Dynamic(archive.name))),
                    )
                },
                onFailure = { error ->
                    showSnackbar(OperationMessages.failure(R.string.operation_compress, error))
                },
            )
        }
    }

    fun extractSelectedArchive() {
        val state = _browseState.value
        if (state.selectedFiles.size != 1) {
            showSnackbar(UiText.Resource(R.string.archive_select_one))
            return
        }
        val archive = state.files.firstOrNull { it.path in state.selectedFiles }
        if (archive == null) {
            showSnackbar(UiText.Resource(R.string.selected_archive_unavailable))
            return
        }
        launchArchiveExtraction(
            archive = archive,
            destinationDirectory = state.currentPath,
            clearSelectionAfter = true,
        )
    }

    fun extractArchive(path: String) {
        val state = _browseState.value
        val archive = state.files.firstOrNull { it.path == path }
        if (archive == null) {
            showSnackbar(UiText.Resource(R.string.archive_unavailable))
            return
        }
        launchArchiveExtraction(
            archive = archive,
            destinationDirectory = state.currentPath,
            clearSelectionAfter = false,
        )
    }

    private fun launchArchiveExtraction(
        archive: FileItem,
        destinationDirectory: String,
        clearSelectionAfter: Boolean,
    ) {
        val provider = fileProvider
        val publishProgress = archiveProgressPublisher(R.string.progress_extracting)

        launchOperation(R.string.progress_extracting, R.string.operation_extract) {
            ArchiveService.extract(
                provider = provider,
                archive = archive,
                destinationDirectory = destinationDirectory,
                onProgress = publishProgress,
            ).fold(
                onSuccess = { extractionRoot ->
                    if (clearSelectionAfter) clearSelection()
                    refreshFiles()
                    showSnackbar(
                        UiText.Resource(
                            R.string.archive_extracted_to,
                            listOf(UiText.Dynamic(extractionRoot.name)),
                        ),
                    )
                },
                onFailure = { error ->
                    showSnackbar(OperationMessages.failure(R.string.operation_extract, error))
                },
            )
        }
    }

    fun copyToClipboard(paths: List<String>) {
        _clipboardPaths.value = paths
        _clipboardOperation.value = ClipboardOperation.COPY
        clipboardProvider = fileProvider
        clearSelection()
        showSnackbar(UiText.Plural(R.plurals.items_copied, paths.size, listOf(paths.size)))
    }

    fun cutToClipboard(paths: List<String>) {
        _clipboardPaths.value = paths
        _clipboardOperation.value = ClipboardOperation.CUT
        clipboardProvider = fileProvider
        clearSelection()
        showSnackbar(UiText.Plural(R.plurals.items_cut, paths.size, listOf(paths.size)))
    }

    fun clearClipboard() {
        _clipboardPaths.value = emptyList()
        _clipboardOperation.value = ClipboardOperation.NONE
        clipboardProvider = null
    }

    fun onAppBackgrounded(nowMillis: Long) {
        sessionAutoCloseTracker.onBackgrounded(nowMillis)
        backgroundedSessionIds = _sessions.value.mapTo(mutableSetOf()) { it.id }
    }

    fun onAppForegrounded(nowMillis: Long) {
        val sessionIds = backgroundedSessionIds
        backgroundedSessionIds = emptySet()
        val decision = sessionAutoCloseTracker.onForegrounded(
            nowMillis = nowMillis,
            enabled = autoCloseSessions.value,
            timeoutMillis = sessionAutoCloseTimeout.value.durationMillis,
            operationRunning = operationState.value is OperationState.Running,
        )
        when (decision) {
            SessionAutoCloseDecision.KEEP_OPEN -> Unit
            SessionAutoCloseDecision.CLOSE_NOW -> closeSessionsAfterInactivity(sessionIds)
            SessionAutoCloseDecision.DEFER_UNTIL_OPERATION_FINISHES -> {
                deferredAutoCloseSessionIds = deferredAutoCloseSessionIds + sessionIds
            }
        }
    }

    fun paste() {
        val paths = _clipboardPaths.value.toList()
        val operation = _clipboardOperation.value
        if (paths.isEmpty() || operation == ClipboardOperation.NONE) return
        val destPath = _browseState.value.currentPath
        val sourceProvider = clipboardProvider ?: fileProvider
        val destinationProvider = fileProvider
        val progressLabelRes = when (operation) {
            ClipboardOperation.COPY -> R.string.progress_copying
            ClipboardOperation.CUT -> R.string.progress_moving
            ClipboardOperation.NONE -> R.string.progress_pasting
        }
        val progressLabel = UiText.Resource(progressLabelRes)
        launchOperation(progressLabelRes, R.string.operation_paste) {
            var failed = 0
            var firstError: Throwable? = null
            var completed = 0
            val itemResults = buildMap {
                for (sourcePath in paths) {
                    val visibleItem = _browseState.value.files.firstOrNull { it.path == sourcePath }
                    put(
                        sourcePath,
                        visibleItem?.let { Result.success(it) }
                            ?: sourceProvider.getFileInfo(sourcePath),
                    )
                }
            }
            for (sourcePath in paths) {
                val itemResult = itemResults.getValue(sourcePath)
                val item = itemResult.getOrNull()
                updateOperationProgress(
                    TransferProgress(
                        label = progressLabel,
                        completedItems = completed,
                        totalItems = paths.size,
                        currentItemName = item?.name ?: sourcePath.substringAfterLast('/'),
                    )
                )
                if (item == null) {
                    failed++
                    if (firstError == null) {
                        firstError = itemResult.exceptionOrNull()
                            ?: IllegalStateException("The source item is unavailable")
                    }
                    continue
                }

                val progressThrottle = StreamProgressThrottle()
                var latestStreamProgress: StreamTransferProgress? = null
                val publishStreamProgress: (StreamTransferProgress) -> Unit = { streamProgress ->
                    latestStreamProgress = streamProgress
                    if (progressThrottle.shouldPublish(streamProgress)) {
                        updateOperationProgress(
                            TransferProgress(
                                label = progressLabel,
                                completedItems = completed,
                                totalItems = paths.size,
                                currentItemName = streamProgress.path.substringAfterLast('/'),
                                copiedBytes = streamProgress.bytesTransferred,
                                totalBytes = streamProgress.totalBytes,
                                elapsedNanos = streamProgress.elapsedNanos,
                            )
                        )
                    }
                }
                val result = when (operation) {
                    ClipboardOperation.COPY -> {
                        if (sourceProvider === destinationProvider) {
                            destinationProvider.copy(sourcePath, destPath)
                        } else {
                            FileOperationCoordinator.copyPath(
                                sourceProvider,
                                destinationProvider,
                                sourcePath,
                                destPath,
                                publishStreamProgress,
                            )
                        }
                    }
                    ClipboardOperation.CUT -> {
                        if (sourceProvider === destinationProvider) {
                            destinationProvider.move(sourcePath, destPath)
                        } else {
                            FileOperationCoordinator.movePath(
                                sourceProvider,
                                destinationProvider,
                                sourcePath,
                                destPath,
                                publishStreamProgress,
                            )
                        }
                    }
                    ClipboardOperation.NONE -> Result.success(Unit)
                }
                result.onSuccess {
                    completed++
                    val streamProgress = latestStreamProgress
                    updateOperationProgress(
                        TransferProgress(
                            label = progressLabel,
                            completedItems = completed,
                            totalItems = paths.size,
                            currentItemName = item.name,
                            copiedBytes = streamProgress?.bytesTransferred ?: 0,
                            totalBytes = streamProgress?.totalBytes,
                            elapsedNanos = streamProgress?.elapsedNanos ?: 0,
                        )
                    )
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
                        action = R.string.action_pasted,
                        error = checkNotNull(firstError),
                    )
                )
            } else {
                showSnackbar(UiText.Resource(R.string.paste_success))
            }
        }
    }

    fun downloadFile(path: String) {
        downloadPaths(listOf(path), clearSelection = false)
    }

    suspend fun prepareWebDavPlayback(file: FileItem): Result<Uri> = runCatching {
        val state = _browseState.value
        require(state.source == FileSource.WEBDAV && file.source == FileSource.WEBDAV) {
            "Direct playback requires the active WebDAV session"
        }
        require(!file.isDirectory) {
            "Direct opening requires a file"
        }
        require(state.files.any { it.path == file.path && it.source == file.source }) {
            "The WebDAV file is no longer active"
        }
        playbackPreparer.prepare(getApplication(), fileProvider, file).getOrThrow()
    }

    fun downloadSelected() {
        downloadPaths(_browseState.value.selectedFiles.toList(), clearSelection = true)
    }

    private fun downloadPaths(paths: List<String>, clearSelection: Boolean) {
        if (paths.isEmpty()) return
        val state = _browseState.value
        val provider = fileProvider
        val progressLabel = UiText.Resource(R.string.progress_downloading)
        launchOperation(R.string.progress_downloading, R.string.operation_download) {
            if (!state.source.isNetwork) {
                showSnackbar(UiText.Resource(R.string.file_already_on_device))
                return@launchOperation
            }

            val items = runCatching {
                paths.map { path ->
                    state.files.firstOrNull { it.path == path }
                        ?: provider.getFileInfo(path).getOrThrow()
                }
            }.getOrElse { error ->
                showSnackbar(OperationMessages.failure(R.string.operation_download, error))
                return@launchOperation
            }
            if (items.any { it.path == state.currentPath }) {
                showSnackbar(UiText.Resource(R.string.choose_inside_location))
                return@launchOperation
            }

            if (clearSelection) clearSelection()
            showSnackbar(UiText.Plural(R.plurals.items_downloading, items.size, listOf(items.size)))

            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val progressThrottle = StreamProgressThrottle()
            var lastCompletedRequestedItems = -1
            val publishProgress: (DownloadProgress) -> Unit = { progress ->
                val stream = progress.stream
                if (stream == null) {
                    progressThrottle.reset()
                    lastCompletedRequestedItems = progress.completedRequestedItems
                    updateOperationProgress(
                        TransferProgress(
                            label = progressLabel,
                            completedItems = progress.completedRequestedItems,
                            totalItems = progress.totalRequestedItems,
                            currentItemName = items
                                .getOrNull(progress.completedRequestedItems)
                                ?.name,
                        ),
                    )
                } else {
                    val completedItemsChanged =
                        progress.completedRequestedItems != lastCompletedRequestedItems
                    if (progressThrottle.shouldPublish(stream, force = completedItemsChanged)) {
                        updateOperationProgress(
                            TransferProgress(
                                label = progressLabel,
                                completedItems = progress.completedRequestedItems,
                                totalItems = progress.totalRequestedItems,
                                currentItemName = stream.path.substringAfterLast('/'),
                                copiedBytes = stream.bytesTransferred,
                                totalBytes = stream.totalBytes,
                                elapsedNanos = stream.elapsedNanos,
                            ),
                        )
                    }
                    lastCompletedRequestedItems = progress.completedRequestedItems
                }
            }
            FileDownloader.download(
                provider = provider,
                items = items,
                destinationDirectory = downloads,
                onProgress = publishProgress,
            ).fold(
                onSuccess = { result ->
                    val count = result.downloadedFiles + result.downloadedDirectories
                    showSnackbar(
                        UiText.Plural(
                            R.plurals.items_downloaded,
                            count,
                            listOf(count, UiText.Resource(R.string.downloads_folder)),
                        ),
                    )
                },
                onFailure = { error ->
                    showSnackbar(OperationMessages.failure(R.string.operation_download, error))
                },
            )
        }
    }

    // Connection management
    fun connectToRemote(connection: RemoteConnection) {
        cancelInitialNavigation()
        viewModelScope.launch {
            val sessionId = remoteSessionId(connection.id)
            if (_sessions.value.none { it.id == sessionId }) {
                val normalizedPath = BrowserNavigationBounds.normalizePath(connection.remotePath)
                val source = sourceForProtocol(connection.protocol)
                sessionProviders[sessionId] = remoteProviderFactory.create(getApplication(), connection)
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
        cancelInitialNavigation()
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
                    showSnackbar(
                        UiText.Resource(
                            if (connection.id == 0L) R.string.connection_saved else R.string.connection_updated,
                        ),
                    )
                },
                onFailure = {
                    showSnackbar(UiText.Resource(R.string.connection_save_failed))
                },
            )
        }
    }

    fun deleteConnection(connection: RemoteConnection) {
        viewModelScope.launch {
            runCatching { connectionRepository.delete(connection) }.fold(
                onSuccess = { showSnackbar(UiText.Resource(R.string.connection_deleted)) },
                onFailure = { showSnackbar(UiText.Resource(R.string.connection_delete_failed)) },
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
        launchOperation(R.string.progress_restoring_from_trash, R.string.operation_restore) {
            val failures = runTrashActions(entries, trashManager::restore)
            loadTrashEntries()
            showTrashResult(
                entries = entries,
                failures = failures,
                successMessage = { count ->
                    UiText.Plural(R.plurals.items_restored, count, listOf(count))
                },
                failureAction = R.string.operation_restore,
            )
        }
    }

    fun deleteSelectedTrashPermanently() {
        val entries = selectedTrashEntries()
        if (entries.isEmpty()) return
        launchOperation(R.string.progress_deleting_permanently, R.string.operation_delete) {
            val failures = runTrashActions(entries, trashManager::deletePermanently)
            loadTrashEntries()
            showTrashResult(
                entries = entries,
                failures = failures,
                successMessage = { count ->
                    UiText.Plural(R.plurals.items_deleted, count, listOf(count))
                },
                failureAction = R.string.operation_delete,
            )
        }
    }

    fun emptyTrash() {
        launchOperation(R.string.progress_emptying_trash, R.string.operation_empty_trash) {
            trashManager.empty().fold(
                onSuccess = { removed ->
                    loadTrashEntries()
                    showSnackbar(
                        UiText.Resource(
                            if (removed == 0) R.string.trash_already_empty else R.string.trash_emptied,
                        ),
                    )
                },
                onFailure = { error ->
                    loadTrashEntries()
                    showSnackbar(OperationMessages.failure(R.string.operation_empty_trash, error))
                },
            )
        }
    }

    fun addBookmark(path: String, name: String) {
        val source = _browseState.value.source
        if (source != FileSource.LOCAL) {
            showSnackbar(UiText.Resource(R.string.bookmarks_local_only))
            return
        }
        viewModelScope.launch {
            bookmarkDao.insertIfAbsent(Bookmark(name = name, path = path, source = source))
        }
    }

    fun removeBookmark(path: String, source: FileSource) {
        viewModelScope.launch {
            bookmarkDao.deleteByPath(path, source)
        }
    }

    // Snackbar
    private fun showSnackbar(message: UiText) {
        _snackbarMessage.value = message
    }

    private fun launchOperation(
        @StringRes progressLabel: Int,
        @StringRes operationName: Int,
        block: suspend () -> Unit,
    ) {
        val started = operationController.launch(
            label = UiText.Resource(progressLabel),
            cancellable = operationName in setOf(R.string.operation_upload, R.string.operation_paste, R.string.operation_download),
            onFailure = { error ->
                showSnackbar(
                    if (error is CancellationException) UiText.Resource(R.string.transfer_cancelled)
                    else OperationMessages.failure(operationName, error),
                )
            },
            onFinished = {
                if (autoCloseSessions.value && deferredAutoCloseSessionIds.isNotEmpty()) {
                    val sessionIds = deferredAutoCloseSessionIds
                    deferredAutoCloseSessionIds = emptySet()
                    closeSessionsAfterInactivity(sessionIds)
                }
            },
            block = block,
        )
        if (!started) {
            val active = operationState.value as? OperationState.Running ?: return
            showSnackbar(UiText.Resource(R.string.operation_already_running, listOf(active.label)))
        }
    }

    fun cancelOperation() = operationController.cancel()

    private fun updateOperationProgress(progress: TransferProgress) = operationController.update(progress)

    private fun cancelInitialNavigation() {
        initialNavigationJob?.cancel()
        initialNavigationJob = null
    }

    private fun archiveProgressPublisher(@StringRes labelRes: Int): (ArchiveProgress) -> Unit {
        val label = UiText.Resource(labelRes)
        var lastEntryName: String? = null
        var lastCompletedEntries = -1
        var lastPublishedBytes = 0L
        return { progress ->
            val entryChanged = progress.currentEntryName != lastEntryName
            val completedEntriesChanged = progress.completedEntries != lastCompletedEntries
            val reachedKnownTotal = progress.totalBytes
                ?.takeIf { it > 0 }
                ?.let { total ->
                    progress.processedBytes >= total &&
                        (entryChanged || lastPublishedBytes < total)
                }
                ?: false
            val crossedPublicationThreshold = !entryChanged &&
                progress.processedBytes - lastPublishedBytes >= PROGRESS_PUBLICATION_BYTES
            if (
                entryChanged ||
                completedEntriesChanged ||
                reachedKnownTotal ||
                crossedPublicationThreshold
            ) {
                lastEntryName = progress.currentEntryName
                lastCompletedEntries = progress.completedEntries
                lastPublishedBytes = progress.processedBytes
                updateOperationProgress(
                    TransferProgress(
                        label = label,
                        completedItems = progress.completedEntries,
                        currentItemName = progress.currentEntryName?.substringAfterLast('/'),
                        copiedBytes = progress.processedBytes,
                        totalBytes = progress.totalBytes,
                    )
                )
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
                        error = OperationMessages.reason(error),
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
        successMessage: (Int) -> UiText,
        @StringRes failureAction: Int,
    ) {
        val succeeded = entries.size - failures.size
        when {
            failures.isEmpty() -> showSnackbar(successMessage(succeeded))
            succeeded > 0 -> showSnackbar(
                UiText.Resource(
                    R.string.partial_trash_success,
                    listOf(succeeded, failures.size, UiText.Resource(failureAction)),
                ),
            )
            else -> showSnackbar(OperationMessages.failure(failureAction, failures.first()))
        }
    }

    private fun validFileNameOrNotify(name: String): String? =
        when (val result = FileNameValidator.validate(name)) {
            is FileNameValidationResult.Valid -> result.name
            is FileNameValidationResult.Invalid -> {
                showSnackbar(UiText.Resource(result.messageRes))
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

    fun setAutoCloseSessions(enabled: Boolean) {
        if (!enabled) deferredAutoCloseSessionIds = emptySet()
        viewModelScope.launch { prefs.setAutoCloseSessions(enabled) }
    }

    fun setSessionAutoCloseTimeout(timeout: SessionAutoCloseTimeout) {
        viewModelScope.launch { prefs.setSessionAutoCloseTimeout(timeout) }
    }

    fun setHomeSectionVisible(section: HomeSection, visible: Boolean) {
        viewModelScope.launch { prefs.setHomeSectionVisible(section, visible) }
    }

    fun moveHomeSection(section: HomeSection, offset: Int) {
        viewModelScope.launch { prefs.moveHomeSection(section, offset) }
    }

    fun resetHomeLayout() {
        viewModelScope.launch { prefs.setHomeLayout(HomeLayout.DEFAULT) }
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

    private fun closeSessionsAfterInactivity(sessionIds: Set<String>) {
        val closedSessions = _sessions.value.filter { it.id in sessionIds }
        if (closedSessions.isEmpty()) return
        val closedProviders = closedSessions.mapNotNull { session ->
            sessionProviders.remove(session.id)
        }.toSet()
        val remainingSessions = _sessions.value.filterNot { it.id in sessionIds }
        val activeSessionWasClosed = _activeSession.value?.id?.let { it in sessionIds } == true
        _sessions.value = remainingSessions
        clearClipboard()
        _snackbarMessage.value = null

        if (activeSessionWasClosed) {
            val nextSession = remainingSessions.lastOrNull()
            if (nextSession == null) {
                loadGuard.nextRequest(null)
                fileProvider = FileProviderFactory.createLocal()
                browserSessionRootPath = null
                _activeSession.value = null
                _browseState.update {
                    it.copy(
                        currentPath = "/",
                        files = emptyList(),
                        isLoading = false,
                        error = null,
                        selectedFiles = emptySet(),
                        source = FileSource.LOCAL,
                        searchQuery = "",
                        fileTypeFilter = FileTypeFilter.ALL,
                    )
                }
            } else {
                val nextProvider = checkNotNull(sessionProviders[nextSession.id])
                fileProvider = nextProvider
                browserSessionRootPath = nextSession.rootPath
                _activeSession.value = nextSession
                _browseState.update {
                    it.copy(
                        currentPath = nextSession.currentPath,
                        source = nextSession.source,
                        selectedFiles = emptySet(),
                        error = null,
                        searchQuery = "",
                        fileTypeFilter = FileTypeFilter.ALL,
                    )
                }
                viewModelScope.launch {
                    loadFiles(nextSession.currentPath, nextSession.id, nextProvider)
                }
            }
        } else {
            refreshActiveSessionSnapshot()
        }
        _sessionClosureGeneration.value += 1L
        viewModelScope.launch {
            closedProviders.forEach { provider -> runCatching { provider.disconnect() } }
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
