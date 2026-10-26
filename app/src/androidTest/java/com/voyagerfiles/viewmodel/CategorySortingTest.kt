package com.voyagerfiles.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.index.CategoryScanStatus
import com.voyagerfiles.data.index.StorageCategory
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.CategorySort
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CategorySortingTest {
    @Test fun sortingPersistsWithoutChangingBrowserPreferencesOrRestartingScan() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = PreferencesManager(application)
        val previous = preferences.categorySort.first()
        val browserBy = preferences.sortBy.first()
        val browserOrder = preferences.sortOrder.first()
        val store = ViewModelStore()
        val folder = File(Environment.getExternalStorageDirectory(), "VoyagerSortingTest-${System.nanoTime()}")
        assertTrue("Full storage access must be enabled for this device test", Environment.isExternalStorageManager())
        assertTrue(folder.mkdirs())
        try {
            for ((name, time) in listOf("alpha.pdf" to 10000L, "beta.pdf" to 30000L, "gamma.pdf" to 20000L)) {
                folder.resolve(name).apply { writeText("fixture $name"); assertTrue(setLastModified(time)) }
            }
            preferences.setCategorySortBy(SortBy.NAME)
            preferences.setCategorySortOrder(SortOrder.ASCENDING)
            val model = withContext(Dispatchers.Main) {
                StorageCategoryViewModel(application).also {
                    store.put("category", it)
                    it.refresh(StorageCategory.DOCUMENTS, false, true)
                }
            }
            val complete = withTimeout(30_000) {
                model.state.first { state ->
                    state.status != CategoryScanStatus.SCANNING &&
                        state.files.count { it.path.startsWith(folder.path + "/") } == 3
                }
            }
            assertEquals(listOf("alpha.pdf", "beta.pdf", "gamma.pdf"),
                complete.files.filter { it.path.startsWith(folder.path + "/") }.map { it.name })
            assertTrue(folder.resolve("gamma.pdf").delete())
            withContext(Dispatchers.Main) {
                model.setSortBy(SortBy.DATE)
                model.setSortOrder(SortOrder.DESCENDING)
            }
            val reordered = withTimeout(10_000) {
                model.state.first { state ->
                    state.files.filter { it.path.startsWith(folder.path + "/") }.map { it.name } ==
                        listOf("beta.pdf", "gamma.pdf", "alpha.pdf")
                }
            }
            assertEquals(complete.coverage, reordered.coverage)
            assertEquals(complete.status, reordered.status)
            assertEquals(browserBy, preferences.sortBy.first())
            assertEquals(browserOrder, preferences.sortOrder.first())

            val recreated = withContext(Dispatchers.Main) {
                store.clear()
                StorageCategoryViewModel(application).also { store.put("recreated", it) }
            }
            withTimeout(10_000) { recreated.sort.first { it == CategorySort(SortBy.DATE, SortOrder.DESCENDING) } }
            withContext(Dispatchers.Main) { recreated.refresh(StorageCategory.DOCUMENTS, false, true) }
            val refreshed = withTimeout(30_000) {
                recreated.state.first { state ->
                    state.status != CategoryScanStatus.SCANNING &&
                        state.files.filter { it.path.startsWith(folder.path + "/") }.map { it.name } ==
                            listOf("beta.pdf", "alpha.pdf")
                }
            }
            assertTrue(refreshed.files.none { it.path == folder.resolve("gamma.pdf").path })
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            preferences.setCategorySortBy(previous.by)
            preferences.setCategorySortOrder(previous.order)
            assertTrue(folder.deleteRecursively())
        }
    }
}
