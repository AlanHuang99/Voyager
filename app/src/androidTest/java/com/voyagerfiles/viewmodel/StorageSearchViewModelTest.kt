package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.index.CategoryScanStatus
import com.voyagerfiles.util.StorageVolumeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StorageSearchViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(application.cacheDir, "storage-search-viewmodel").apply {
            deleteRecursively()
            mkdirs()
            resolve("alpha.txt").writeText("alpha")
            resolve("beta.bin").writeText("beta")
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { store.clear() }
        root.deleteRecursively()
        Unit
    }

    @Test
    fun editingAndBlankSubmissionDoNotReadStorage() = runBlocking {
        val reads = AtomicInteger()
        val viewModel = model(volumeProvider = { reads.incrementAndGet(); volumes() })
        withContext(Dispatchers.Main) {
            viewModel.setQuery("   ")
            viewModel.submit(false, true)
            viewModel.setQuery("alpha")
        }
        assertEquals(0, reads.get())
        assertTrue(viewModel.state.value.submittedQuery.isEmpty())
        withContext(Dispatchers.Main) { viewModel.submit(false, true) }
        await(viewModel) { it.result.status == CategoryScanStatus.COMPLETE }
        assertEquals(listOf("alpha.txt"), viewModel.state.value.result.files.map { it.name })
    }

    @Test
    fun replacingAQueryRejectsAnOlderBlockedVolumeRead() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val reads = AtomicInteger()
        val viewModel = model(volumeProvider = {
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                returned.countDown()
            }
            volumes()
        })
        try {
            withContext(Dispatchers.Main) {
                viewModel.setQuery("alpha")
                viewModel.submit(false, true)
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) {
                viewModel.setQuery("beta")
                viewModel.submit(false, true)
            }
            await(viewModel) { it.submittedQuery == "beta" && it.result.status == CategoryScanStatus.COMPLETE }
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { returned.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) {
                assertEquals("beta", viewModel.state.value.submittedQuery)
                assertEquals(listOf("beta.bin"), viewModel.state.value.result.files.map { it.name })
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun cancellationWhileLoadingVolumesKeepsTheCancelledState() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val viewModel = model(volumeProvider = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            volumes()
        })
        try {
            withContext(Dispatchers.Main) {
                viewModel.setQuery("alpha")
                viewModel.submit(false, true)
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) { viewModel.cancel() }
            assertEquals(CategoryScanStatus.CANCELLED, viewModel.state.value.result.status)
            assertTrue(viewModel.state.value.result.files.isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun changedFilesAreRemovedAndPermissionLossClearsResults() = runBlocking {
        var granted = true
        val viewModel = model(permission = { granted })
        withContext(Dispatchers.Main) {
            viewModel.setQuery("alpha")
            viewModel.submit(false, true)
        }
        await(viewModel) { it.result.status == CategoryScanStatus.COMPLETE }
        val file = viewModel.state.value.result.files.single()
        assertTrue(withContext(Dispatchers.Main) { viewModel.validate(file) })
        File(file.path).appendText("changed")
        assertFalse(withContext(Dispatchers.Main) { viewModel.validate(file) })
        assertTrue(viewModel.state.value.result.files.isEmpty())
        assertEquals(1, viewModel.state.value.result.staleEntries)
        assertEquals(CategoryScanStatus.PARTIAL, viewModel.state.value.result.status)
        granted = false
        withContext(Dispatchers.Main) { viewModel.refresh(false, false) }
        assertEquals(CategoryScanStatus.DENIED, viewModel.state.value.result.status)
        assertTrue(viewModel.state.value.result.files.isEmpty())
    }

    private suspend fun model(
        volumeProvider: () -> List<StorageVolumeInfo> = ::volumes,
        permission: () -> Boolean = { true },
    ): StorageSearchViewModel = withContext(Dispatchers.Main) {
        StorageSearchViewModel(application, volumeProvider = volumeProvider, permissionGranted = permission).also {
            store.put("search", it)
        }
    }

    private fun volumes() = listOf(StorageVolumeInfo("Test storage", root.path, true, false, "mounted"))

    private suspend fun await(viewModel: StorageSearchViewModel, condition: (StorageSearchState) -> Boolean) {
        withTimeout(10_000) { while (!condition(viewModel.state.value)) delay(25) }
    }
}
