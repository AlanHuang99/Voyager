package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileRowOperationsTest {
    private val viewModelStore = ViewModelStore()
    private lateinit var root: File

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        root = File(application.cacheDir, "file-row-operations-test").apply {
            deleteRecursively()
            mkdirs()
            resolve("target.txt").writeText("target")
            resolve("selected.txt").writeText("selected")
        }
    }

    @After
    fun tearDown() {
        runBlocking { withContext(Dispatchers.Main) { viewModelStore.clear() } }
        root.deleteRecursively()
    }

    @Test
    fun copyByPathPreservesAnotherSelection() = runBlocking {
        val viewModel = openRoot()
        withContext(Dispatchers.Main) {
            viewModel.toggleSelection(root.resolve("selected.txt").absolutePath)
            viewModel.copyToClipboard(listOf(root.resolve("target.txt").absolutePath), clearSelectionAfter = false)
        }

        assertEquals(setOf(root.resolve("selected.txt").absolutePath), viewModel.browseState.value.selectedFiles)
        assertEquals(listOf(root.resolve("target.txt").absolutePath), viewModel.clipboardPaths.value)
        assertEquals(ClipboardOperation.COPY, viewModel.clipboardOperation.value)
    }

    @Test
    fun cutByPathPreservesAnotherSelection() = runBlocking {
        val viewModel = openRoot()
        withContext(Dispatchers.Main) {
            viewModel.toggleSelection(root.resolve("selected.txt").absolutePath)
            viewModel.cutToClipboard(listOf(root.resolve("target.txt").absolutePath), clearSelectionAfter = false)
        }

        assertEquals(setOf(root.resolve("selected.txt").absolutePath), viewModel.browseState.value.selectedFiles)
        assertEquals(listOf(root.resolve("target.txt").absolutePath), viewModel.clipboardPaths.value)
        assertEquals(ClipboardOperation.CUT, viewModel.clipboardOperation.value)
        assertEquals("target", root.resolve("target.txt").readText())
    }

    @Test
    fun selectionCopyStillClearsTheSelection() = runBlocking {
        val viewModel = openRoot()
        val selectedPath = root.resolve("selected.txt").absolutePath
        withContext(Dispatchers.Main) {
            viewModel.toggleSelection(selectedPath)
            viewModel.copyToClipboard(listOf(selectedPath))
        }

        assertEquals(emptySet<String>(), viewModel.browseState.value.selectedFiles)
        assertEquals(listOf(selectedPath), viewModel.clipboardPaths.value)
    }

    @Test
    fun selectionCutStillClearsTheSelection() = runBlocking {
        val viewModel = openRoot()
        val selectedPath = root.resolve("selected.txt").absolutePath
        withContext(Dispatchers.Main) {
            viewModel.toggleSelection(selectedPath)
            viewModel.cutToClipboard(listOf(selectedPath))
        }

        assertEquals(emptySet<String>(), viewModel.browseState.value.selectedFiles)
        assertEquals(listOf(selectedPath), viewModel.clipboardPaths.value)
        assertEquals(ClipboardOperation.CUT, viewModel.clipboardOperation.value)
    }

    private suspend fun openRoot(): FileBrowserViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = withContext(Dispatchers.Main) {
            FileBrowserViewModel(application).also {
                viewModelStore.put("browser", it)
                it.openLocalRoot(root.absolutePath)
            }
        }
        withTimeout(10_000) {
            while (viewModel.browseState.value.currentPath != root.absolutePath ||
                viewModel.browseState.value.isLoading || viewModel.browseState.value.files.size != 2
            ) {
                delay(25)
            }
        }
        return viewModel
    }
}
