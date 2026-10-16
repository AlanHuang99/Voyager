package com.voyagerfiles.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voyagerfiles.data.index.CategoryIndexState
import com.voyagerfiles.data.index.CategoryScanStatus
import com.voyagerfiles.data.index.StorageCategoryIndex
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.util.StorageVolumeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageSearchState(
    val query: String = "",
    val submittedQuery: String = "",
    val result: CategoryIndexState = CategoryIndexState(),
)

class StorageSearchViewModel @JvmOverloads constructor(
    application: Application,
    private val index: StorageCategoryIndex = StorageCategoryIndex(),
    private val volumeProvider: () -> List<StorageVolumeInfo> = { FileUtils.getStorageVolumes(application) },
    private val permissionGranted: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(application, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    },
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StorageSearchState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var generation = 0L

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun submit(showHidden: Boolean, accessGranted: Boolean) {
        start(_state.value.query.trim(), showHidden, accessGranted)
    }

    fun refresh(showHidden: Boolean, accessGranted: Boolean) {
        start(_state.value.submittedQuery, showHidden, accessGranted)
    }

    private fun start(query: String, showHidden: Boolean, accessGranted: Boolean) {
        val request = ++generation
        scanJob?.cancel()
        if (!accessGranted || !permissionGranted()) {
            _state.update { it.copy(submittedQuery = query, result = CategoryIndexState(status = CategoryScanStatus.DENIED)) }
            return
        }
        if (query.isBlank()) {
            _state.update { it.copy(submittedQuery = "", result = CategoryIndexState()) }
            return
        }
        _state.update { it.copy(submittedQuery = query, result = CategoryIndexState(status = CategoryScanStatus.SCANNING)) }
        scanJob = viewModelScope.launch {
            try {
                val volumes = withContext(Dispatchers.IO) { volumeProvider() }
                index.search(query, volumes, showHidden, permissionGranted).collect { result ->
                    if (generation == request) _state.update { it.copy(result = result) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == request) {
                    _state.update {
                        it.copy(result = if (permissionGranted()) it.result.copy(status = CategoryScanStatus.FAILED)
                            else CategoryIndexState(status = CategoryScanStatus.DENIED))
                    }
                }
            }
        }
    }

    fun cancel() {
        generation++
        scanJob?.cancel()
        _state.update { state ->
            if (state.result.status != CategoryScanStatus.SCANNING) state
            else state.copy(result = state.result.copy(
                status = CategoryScanStatus.CANCELLED,
                coverage = state.result.coverage.map {
                    if (it.status == CategoryScanStatus.SCANNING || it.status == CategoryScanStatus.PENDING) {
                        it.copy(status = CategoryScanStatus.CANCELLED)
                    } else it
                },
            ))
        }
    }

    suspend fun validate(file: FileItem): Boolean {
        if (!permissionGranted()) {
            cancel()
            _state.update { it.copy(result = CategoryIndexState(status = CategoryScanStatus.DENIED)) }
            return false
        }
        val request = generation
        if (_state.value.result.files.none { it.path == file.path }) return false
        val current = withContext(Dispatchers.IO) {
            runCatching { index.isCurrent(file, volumeProvider(), permissionGranted) }.getOrDefault(false)
        }
        if (request != generation) return false
        if (!current) {
            cancel()
            _state.update { state ->
                state.copy(result = if (!permissionGranted()) CategoryIndexState(status = CategoryScanStatus.DENIED)
                    else state.result.copy(
                        status = CategoryScanStatus.PARTIAL,
                        files = state.result.files.filterNot { it.path == file.path },
                        staleEntries = state.result.staleEntries + 1,
                    ))
            }
        }
        return current
    }
}
