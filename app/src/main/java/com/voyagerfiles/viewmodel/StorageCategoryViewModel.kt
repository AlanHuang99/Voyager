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
import com.voyagerfiles.data.index.StorageCategory
import com.voyagerfiles.data.index.StorageCategoryIndex
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageCategoryViewModel(application: Application) : AndroidViewModel(application) {
    private val index = StorageCategoryIndex()
    private val _state = MutableStateFlow(CategoryIndexState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null

    private fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun refresh(category: StorageCategory, showHidden: Boolean, accessGranted: Boolean) {
        scanJob?.cancel()
        if (!accessGranted || !hasPermission()) {
            _state.value = CategoryIndexState(status = CategoryScanStatus.DENIED)
            return
        }
        _state.value = CategoryIndexState(status = CategoryScanStatus.SCANNING)
        scanJob = viewModelScope.launch {
            try {
                val volumes = withContext(Dispatchers.IO) { FileUtils.getStorageVolumes(getApplication()) }
                index.scan(category, volumes, showHidden, ::hasPermission).collect { _state.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!hasPermission()) {
                    _state.value = CategoryIndexState(status = CategoryScanStatus.DENIED)
                } else {
                    _state.update { it.copy(status = CategoryScanStatus.FAILED) }
                }
            }
        }
    }

    fun cancel() {
        scanJob?.cancel()
        if (_state.value.status == CategoryScanStatus.SCANNING) {
            _state.update { state ->
                state.copy(
                    status = CategoryScanStatus.CANCELLED,
                    coverage = state.coverage.map {
                        if (it.status == CategoryScanStatus.SCANNING || it.status == CategoryScanStatus.PENDING) {
                            it.copy(status = CategoryScanStatus.CANCELLED)
                        } else it
                    },
                )
            }
        }
    }

    suspend fun validate(file: FileItem): Boolean {
        if (!hasPermission()) {
            scanJob?.cancel()
            _state.value = CategoryIndexState(status = CategoryScanStatus.DENIED)
            return false
        }
        val current = withContext(Dispatchers.IO) {
            runCatching { index.isCurrent(file, FileUtils.getStorageVolumes(getApplication()), ::hasPermission) }.getOrDefault(false)
        }
        if (!current) {
            // Stop the scan so a buffered snapshot cannot put a stale result back into the list.
            cancel()
            if (!hasPermission()) {
                _state.value = CategoryIndexState(status = CategoryScanStatus.DENIED)
            } else {
                _state.update { state ->
                    state.copy(files = state.files.filterNot { it.path == file.path }, staleEntries = state.staleEntries + 1)
                }
            }
        }
        return current
    }
}
