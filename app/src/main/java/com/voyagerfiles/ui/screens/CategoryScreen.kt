package com.voyagerfiles.ui.screens

import android.content.ActivityNotFoundException
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voyagerfiles.R
import com.voyagerfiles.data.index.CategoryIndexState
import com.voyagerfiles.data.index.CategoryScanStatus
import com.voyagerfiles.data.index.StorageCategory
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.ui.components.FileListItem
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.viewmodel.StorageCategoryViewModel
import kotlinx.coroutines.launch

@Composable
fun CategoryScreen(
    category: StorageCategory,
    showHidden: Boolean,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onNavigateBack: () -> Unit,
    categoryViewModel: StorageCategoryViewModel = viewModel(),
) {
    val state by categoryViewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val refresh = { categoryViewModel.refresh(category, showHidden, hasAllFilesAccess) }
    DisposableEffect(lifecycleOwner, category, showHidden, hasAllFilesAccess) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> refresh()
                Lifecycle.Event.ON_STOP -> categoryViewModel.cancel()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !hasAllFilesAccess) refresh()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            categoryViewModel.cancel()
        }
    }
    CategoryContent(
        category = category,
        state = state,
        showHidden = showHidden,
        snackbar = snackbar,
        onRefresh = refresh,
        onCancel = categoryViewModel::cancel,
        onRequestAllFilesAccess = onRequestAllFilesAccess,
        onNavigateBack = onNavigateBack,
        onOpenFile = { file ->
            scope.launch {
                if (!categoryViewModel.validate(file)) {
                    snackbar.showSnackbar(context.getString(R.string.category_stale_file))
                } else {
                    FileUtils.openFile(context, file).onFailure { error ->
                        snackbar.showSnackbar(context.getString(
                            if (error is ActivityNotFoundException) R.string.browser_no_file_handler else R.string.browser_file_open_failed,
                        ))
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryContent(
    category: StorageCategory,
    state: CategoryIndexState,
    showHidden: Boolean,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(category.filter.labelRes)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back)) }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.content_desc_refresh)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item(key = "coverage") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.category_scope), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (showHidden) R.string.category_hidden_included else R.string.category_hidden_excluded), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(state.status.labelRes), style = MaterialTheme.typography.titleSmall)
                    if (state.status == CategoryScanStatus.DENIED) {
                        TextButton(onClick = onRequestAllFilesAccess) { Text(stringResource(R.string.permission_grant_full_access)) }
                    } else {
                        Text(stringResource(R.string.category_results, state.files.size, state.staleEntries))
                    }
                    if (state.status == CategoryScanStatus.SCANNING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    }
                    for (coverage in state.coverage) {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(coverage.volume.description, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Text(stringResource(coverage.status.labelRes), style = MaterialTheme.typography.labelMedium)
                            }
                            coverage.volume.path?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (coverage.status != CategoryScanStatus.PENDING && coverage.status != CategoryScanStatus.UNAVAILABLE) {
                                Text(stringResource(R.string.category_coverage_counts, coverage.filesExamined, coverage.inaccessibleEntries, coverage.skippedEntries), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(stringResource(R.string.category_skipped_explanation), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.files, key = { it.path }) { file ->
                Column {
                    FileListItem(file, false, false, onClick = { onOpenFile(file) }, onLongClick = { onOpenFile(file) })
                    Text(file.path, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
