package com.voyagerfiles.ui.screens

import android.app.Application
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.R
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrowserLocationSheetTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val dao = AppDatabase.getInstance(application).bookmarkDao()
    private val store = ViewModelStore()
    private lateinit var root: File
    /** Four bookmarked folders, least recently used first; the overview shows only the three most recent. */
    private lateinit var bookmarked: List<File>
    private lateinit var viewModel: FileBrowserViewModel
    private val sessionsLabel get() = application.getString(R.string.content_desc_sessions)
    private val showAllTag = "location-show-all:${LocationGroup.BOOKMARKS.name}"

    @Before fun setUp() {
        root = File(application.cacheDir, "location-sheet-root").apply { mkdirs() }
        bookmarked = listOf("a", "b", "c", "d").map { File(application.cacheDir, "location-sheet-$it").apply { mkdirs() } }
        val now = System.currentTimeMillis()
        runBlocking {
            bookmarked.forEachIndexed { index, folder ->
                dao.deleteByPath(folder.path, FileSource.LOCAL)
                // Used within the last second, oldest first, so these outrank whatever bookmarks the device already has.
                dao.insertIfAbsent(
                    Bookmark(
                        name = folder.name,
                        path = folder.path,
                        createdAt = now - 10_000L + index,
                        lastUsedAt = now - 1_000L + index,
                    ),
                )
            }
        }
        compose.runOnIdle {
            viewModel = FileBrowserViewModel(application)
            store.put("browser", viewModel)
            viewModel.openLocalRoot(root.path)
        }
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        runBlocking { bookmarked.forEach { dao.deleteByPath(it.path, FileSource.LOCAL) } }
        root.deleteRecursively()
        bookmarked.forEach { it.deleteRecursively() }
    }

    private fun showBrowser(onNavigateBack: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = onNavigateBack,
                    isTelevision = false,
                    hasAllFilesAccess = true,
                )
            }
        }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == root.path }
        compose.waitUntil(10_000) { bookmarked.all { folder -> viewModel.bookmarks.value.any { it.path == folder.path } } }
    }

    private fun rowTag(folder: File): String =
        "location-bookmark:" + viewModel.bookmarks.value.single { it.path == folder.path }.id

    private fun openSheet() = compose.onNodeWithContentDescription(sessionsLabel).performClick()

    @Test fun bookmarkInSheetOpensASecondSessionInPlace() {
        var leftBrowser = 0
        showBrowser { leftBrowser++ }
        val newest = bookmarked.last()

        openSheet()
        compose.onNodeWithTag(rowTag(newest)).performScrollTo().assertIsDisplayed().performClick()

        compose.waitUntil(10_000) { viewModel.activeSession.value?.rootPath == newest.path }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == newest.path }
        assertEquals(2, viewModel.sessions.value.size)
        assertEquals(0, leftBrowser)

        // Once its session exists the bookmark is listed as a session, not offered again as a location.
        openSheet()
        compose.onNodeWithTag("location-home").assertIsDisplayed()
        compose.onNodeWithTag(rowTag(newest)).assertDoesNotExist()
    }

    @Test fun homeActionLeavesTheBrowserWithoutClosingSessions() {
        var leftBrowser = 0
        showBrowser { leftBrowser++ }

        openSheet()
        compose.onNodeWithTag("location-home").performClick()

        compose.runOnIdle { assertEquals(1, leftBrowser) }
        assertEquals(1, viewModel.sessions.value.size)
    }

    @Test fun overviewShowsThreeRecentBookmarksAndShowAllRevealsTheRest() {
        showBrowser()
        val (oldest, second, third, newest) = bookmarked

        openSheet()
        listOf(second, third, newest).forEach { compose.onNodeWithTag(rowTag(it)).assertExists() }
        compose.onNodeWithTag(rowTag(oldest)).assertDoesNotExist()

        compose.onNodeWithTag(showAllTag).performScrollTo().performClick()
        bookmarked.forEach { compose.onNodeWithTag(rowTag(it)).assertExists() }
        compose.onNodeWithTag("location-home").assertDoesNotExist()

        compose.onNodeWithTag("location-back").performClick()
        compose.onNodeWithTag(rowTag(oldest)).assertDoesNotExist()
        compose.onNodeWithTag(showAllTag).assertExists()
        compose.onNodeWithTag("location-home").assertExists()
    }

    @Test fun openingABookmarkMovesItIntoTheRecentEntries() {
        showBrowser()
        val (oldest, second) = bookmarked
        val oldestUsedBefore = viewModel.bookmarks.value.single { it.path == oldest.path }.lastUsedAt
        val secondUsedBefore = viewModel.bookmarks.value.single { it.path == second.path }.lastUsedAt

        openSheet()
        compose.onNodeWithTag(showAllTag).performScrollTo().performClick()
        compose.onNodeWithTag(rowTag(oldest)).performScrollTo().performClick()
        compose.waitUntil(10_000) { viewModel.activeSession.value?.rootPath == oldest.path }
        compose.waitUntil(10_000) { viewModel.bookmarks.value.single { it.path == oldest.path }.lastUsedAt > oldestUsedBefore }

        // Close that session again so the bookmark is offered as a location, now as the most recent one.
        val openedSession = viewModel.sessions.value.single { it.rootPath == oldest.path }
        compose.runOnIdle { viewModel.closeSession(openedSession.id) }
        compose.waitUntil(10_000) { viewModel.sessions.value.size == 1 }

        openSheet()
        compose.onNodeWithTag(rowTag(oldest)).assertExists()
        compose.onNodeWithTag(rowTag(second)).assertDoesNotExist()
        assertEquals(secondUsedBefore, viewModel.bookmarks.value.single { it.path == second.path }.lastUsedAt)
    }

    @Test fun systemBackReturnsFromTheFullListToTheOverviewBeforeClosingTheSheet() {
        showBrowser()

        openSheet()
        compose.onNodeWithTag(showAllTag).performScrollTo().performClick()
        compose.onNodeWithTag("location-back").assertExists()

        pressBack()
        compose.onNodeWithTag("location-home").assertExists()
        compose.onNodeWithTag("location-back").assertDoesNotExist()

        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("location-home").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, viewModel.sessions.value.size)
        assertEquals(root.path, viewModel.browseState.value.currentPath)
    }

    /** Sends a real back key to the focused window, which is the sheet's own window while it is open. */
    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
}
