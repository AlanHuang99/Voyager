package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.R
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.SearchBarMode
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.ScrollPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrowserFeedbackRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = PreferencesManager(application)
    private val store = ViewModelStore()
    private lateinit var root: File
    private lateinit var peer: File
    private lateinit var viewModel: FileBrowserViewModel
    private lateinit var originalSearch: SearchBarMode
    private lateinit var originalView: ViewMode

    @Before fun setUp() {
        root = File(application.cacheDir, "september-feedback").apply {
            deleteRecursively()
            mkdirs()
            repeat(80) { index ->
                resolve("folder-%03d".format(index)).mkdirs()
            }
        }
        peer = File(application.cacheDir, "september-feedback-peer").apply { mkdirs() }
        runBlocking {
            originalSearch = prefs.searchBarMode.first()
            originalView = prefs.viewMode.first()
            prefs.setSearchBarMode(SearchBarMode.TOP)
            prefs.setViewMode(ViewMode.LIST)
        }
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        runBlocking {
            AppDatabase.getInstance(application).bookmarkDao().deleteByPath(root.resolve("folder-000").path, FileSource.LOCAL)
            prefs.setSearchBarMode(originalSearch)
            prefs.setViewMode(originalView)
        }
        root.deleteRecursively()
        peer.deleteRecursively()
    }

    private fun showBrowser(onFindDuplicates: (String) -> Unit = {}) {
        compose.runOnIdle {
            viewModel = FileBrowserViewModel(application)
            store.put("browser", viewModel)
            viewModel.openLocalRoot(root.path)
        }
        compose.setContent { MaterialTheme { BrowserScreen(viewModel, {}, onFindDuplicates = onFindDuplicates) } }
        awaitDirectory(root)
    }

    private fun awaitDirectory(folder: File) {
        compose.waitUntil(10_000) { viewModel.browseState.value.let { it.currentPath == folder.path && !it.isLoading } }
        compose.waitForIdle()
    }

    private fun holdFirstItem() {
        compose.onNode(hasText("folder-000") and hasClickAction()).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset.Zero)
        }
        compose.waitUntil(5_000) { viewModel.browseState.value.selectedFiles.isNotEmpty() }
    }

    @Test fun stationaryLongPressSelectsOnlyOneFolderAfterToolbarMoves() {
        showBrowser()
        holdFirstItem()
        compose.waitForIdle()
        compose.onNodeWithTag("browser-files").performTouchInput {
            advanceEventTime(400)
            moveBy(Offset(1f, 0f))
            up()
        }
        compose.runOnIdle { assertEquals(setOf(root.resolve("folder-000").path), viewModel.browseState.value.selectedFiles) }
    }

    @Test fun deliberateDragStillSelectsARangeAfterToolbarMoves() {
        showBrowser()
        holdFirstItem()
        val target = compose.onNode(hasText("folder-004") and hasClickAction()).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { moveTo(target, delayMillis = 100); up() }
        compose.waitUntil(5_000) { viewModel.browseState.value.selectedFiles.size > 1 }
        assertEquals((0..4).map { root.resolve("folder-%03d".format(it)).path }.toSet(), viewModel.browseState.value.selectedFiles)
    }

    @Test fun listRestoresParentAndRefreshScrollPosition() = checkParentScroll(ViewMode.LIST)
    @Test fun gridRestoresParentAndRefreshScrollPosition() = checkParentScroll(ViewMode.GRID)

    private fun checkParentScroll(mode: ViewMode) {
        runBlocking { prefs.setViewMode(mode) }
        showBrowser()
        compose.waitUntil { viewModel.browseState.value.viewMode == mode }
        compose.onNodeWithTag("browser-files").performScrollToIndex(45)
        compose.waitForIdle()
        val position = viewModel.directoryScrollPosition
        assertTrue(position.index > 30)
        compose.onNode(hasText("folder-045") and hasClickAction()).performClick()
        awaitDirectory(root.resolve("folder-045"))
        compose.runOnIdle { assertTrue(viewModel.navigateUp()) }
        awaitDirectory(root)
        compose.onNodeWithText("folder-045").assertIsDisplayed()
        compose.runOnIdle { assertEquals(position, viewModel.directoryScrollPosition); viewModel.refresh() }
        awaitDirectory(root)
        compose.onNodeWithText("folder-045").assertIsDisplayed()
        compose.runOnIdle { assertEquals(position, viewModel.directoryScrollPosition) }
    }

    @Test fun sessionSwitchRestoresScrollAndRejectsLateUpdatesFromPreviousSession() {
        showBrowser()
        val firstId = viewModel.activeSession.value!!.id
        compose.onNodeWithTag("browser-files").performScrollToIndex(45)
        compose.waitForIdle()
        val position = viewModel.directoryScrollPosition
        compose.runOnIdle { viewModel.openLocalRoot(peer.path) }
        awaitDirectory(peer)
        compose.runOnIdle { viewModel.onDirectoryScrolled(firstId, root.path, ScrollPosition(70, 0)) }
        assertEquals(ScrollPosition.TOP, viewModel.directoryScrollPosition)
        compose.runOnIdle { viewModel.activateSession(firstId) }
        awaitDirectory(root)
        compose.onNodeWithText("folder-045").assertIsDisplayed()
        compose.runOnIdle { assertEquals(position, viewModel.directoryScrollPosition) }
    }

    @Test fun compactSearchOpensFiltersAndClosesWithoutLeavingFolder() {
        runBlocking { prefs.setSearchBarMode(SearchBarMode.COMPACT) }
        showBrowser()
        compose.onNodeWithTag(BROWSER_SEARCH_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription(application.getString(R.string.action_search)).performClick()
        compose.onNodeWithTag(BROWSER_SEARCH_TEST_TAG).performTextInput("folder-079")
        compose.waitUntil { viewModel.browseState.value.visibleFiles.size == 1 }
        compose.onNodeWithContentDescription(application.getString(R.string.action_close_search)).performClick()
        compose.onNodeWithTag(BROWSER_SEARCH_TEST_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(root.path, viewModel.browseState.value.currentPath)
            assertEquals("", viewModel.browseState.value.searchQuery)
            assertEquals(80, viewModel.browseState.value.visibleFiles.size)
        }
        assertEquals(SearchBarMode.COMPACT, runBlocking { PreferencesManager(application).searchBarMode.first() })
    }

    @Test fun bottomSearchIsBelowFilesAndFiltersTheDirectory() {
        runBlocking { prefs.setSearchBarMode(SearchBarMode.BOTTOM) }
        showBrowser()
        val search = compose.onNodeWithTag(BROWSER_SEARCH_TEST_TAG)
        assertTrue(search.fetchSemanticsNode().boundsInRoot.top >= compose.onNodeWithTag("browser-files").fetchSemanticsNode().boundsInRoot.bottom)
        search.performTextInput("folder-079")
        compose.waitUntil { viewModel.browseState.value.visibleFiles.size == 1 }
        assertEquals(SearchBarMode.BOTTOM, runBlocking { PreferencesManager(application).searchBarMode.first() })
    }

    @Test fun selectedFolderHasBookmarkShortcutAndDuplicateActionsForThatFolder() {
        var duplicatePath: String? = null
        showBrowser { duplicatePath = it }
        val folder = root.resolve("folder-000")
        compose.runOnIdle { viewModel.toggleSelection(folder.path) }
        val menu = application.getString(R.string.content_desc_more_selection_actions)
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(application.getString(R.string.shortcut_pin_folder)).assertIsDisplayed()
        compose.onNodeWithText(application.getString(R.string.action_bookmark_folder)).performClick()
        compose.waitUntil(5_000) { viewModel.bookmarks.value.any { it.path == folder.path } }
        assertFalse(viewModel.bookmarks.value.any { it.path == root.path })
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(application.getString(R.string.action_remove_bookmark)).performClick()
        compose.waitUntil(5_000) { viewModel.bookmarks.value.none { it.path == folder.path } }
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(application.getString(R.string.duplicates_title)).performClick()
        assertEquals(folder.path, duplicatePath)
    }
}
