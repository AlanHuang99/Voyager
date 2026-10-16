package com.voyagerfiles.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voyagerfiles.R
import com.voyagerfiles.data.index.CategoryScanStatus
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.ui.components.FileListItem
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.viewmodel.StorageSearchState
import com.voyagerfiles.viewmodel.StorageSearchViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun StorageSearchScreen(
    showHidden: Boolean,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    searchViewModel: StorageSearchViewModel = viewModel(),
) {
    val state by searchViewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val refresh = { searchViewModel.refresh(showHidden, hasAllFilesAccess) }
    DisposableEffect(lifecycleOwner, showHidden, hasAllFilesAccess) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> refresh()
                Lifecycle.Event.ON_STOP -> searchViewModel.cancel()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !hasAllFilesAccess) refresh()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            searchViewModel.cancel()
        }
    }
    fun open(file: FileItem, containingFolder: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            if (!searchViewModel.validate(file)) {
                snackbar.showSnackbar(context.getString(R.string.search_stale_file))
            } else if (containingFolder) {
                File(file.path).parent?.let(onOpenFolder)
            } else {
                FileUtils.openFile(context, file).onFailure { error ->
                    snackbar.showSnackbar(context.getString(
                        if (error is ActivityNotFoundException) R.string.browser_no_file_handler else R.string.browser_file_open_failed,
                    ))
                }
            }
        }
    }
    StorageSearchContent(
        state = state,
        snackbar = snackbar,
        onQueryChange = searchViewModel::setQuery,
        onSubmit = {
            keyboard?.hide()
            focus.clearFocus()
            searchViewModel.submit(showHidden, hasAllFilesAccess)
        },
        onRefresh = refresh,
        onCancel = searchViewModel::cancel,
        onRequestAllFilesAccess = onRequestAllFilesAccess,
        onNavigateBack = onNavigateBack,
        onOpenFile = { open(it, containingFolder = false) },
        onOpenLocation = { open(it, containingFolder = true) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageSearchContent(
    state: StorageSearchState,
    snackbar: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
) {
    val result = state.result
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = state.submittedQuery.isNotBlank()) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.content_desc_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f).testTag("storage-search-query"),
                        label = { Text(stringResource(R.string.search_file_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (state.query.isNotBlank()) onSubmit() }),
                    )
                    IconButton(onClick = onSubmit, enabled = state.query.isNotBlank()) {
                        Icon(Icons.Filled.Search, stringResource(R.string.action_search))
                    }
                }
                Text(stringResource(R.string.search_storage_scope), style = MaterialTheme.typography.bodySmall)
                if (result.status == CategoryScanStatus.DENIED) {
                    Text(stringResource(R.string.search_permission_required))
                    TextButton(onClick = onRequestAllFilesAccess) { Text(stringResource(R.string.permission_grant_full_access)) }
                } else if (state.submittedQuery.isBlank()) {
                    Text(stringResource(R.string.search_ready))
                } else {
                    Text(stringResource(R.string.search_results_for, state.submittedQuery), style = MaterialTheme.typography.titleSmall)
                    Text(pluralStringResource(R.plurals.search_result_count, result.files.size, result.files.size))
                    Text(stringResource(result.status.labelRes), style = MaterialTheme.typography.bodySmall)
                    if (result.status == CategoryScanStatus.SCANNING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    } else if (result.status == CategoryScanStatus.COMPLETE && result.files.isEmpty()) {
                        Text(stringResource(R.string.search_no_matches))
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).testTag("storage-search-results")) {
                if (result.coverage.isNotEmpty()) {
                    item(key = "coverage") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            result.coverage.forEach { coverage ->
                                Column {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(coverage.volume.description, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                        Text(stringResource(coverage.status.labelRes), style = MaterialTheme.typography.labelMedium)
                                    }
                                    if (coverage.status != CategoryScanStatus.PENDING && coverage.status != CategoryScanStatus.UNAVAILABLE) {
                                        Text(stringResource(R.string.category_coverage_counts, coverage.filesExamined, coverage.inaccessibleEntries, coverage.skippedEntries),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                items(result.files, key = { it.path }) { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            FileListItem(file, false, false, onClick = { onOpenFile(file) }, onLongClick = { onOpenLocation(file) })
                            Text(file.path, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onOpenLocation(file) }) {
                            Icon(Icons.Filled.FolderOpen, stringResource(R.string.content_desc_open_location_named, file.path))
                        }
                    }
                }
            }
        }
    }
}
