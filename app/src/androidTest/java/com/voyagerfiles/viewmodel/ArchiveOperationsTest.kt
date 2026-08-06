package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveOperationsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        root = File(application.cacheDir, "archive-operations-test").apply {
            deleteRecursively()
            mkdirs()
            resolve("notes.txt").writeText("on-device archive content")
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun createsAndExtractsZipThroughViewModel() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = withContext(Dispatchers.Main) {
            FileBrowserViewModel(application).also { it.openLocalRoot(root.absolutePath) }
        }
        waitUntil("open test root", viewModel) {
            viewModel.browseState.value.currentPath == root.absolutePath &&
                !viewModel.browseState.value.isLoading
        }
        delay(250)
        assertEquals(root.absolutePath, viewModel.browseState.value.currentPath)

        withContext(Dispatchers.Main) {
            viewModel.toggleSelection(root.resolve("notes.txt").absolutePath)
            viewModel.createZipFromSelection("bundle.zip")
        }
        waitUntil("create bundle.zip", viewModel) {
            viewModel.operationState.value == OperationState.Idle &&
                root.resolve("bundle.zip").isFile &&
                viewModel.browseState.value.files.any { it.name == "bundle.zip" } &&
                !viewModel.browseState.value.isLoading
        }
        assertTrue(root.resolve("bundle.zip").length() > 0)

        withContext(Dispatchers.Main) {
            viewModel.toggleSelection(root.resolve("bundle.zip").absolutePath)
            viewModel.extractSelectedArchive()
        }
        waitUntil("extract bundle.zip", viewModel) {
            viewModel.operationState.value == OperationState.Idle &&
                root.resolve("bundle_extracted/notes.txt").isFile
        }

        assertEquals(
            "on-device archive content",
            root.resolve("bundle_extracted/notes.txt").readText(),
        )
    }

    @Test
    fun extractsArchiveByPathWithoutSelection() = runBlocking {
        val archive = root.resolve("bundle.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("inside.txt"))
            zip.write("direct extraction content".toByteArray())
            zip.closeEntry()
        }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = withContext(Dispatchers.Main) {
            FileBrowserViewModel(application).also { it.openLocalRoot(root.absolutePath) }
        }
        waitUntil("open test root", viewModel) {
            viewModel.browseState.value.currentPath == root.absolutePath &&
                viewModel.browseState.value.files.any { it.path == archive.absolutePath } &&
                !viewModel.browseState.value.isLoading
        }

        withContext(Dispatchers.Main) {
            viewModel.extractArchive(archive.absolutePath)
        }
        waitUntil("extract bundle.zip directly", viewModel) {
            viewModel.operationState.value == OperationState.Idle &&
                root.resolve("bundle_extracted/inside.txt").isFile
        }

        assertEquals(
            "direct extraction content",
            root.resolve("bundle_extracted/inside.txt").readText(),
        )
        assertTrue(viewModel.browseState.value.selectedFiles.isEmpty())
    }

    private suspend fun waitUntil(
        description: String,
        viewModel: FileBrowserViewModel,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(10_000) {
                while (!condition()) {
                    delay(25)
                }
            }
        } catch (error: Throwable) {
            val state = viewModel.browseState.value
            throw AssertionError(
                "Timed out waiting to $description; path=${state.currentPath}; " +
                    "loading=${state.isLoading}; files=${state.files.map { it.name }}; " +
                    "selection=${state.selectedFiles}; operation=${viewModel.operationState.value}; " +
                    "message=${viewModel.snackbarMessage.value}; disk=${root.list()?.toList()}",
                error,
            )
        }
    }
}
