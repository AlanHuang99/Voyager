package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voyagerfiles.app.VoyagerApp
import com.voyagerfiles.R
import com.voyagerfiles.data.duplicates.DuplicateScan
import com.voyagerfiles.data.duplicates.DuplicateScanProgress
import com.voyagerfiles.data.duplicates.DuplicateScanner
import com.voyagerfiles.data.duplicates.removeVerifiedDuplicates
import com.voyagerfiles.data.repository.LocalTrashManager
import com.voyagerfiles.ui.text.UiText
import com.voyagerfiles.util.FileUtils
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DuplicateState(
    val scanning: Boolean = false,
    val removing: Boolean = false,
    val progress: DuplicateScanProgress = DuplicateScanProgress(0),
    val result: DuplicateScan? = null,
    val selected: Set<String> = emptySet(),
    val message: UiText? = null,
)

class DuplicateViewModel @JvmOverloads constructor(
    application: Application,
    private val operations: TransferOperationController = (application as VoyagerApp).transfers,
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(DuplicateState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val scanner = DuplicateScanner()

    fun scan(path: String) {
        if (job?.isActive == true) return
        mutableState.value = DuplicateState(scanning = true)
        job = viewModelScope.launch {
            try {
                var lastUpdate = 0L
                val result = scanner.scan(File(path)) { progress ->
                    val now = System.nanoTime()
                    if (now - lastUpdate > 100_000_000L) {
                        lastUpdate = now
                        mutableState.update { it.copy(progress = progress) }
                    }
                }
                mutableState.update { it.copy(scanning = false, result = result) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(scanning = false, message = UiText.Resource(R.string.duplicates_cancelled)) }
            } catch (error: Exception) {
                mutableState.update { it.copy(scanning = false, message = UiText.Dynamic(error.message ?: "Scan failed.")) }
            }
        }
    }

    fun cancelScan() { if (state.value.scanning) job?.cancel() }

    fun toggle(path: String) {
        mutableState.update { state ->
            if (state.scanning || state.removing) return@update state
            val group = state.result?.groups?.firstOrNull { group -> group.files.any { it.path == path } } ?: return@update state
            val selected = if (path in state.selected) state.selected - path else state.selected + path
            if (group.files.all { it.path in selected }) state.copy(message = UiText.Resource(R.string.duplicates_keep_one))
            else state.copy(selected = selected, message = null)
        }
    }

    fun remove(useTrash: Boolean) {
        val snapshot = state.value
        val scan = snapshot.result ?: return
        if (snapshot.selected.isEmpty() || snapshot.scanning || snapshot.removing) return
        mutableState.update { it.copy(removing = true, message = null) }
        val label = UiText.Resource(R.string.duplicates_removing)
        val started = operations.launch(label, cancellable = false,
            onFailure = { error -> mutableState.update { it.copy(removing = false, message = UiText.Dynamic(error.message ?: "Removal failed.")) } },
            onFinished = { mutableState.update { it.copy(removing = false) } },
        ) {
            operations.update(TransferProgress(label, totalItems = snapshot.selected.size))
            var completed = 0
            try {
                val roots = FileUtils.getStorageVolumes(getApplication()).mapNotNull { it.path?.let(::File) }
                val trash = LocalTrashManager(roots)
                val result = withContext(Dispatchers.IO) {
                    removeVerifiedDuplicates(scan.groups, snapshot.selected, scanner) { path ->
                        if (useTrash) trash.moveToTrash(path).getOrThrow() else Files.delete(File(path).toPath())
                        completed++
                        operations.update(TransferProgress(label, completedItems = completed, totalItems = snapshot.selected.size))
                    }
                }
                if (result.failures.isNotEmpty()) operations.recordFailure(IllegalStateException(result.failures.values.first()))
                val remaining = scan.groups.map { group -> group.copy(files = group.files.filter { it.path !in result.removed }) }
                    .filter { it.files.size > 1 }
                mutableState.update { it.copy(removing = false, selected = emptySet(), result = scan.copy(groups = remaining),
                    message = UiText.Resource(R.string.duplicates_removed, listOf(result.removed.size, result.failures.size,
                        result.failures.entries.firstOrNull()?.let { failure -> "${failure.key}: ${failure.value}" }.orEmpty()))) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(removing = false) }
                throw cancelled
            } catch (error: Exception) {
                operations.recordFailure(error)
                mutableState.update { it.copy(removing = false, message = UiText.Dynamic(error.message ?: "Removal failed.")) }
            }
        }
        if (!started) mutableState.update { it.copy(removing = false, message = UiText.Resource(R.string.duplicates_operation_busy)) }
    }
}
