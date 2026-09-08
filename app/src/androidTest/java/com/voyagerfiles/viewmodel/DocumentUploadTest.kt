package com.voyagerfiles.viewmodel

import android.app.Application
import com.voyagerfiles.ui.text.resolve
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DocumentUploadTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fixtureRoot: File

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        fixtureRoot = File(application.cacheDir, "document-upload-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        fixtureRoot.deleteRecursively()
    }

    @Test
    fun contentUriUploadsIntoTheActiveDirectory() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val source = fixtureRoot.resolve("report.txt").apply { writeText("document upload") }
        val destination = fixtureRoot.resolve("destination").apply { mkdirs() }
        val sourceUri = FileProvider.getUriForFile(
            application,
            "${application.packageName}.fileprovider",
            source,
        )
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {}
        composeTestRule.runOnIdle { viewModel.openLocalRoot(destination.absolutePath) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.currentPath == destination.absolutePath &&
                !viewModel.browseState.value.isLoading
        }

        composeTestRule.runOnIdle { viewModel.uploadDocuments(listOf(sourceUri)) }

        val uploaded = destination.resolve(source.name)
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.operationState.value == OperationState.Idle && uploaded.exists()
        }
        assertEquals(source.readText(), uploaded.readText())
    }

    @Test
    fun conflictingDocumentWaitsForSkipThenContinuesWithTruthfulCounts() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val conflictingSource = fixtureRoot.resolve("report.txt").apply { writeText("replacement") }
        val newSource = fixtureRoot.resolve("notes.txt").apply { writeText("new upload") }
        val destination = fixtureRoot.resolve("destination").apply {
            mkdirs()
            resolve("report.txt").writeText("existing")
        }
        val authority = "${application.packageName}.fileprovider"
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {}
        composeTestRule.runOnIdle { viewModel.openLocalRoot(destination.absolutePath) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.currentPath == destination.absolutePath &&
                !viewModel.browseState.value.isLoading
        }

        composeTestRule.runOnIdle {
            viewModel.uploadDocuments(
                listOf(
                    FileProvider.getUriForFile(application, authority, conflictingSource),
                    FileProvider.getUriForFile(application, authority, newSource),
                )
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.transferConflict.value != null }
        assertEquals("existing", destination.resolve("report.txt").readText())
        org.junit.Assert.assertFalse(destination.resolve("notes.txt").exists())
        composeTestRule.runOnIdle {
            viewModel.resolveTransferConflict(viewModel.transferConflict.value!!, ConflictResponse(ConflictDecision.SKIP))
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.operationState.value == OperationState.Idle &&
                destination.resolve("notes.txt").exists()
        }
        assertEquals("existing", destination.resolve("report.txt").readText())
        assertEquals("new upload", destination.resolve("notes.txt").readText())
        assertEquals("1 uploaded, 1 skipped", viewModel.snackbarMessage.value!!.resolve(application.resources))
        assertEquals(1, viewModel.lastOperationResult.value!!.progress.completedItems)
        assertEquals(1, viewModel.lastOperationResult.value!!.progress.skippedItems)
        assertEquals(OperationOutcome.COMPLETED, viewModel.lastOperationResult.value!!.outcome)
    }
}
