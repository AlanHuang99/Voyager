package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.R
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class BookmarkActionsTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val dao = AppDatabase.getInstance(application).bookmarkDao()
    private val store = ViewModelStore()
    private lateinit var root: File
    private lateinit var viewModel: FileBrowserViewModel
    private val removeLabel get() = application.getString(R.string.action_remove_bookmark)

    @Before fun setUp() {
        root = File(application.cacheDir, "bookmark-actions-test").apply { mkdirs() }
        runBlocking {
            dao.deleteByPath(root.path, FileSource.LOCAL)
            dao.deleteByPath(root.path, FileSource.SFTP)
        }
        compose.runOnIdle {
            viewModel = FileBrowserViewModel(application)
            store.put("browser", viewModel)
            viewModel.openLocalRoot(root.path)
        }
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        runBlocking {
            dao.deleteByPath(root.path, FileSource.LOCAL)
            dao.deleteByPath(root.path, FileSource.SFTP)
        }
        root.deleteRecursively()
    }

    @Test fun browserReflectsAsyncPersistenceAndShowsMatchingAddRemoveIcons() {
        compose.setContent {
            MaterialTheme { BrowserScreen(viewModel, onNavigateBack = {}) }
        }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == root.path }
        openMenu()
        compose.onNodeWithText(application.getString(R.string.action_bookmark_folder)).assertIsDisplayed()
        compose.onNodeWithTag("bookmark-add-icon", useUnmergedTree = true).assertIsDisplayed()

        // A save arriving while the menu is open must change both its label and icon.
        runBlocking { dao.insertIfAbsent(Bookmark(name = root.name, path = root.path)) }
        compose.waitUntil(10_000) { viewModel.bookmarks.value.any { it.path == root.path } }
        compose.onNodeWithTag("bookmark-remove-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(removeLabel).performClick()
        compose.waitUntil(10_000) { viewModel.bookmarks.value.none { it.path == root.path } }
        assertFalse(runBlocking { dao.isBookmarked(root.path, FileSource.LOCAL) })

        openMenu()
        compose.onNodeWithTag("bookmark-add-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(application.getString(R.string.action_bookmark_folder)).performClick()
        compose.waitUntil(10_000) { viewModel.bookmarks.value.any { it.path == root.path } }
        assertTrue(runBlocking { dao.isBookmarked(root.path, FileSource.LOCAL) })
        openMenu()
        compose.onNodeWithText(removeLabel).assertIsDisplayed()
    }

    @Test fun homeLongPressCancelAndAccessibleRemoveKeepNavigationSeparate() {
        runBlocking { dao.insertIfAbsent(Bookmark(name = root.name, path = root.path)) }
        var navigations = 0
        compose.setContent {
            MaterialTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToBrowser = { navigations++ },
                    onNavigateToSession = { _, _ -> },
                    onNavigateToConnections = {}, onNavigateToTrash = {},
                    onNavigateToSettings = {}, onOpenSafTree = {},
                    hasAllFilesAccess = true, onRequestAllFilesAccess = {},
                )
            }
        }
        compose.waitUntil(10_000) { viewModel.bookmarks.value.any { it.path == root.path } }
        val bookmarkId = viewModel.bookmarks.value.single { it.path == root.path }.id
        val rowTag = "home-bookmark:$bookmarkId"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(rowTag))
        val row = compose.onNodeWithTag(rowTag)
        row.performScrollTo().assertIsDisplayed().performTouchInput { longClick(durationMillis = 1_000) }
        assertEquals(0, navigations)
        compose.onNodeWithText(application.getString(R.string.action_cancel)).performClick()
        assertTrue(runBlocking { dao.isBookmarked(root.path, FileSource.LOCAL) })
        val longClick = row.fetchSemanticsNode().config[SemanticsActions.OnLongClick]
        assertEquals(removeLabel, longClick.label)
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNode(hasText(removeLabel) and hasClickAction()).performClick()
        compose.waitUntil(10_000) { viewModel.bookmarks.value.none { it.path == root.path } }
        assertEquals(0, navigations)
        assertFalse(runBlocking { dao.isBookmarked(root.path, FileSource.LOCAL) })
        assertTrue(root.exists())
    }

    @Test fun repeatedAddAndStaleRemovalAreIdempotentAndSourceScoped() {
        runBlocking { dao.insertIfAbsent(Bookmark(name = "Remote", path = root.path, source = FileSource.SFTP)) }
        compose.runOnIdle {
            viewModel.addBookmark(root.path, root.name)
            viewModel.addBookmark(root.path, root.name)
        }
        compose.waitUntil(10_000) {
            runBlocking { dao.getAllBookmarks().first().count { it.path == root.path && it.source == FileSource.LOCAL } == 1 }
        }
        compose.runOnIdle {
            viewModel.removeBookmark(root.path, FileSource.LOCAL)
            viewModel.removeBookmark(root.path, FileSource.LOCAL)
        }
        compose.waitUntil(10_000) { !runBlocking { dao.isBookmarked(root.path, FileSource.LOCAL) } }
        compose.waitForIdle()
        assertEquals(
            listOf(FileSource.SFTP),
            runBlocking { dao.getAllBookmarks().first().filter { it.path == root.path }.map { it.source } },
        )
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(application.getString(R.string.content_desc_more)).performClick()
    }
}
