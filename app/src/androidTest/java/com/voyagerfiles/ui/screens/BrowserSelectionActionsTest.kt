package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
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
        val application = ApplicationProvider.getApplicationContext<Application>()
        runBlocking { PreferencesManager(application).setViewMode(ViewMode.LIST) }
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

    @Test
    fun localDeleteOffersTrashAndPermanentChoices() {
        val viewModel = launchBrowser()
        composeTestRule.runOnIdle { viewModel.setUseTrash(true) }
        waitForRoot(viewModel)
        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.useTrash.value }

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("Delete").performClick()

        composeTestRule.onNodeWithText("Move to Trash").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete permanently").assertIsDisplayed()
        composeTestRule.onNodeWithText("Permanent deletion cannot be undone.", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun permanentChoiceDeletesTheSelectedLocalFile() {
        val viewModel = launchBrowser()
        composeTestRule.runOnIdle { viewModel.setUseTrash(true) }
        waitForRoot(viewModel)
        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.useTrash.value }

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("Delete").performClick()
        composeTestRule.onNodeWithText("Delete permanently").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            !root.resolve("notes.txt").exists()
        }
        composeTestRule.onNodeWithText("notes.txt").assertDoesNotExist()
    }

    @Test
    fun viewOptionsExposeAndPersistCompactList() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        composeTestRule.runOnIdle { viewModel.setViewMode(ViewMode.LIST) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.viewMode == ViewMode.LIST
        }

        composeTestRule.onNodeWithContentDescription("View options, current List").performClick()
        composeTestRule.onNodeWithText("List").assertIsDisplayed()
        composeTestRule.onNodeWithText("Compact list").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("View options, current Compact list")
            .assertIsDisplayed()

        val application = ApplicationProvider.getApplicationContext<Application>()
        val restoredViewModel = FileBrowserViewModel(application)
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            restoredViewModel.browseState.value.viewMode == ViewMode.COMPACT
        }
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
