package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowserSelectionActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var root: File

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        root = File(application.cacheDir, "browser-selection-actions-test").apply {
            deleteRecursively()
            mkdirs()
            resolve("notes.txt").writeText("notes")
            resolve("Folder").mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun oneLocalFilePromotesShareRenameAndDelete() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithContentDescription("Share").assertExists()
        composeTestRule.onNodeWithContentDescription("Rename").assertExists()
        composeTestRule.onNodeWithContentDescription("Delete").assertExists()
    }

    private fun launchBrowser(): FileBrowserViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {
            MaterialTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                )
            }
        }
        composeTestRule.runOnIdle {
            viewModel.openLocalRoot(root.absolutePath)
        }
        return viewModel
    }

    private fun waitForRoot(viewModel: FileBrowserViewModel) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.currentPath == root.absolutePath &&
                !viewModel.browseState.value.isLoading
        }
        composeTestRule.onNodeWithText("notes.txt").assertExists()
    }
}
