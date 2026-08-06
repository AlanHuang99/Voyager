package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            ZipOutputStream(FileOutputStream(resolve("archive.zip"))).use { zip ->
                zip.putNextEntry(ZipEntry("inside.txt"))
                zip.write("inside".toByteArray())
                zip.closeEntry()
            }
            resolve("legacy.rar").writeBytes(byteArrayOf())
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
    fun firstSelectionPerformsOneHapticInListAndGridModes() {
        val haptics = RecordingHapticFeedback()
        val viewModel = launchBrowser(haptics)
        waitForRoot(viewModel)
        composeTestRule.runOnIdle { viewModel.setViewMode(ViewMode.LIST) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.viewMode == ViewMode.LIST
        }

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.LongPress), haptics.events)
            viewModel.clearSelection()
            viewModel.setViewMode(ViewMode.GRID)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.viewMode == ViewMode.GRID &&
                viewModel.browseState.value.selectedFiles.isEmpty()
        }

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(
                listOf(HapticFeedbackType.LongPress, HapticFeedbackType.LongPress),
                haptics.events,
            )
        }
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

    @Test
    fun singleSelectionDetailsShowTheFilePath() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("More selection actions").performClick()
        composeTestRule.onNodeWithText("Details").assertIsDisplayed().performClick()

        composeTestRule.onNodeWithText("Details").assertIsDisplayed()
        composeTestRule.onNodeWithText(root.resolve("notes.txt").absolutePath)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun oneLocalFileOffersOpenWith() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("More selection actions").performClick()

        composeTestRule.onNodeWithText("Open with").assertIsDisplayed()
    }

    @Test
    fun aFolderDoesNotOfferOpenWith() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("Folder") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("More selection actions").performClick()

        composeTestRule.onNodeWithText("Open with").assertDoesNotExist()
    }

    @Test
    fun selectedFileCanOpenCompressionDialogWithSafeDefaultName() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("More selection actions").performClick()
        composeTestRule.onNodeWithText("Compress to ZIP").assertIsDisplayed().performClick()

        composeTestRule.onNodeWithText("Compress to ZIP").assertIsDisplayed()
        composeTestRule.onNodeWithText("notes.zip").assertIsDisplayed()
    }

    @Test
    fun supportedArchiveOffersExtraction() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("archive.zip") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("More selection actions").performClick()

        composeTestRule.onNodeWithText("Extract here").assertIsDisplayed()
    }

    @Test
    fun tappingSupportedArchiveConfirmsBeforeExtraction() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("archive.zip") and hasClickAction()).performClick()

        composeTestRule.onNodeWithText("Extract archive?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Extract archive?").assertDoesNotExist()
        assertFalse(root.resolve("archive_extracted").exists())

        composeTestRule.onNode(hasText("archive.zip") and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Extract").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            root.resolve("archive_extracted/inside.txt").isFile
        }
    }

    @Test
    fun tappingUnsupportedArchiveExplainsWhyWithoutConfirming() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("legacy.rar") and hasClickAction()).performClick()

        composeTestRule.onNodeWithText("RAR extraction is not available in this build")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Extract archive?").assertDoesNotExist()
    }

    @Test
    fun rarExplainsThatExtractionIsUnavailable() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.onNode(hasText("legacy.rar") and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("More selection actions").performClick()

        composeTestRule.onNodeWithText("Extract here").assertIsDisplayed()
        composeTestRule.onNodeWithText("RAR extraction is not available in this build")
            .assertIsDisplayed()
    }

    private fun launchBrowser(
        hapticFeedback: HapticFeedback? = null,
    ): FileBrowserViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {
            MaterialTheme {
                if (hapticFeedback == null) {
                    BrowserScreen(
                        viewModel = viewModel,
                        onNavigateBack = {},
                    )
                } else {
                    CompositionLocalProvider(LocalHapticFeedback provides hapticFeedback) {
                        BrowserScreen(
                            viewModel = viewModel,
                            onNavigateBack = {},
                        )
                    }
                }
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

    private class RecordingHapticFeedback : HapticFeedback {
        val events = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            events += hapticFeedbackType
        }
    }
}
