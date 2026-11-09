package com.voyagerfiles.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.ui.theme.VoyagerTheme
import com.voyagerfiles.util.IncomingShare
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.OperationState
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IncomingShareNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeRequiresChoosingFolderAndCancelClearsShare() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val browser = FileBrowserViewModel(application)
        var files by mutableStateOf(listOf(Uri.parse("content://com.voyagerfiles.test.upload/document/zero")))
        compose.setContent {
            VoyagerTheme {
                AppNavigation(browser, true, {}, sharedFiles = files, onShareConsumed = { files = emptyList() })
            }
        }
        compose.onNodeWithText("Save here").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save here").assertDoesNotExist()
    }

    @Test fun sharedFilesSaveThroughExistingUploadPipeline() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val fixture = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "voyager-share-${UUID.randomUUID()}").apply { mkdirs() }
        val browser = FileBrowserViewModel(application)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(
                Uri.parse("content://com.voyagerfiles.test.upload/document/zero"),
                Uri.parse("content://com.voyagerfiles.test.upload/document/unknown"),
            ),
        )
        var files by mutableStateOf(IncomingShare.files(application, intent))
        try {
            compose.setContent {
                VoyagerTheme {
                    AppNavigation(browser, true, {}, requestedFolder = fixture.path, sharedFiles = files, onShareConsumed = { files = emptyList() })
                }
            }
            compose.waitUntil(10_000) { browser.browseState.value.currentPath == fixture.path && !browser.browseState.value.isLoading }
            compose.onNodeWithText("Save here").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { browser.operationState.value == OperationState.Idle && fixture.resolve("unknown.bin").exists() }
            assertEquals(0L, fixture.resolve("empty.txt").length())
            assertEquals("shared document", fixture.resolve("unknown.bin").readText())
            compose.onNodeWithText("Save here").assertDoesNotExist()
        } finally {
            fixture.deleteRecursively()
        }
    }
}
