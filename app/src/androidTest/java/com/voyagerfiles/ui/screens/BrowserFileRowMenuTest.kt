package com.voyagerfiles.ui.screens

import android.app.Application
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.archive.ArchiveExtractionReport
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.viewmodel.ClipboardOperation
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class BrowserFileRowMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule(effectContext = StandardTestDispatcher())

    private val viewModelStore = ViewModelStore()
    private lateinit var root: File
    private lateinit var preferences: PreferencesManager
    private lateinit var originalViewMode: ViewMode
    private var originalUseTrash = false

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        preferences = PreferencesManager(application)
        runBlocking {
            originalViewMode = preferences.viewMode.first()
            originalUseTrash = preferences.useTrash.first()
            preferences.setViewMode(ViewMode.LIST)
        }
        root = File(application.cacheDir, "browser-file-row-menu-test").apply {
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
        composeTestRule.runOnIdle { viewModelStore.clear() }
        root.deleteRecursively()
        runBlocking {
            preferences.setViewMode(originalViewMode)
            preferences.setUseTrash(originalUseTrash)
        }
    }

    @Test
    fun menuOffersTheFileActionsWithoutSelectingTheFile() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("notes.txt")

        listOf("Open with", "Share", "Copy", "Cut", "Rename", "Compress to ZIP", "Details", "Delete")
            .forEach { composeTestRule.onNodeWithText(it).assertIsDisplayed() }
        composeTestRule.onNodeWithText("Download").assertDoesNotExist()
        composeTestRule.runOnIdle { assertTrue(viewModel.browseState.value.selectedFiles.isEmpty()) }
    }

    @Test
    fun tapNearTheRowsTopRightCornerOpensTheMenu() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        // Outside the icon-sized circle, but inside the full-height strip at the row's end.
        composeTestRule.onNode(hasText("Folder") and hasClickAction())
            .performTouchInput { click(Offset(width - 2f, 2f)) }

        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(root.absolutePath, viewModel.browseState.value.currentPath)
        }
    }

    @Test
    fun tapNearCompactRowsTopRightCornerOpensTheMenu() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        composeTestRule.runOnIdle { viewModel.setViewMode(ViewMode.COMPACT) }
        waitForCompactRows()
        composeTestRule.onNode(hasText("Folder") and hasClickAction())
            .performTouchInput { click(Offset(width - 2f, 2f)) }

        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(root.absolutePath, viewModel.browseState.value.currentPath)
        }
    }

    @Test
    fun copyFromTheMenuCopiesOnlyThatFile() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Copy").performClick()

        composeTestRule.runOnIdle {
            assertEquals(listOf(root.resolve("notes.txt").absolutePath), viewModel.clipboardPaths.value)
            assertEquals(ClipboardOperation.COPY, viewModel.clipboardOperation.value)
            assertTrue(viewModel.browseState.value.selectedFiles.isEmpty())
        }
    }

    @Test
    fun cutFromTheMenuCutsOnlyThatFile() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Cut").performClick()

        composeTestRule.runOnIdle {
            assertEquals(listOf(root.resolve("notes.txt").absolutePath), viewModel.clipboardPaths.value)
            assertEquals(ClipboardOperation.CUT, viewModel.clipboardOperation.value)
            assertTrue(viewModel.browseState.value.selectedFiles.isEmpty())
        }
        assertTrue(root.resolve("notes.txt").exists())
    }

    @Test
    fun renameFromTheMenuRenamesThatFile() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNode(hasSetTextAction() and hasText("notes.txt")).performTextReplacement("renamed.txt")
        composeTestRule.onNode(hasText("Rename") and hasClickAction()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) { root.resolve("renamed.txt").exists() }
        assertTrue(!root.resolve("notes.txt").exists())
    }

    @Test
    fun deleteFromTheMenuNamesAndRemovesOnlyThatFile() {
        val viewModel = launchBrowser()
        composeTestRule.runOnIdle { viewModel.setUseTrash(true) }
        waitForRoot(viewModel)
        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.useTrash.value }

        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNodeWithText("Delete \"notes.txt\"?").assertIsDisplayed()
        composeTestRule.runOnIdle { viewModel.toggleSelection(root.resolve("archive.zip").absolutePath) }
        composeTestRule.onNodeWithText("Delete permanently").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) { !root.resolve("notes.txt").exists() }
        assertTrue(root.resolve("archive.zip").exists())
        assertTrue(root.resolve("legacy.rar").exists())
        assertTrue(root.resolve("Folder").isDirectory)
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            !viewModel.browseState.value.isLoading &&
                viewModel.browseState.value.files.none { it.name == "notes.txt" }
        }
        composeTestRule.runOnIdle {
            assertEquals(setOf(root.resolve("archive.zip").absolutePath), viewModel.browseState.value.selectedFiles)
        }
    }

    @Test
    fun deleteDialogClosesWhenTheFolderChanges() {
        val peer = root.resolve("Folder")
        peer.resolve("notes.txt").writeText("peer file")
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNodeWithText("Delete \"notes.txt\"?").assertIsDisplayed()

        composeTestRule.runOnIdle { viewModel.navigateTo(peer.absolutePath) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.currentPath == peer.absolutePath &&
                composeTestRule.onAllNodesWithText("Delete \"notes.txt\"?").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("notes", root.resolve("notes.txt").readText())
        assertEquals("peer file", peer.resolve("notes.txt").readText())
    }

    @Test
    fun compressionDialogClosesWhenTheSessionChanges() {
        val peer = root.resolve("Folder")
        peer.resolve("notes.txt").writeText("peer file")
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        val originalSession = viewModel.activeSession.value?.id
        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Compress to ZIP").performClick()
        composeTestRule.onNodeWithText("Create").assertIsDisplayed()

        composeTestRule.runOnIdle { viewModel.openLocalRoot(peer.absolutePath) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.activeSession.value?.id != originalSession &&
                viewModel.browseState.value.currentPath == peer.absolutePath &&
                composeTestRule.onAllNodesWithText("Create").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(!root.resolve("notes.zip").exists())
        assertTrue(!peer.resolve("notes.zip").exists())
        assertEquals("notes", root.resolve("notes.txt").readText())
        assertEquals("peer file", peer.resolve("notes.txt").readText())
    }

    @Test
    fun retainedExtractionUsesNormalDeleteConfirmationForItsFolder() {
        val extracted = root.resolve("Folder")
        extracted.resolve("kept.txt").writeText("kept output")
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        composeTestRule.runOnIdle { viewModel.setUseTrash(true) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.useTrash.value }
        val report = ArchiveExtractionReport(
            root = FileItem(extracted.name, extracted.absolutePath, isDirectory = true),
            extractedEntries = 1, extractedFiles = 1, totalEntries = 2,
            renamed = emptyList(), notExtracted = emptyList(), complete = false,
        )
        composeTestRule.runOnIdle {
            viewModel.toggleSelection(root.resolve("notes.txt").absolutePath)
            viewModel.requestExtractionRemoval(report)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Delete \"Folder\"?").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Delete \"Folder\"?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(extracted.resolve("kept.txt").isFile)
        assertTrue(root.resolve("notes.txt").isFile)

        composeTestRule.runOnIdle { viewModel.requestExtractionRemoval(report) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Delete \"Folder\"?").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Delete permanently").performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) { !extracted.exists() }
        assertTrue(root.resolve("notes.txt").isFile)
    }

    @Test
    fun compressFromTheMenuZipsThatFile() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Compress to ZIP").performClick()
        composeTestRule.runOnIdle { viewModel.toggleSelection(root.resolve("archive.zip").absolutePath) }
        composeTestRule.onNodeWithText("Create").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            root.resolve("notes.zip").exists() &&
                !viewModel.browseState.value.isLoading &&
                viewModel.browseState.value.files.any { it.name == "notes.zip" }
        }
        ZipFile(root.resolve("notes.zip")).use { zip ->
            assertEquals(listOf("notes.txt"), zip.entries().asSequence().map { it.name }.toList())
            assertEquals("notes", zip.getInputStream(zip.getEntry("notes.txt")).bufferedReader().use { it.readText() })
        }
        composeTestRule.runOnIdle {
            assertEquals(setOf(root.resolve("archive.zip").absolutePath), viewModel.browseState.value.selectedFiles)
        }
    }

    @Test
    fun extractFromTheMenuExtractsThatArchive() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("archive.zip")
        composeTestRule.onNodeWithText("Compress to ZIP").assertIsDisplayed()
        composeTestRule.onNodeWithText("Extract here").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            root.walkTopDown().any { it.name == "inside.txt" }
        }
    }

    @Test
    fun rarMenuExplainsThatExtractionIsUnavailable() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("legacy.rar")

        composeTestRule.onNodeWithText("Extract here").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithText("RAR extraction is not available in this build").assertIsDisplayed()
    }

    @Test
    fun folderMenuOffersFolderShortcuts() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("Folder")

        composeTestRule.onNodeWithText("Add folder to Home screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share").assertDoesNotExist()
    }

    @Test
    fun detailsFromTheMenuShowTheFilePath() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Details").performClick()

        composeTestRule.onNodeWithText(root.resolve("notes.txt").absolutePath)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun rowMenusHideWhileSelecting() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        assertEquals(4, rowMenuButtonCount())

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        assertEquals(0, rowMenuButtonCount())
    }

    @Test
    fun compactListHasRowMenusAndGridHasNone() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.runOnIdle { viewModel.setViewMode(ViewMode.COMPACT) }
        waitForCompactRows()
        assertEquals(4, rowMenuButtonCount())

        composeTestRule.runOnIdle { viewModel.setViewMode(ViewMode.GRID) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) { rowMenuButtonCount() == 0 }
        assertEquals(ViewMode.GRID, viewModel.browseState.value.viewMode)
    }

    @Test
    fun remoteRightThenCenterOpensTheMenuOnTv() {
        val viewModel = launchBrowser(isTelevision = true)
        waitForRoot(viewModel)
        val firstRow = viewModel.browseState.value.visibleFiles.first().name
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeTestRule.onNode(hasText(firstRow) and hasClickAction()).assertIsFocused()
            }.isSuccess
        }

        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeTestRule.onNodeWithContentDescription("More options for $firstRow").assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)

        composeTestRule.onNodeWithText("Details").assertIsDisplayed()
        composeTestRule.runOnIdle { assertTrue(viewModel.browseState.value.selectedFiles.isEmpty()) }
    }

    private fun waitForCompactRows() {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeTestRule.onNodeWithContentDescription("More options for Folder").assertWidthIsEqualTo(48.dp)
            }.isSuccess
        }
        composeTestRule.waitForIdle()
    }

    private fun openMenuFor(name: String) {
        composeTestRule.onNodeWithContentDescription("More options for $name").performClick()
    }

    private fun rowMenuButtonCount(): Int =
        composeTestRule.onAllNodesWithTag(FILE_ROW_MENU_BUTTON_TEST_TAG).fetchSemanticsNodes().size

    private fun sendKey(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        instrumentation.sendKeySync(KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, keyCode, 0))
        composeTestRule.waitForIdle()
    }

    private fun launchBrowser(isTelevision: Boolean = false): FileBrowserViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        viewModelStore.put("browser", viewModel)
        composeTestRule.setContent {
            MaterialTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    isTelevision = isTelevision,
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
                !viewModel.browseState.value.isLoading &&
                composeTestRule.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
