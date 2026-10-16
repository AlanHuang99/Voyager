package com.voyagerfiles.ui.screens

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.Environment
import android.os.Build
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.index.CategoryIndexState
import com.voyagerfiles.data.index.CategoryScanStatus
import com.voyagerfiles.data.index.CategoryVolumeCoverage
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.util.StorageVolumeInfo
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.StorageSearchViewModel
import com.voyagerfiles.viewmodel.StorageSearchState
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class StorageSearchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(application.cacheDir, "storage-search-screen").apply {
            deleteRecursively()
            resolve("one").mkdirs()
            resolve("two").mkdirs()
            resolve("one/report.txt").writeText("first")
            resolve("two/report.txt").writeText("second")
        }
    }

    @After
    fun tearDown() {
        compose.runOnIdle { store.clear() }
        root.deleteRecursively()
    }

    @Test
    fun submittingByKeyboardFindsBothPathsAndOpensTheChosenFolder() {
        val reads = AtomicInteger()
        val model = searchModel(reads)
        var opened: String? = null
        showSearch(model) { opened = it }
        compose.onNodeWithTag("storage-search-query").performTextInput("report")
        compose.runOnIdle { assertEquals(0, reads.get()) }
        compose.onNodeWithTag("storage-search-query").performImeAction()
        awaitResults(model)
        compose.onNodeWithText("Test storage").assertIsDisplayed()
        compose.onNodeWithText("Files checked: 2 · Inaccessible entries: 0 · Skipped entries: 0").assertIsDisplayed()

        val first = root.resolve("one/report.txt").canonicalFile
        val second = root.resolve("two/report.txt").canonicalFile
        compose.onNodeWithTag("storage-search-results").performScrollToNode(hasText(first.path))
        compose.onNodeWithText(first.path).assertIsDisplayed()
        compose.onNodeWithTag("storage-search-results").performScrollToNode(hasText(second.path))
        compose.onNodeWithText(second.path).assertIsDisplayed()
        compose.onNodeWithContentDescription("Open folder for ${second.path}").performClick()
        compose.waitUntil(5_000) { opened != null }
        assertEquals(second.parent, opened)
    }

    @Test
    fun removedResultCannotOpenItsFormerLocation() {
        val model = searchModel()
        var opened: String? = null
        showSearch(model) { opened = it }
        compose.onNodeWithTag("storage-search-query").performTextInput("report")
        compose.onNodeWithTag("storage-search-query").performImeAction()
        awaitResults(model)
        val removed = root.resolve("one/report.txt").canonicalFile
        assertTrue(removed.delete())

        compose.onNodeWithTag("storage-search-results").performScrollToNode(hasContentDescription("Open folder for ${removed.path}"))
        compose.onNodeWithContentDescription("Open folder for ${removed.path}").performClick()
        compose.waitUntil(5_000) { model.state.value.result.staleEntries == 1 }
        compose.runOnIdle {
            assertNull(opened)
            assertEquals(CategoryScanStatus.PARTIAL, model.state.value.result.status)
        }
        compose.onNodeWithText(removed.path).assertDoesNotExist()
        compose.onNodeWithText("This file changed or is no longer accessible. Search again.").assertIsDisplayed()
    }

    @Test
    fun fileOpenFailureReturnsToTheMainThreadAndShowsItsMessage() {
        root.resolve("unhandled.dat").writeText("fixture")
        val model = searchModel()
        val openedOnMain = AtomicBoolean()
        val context = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent) {
                openedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                throw ActivityNotFoundException("No handler for the fixture")
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { StorageSearchScreen(false, true, {}, {}, {}, model) }
            }
        }
        compose.onNodeWithTag("storage-search-query").performTextInput("unhandled")
        compose.onNodeWithTag("storage-search-query").performImeAction()
        compose.waitUntil(10_000) { model.state.value.result.status == CategoryScanStatus.COMPLETE }
        compose.onNodeWithTag("storage-search-results").performScrollToNode(hasText("unhandled.dat"))
        compose.onNodeWithText("unhandled.dat").performClick()
        compose.onNodeWithText("No app can open this file type").assertIsDisplayed()
        assertTrue(openedOnMain.get())
    }

    @Test
    fun homeSearchOffersPermissionRecoveryAndReturnsHome() {
        val browser = browserModel()
        var requested = false
        compose.setContent {
            MaterialTheme { AppNavigation(browser, false, onRequestAllFilesAccess = { requested = true }) }
        }
        compose.onNodeWithContentDescription("Search shared storage").performClick()
        compose.onNodeWithText("Full storage access is required to search shared storage.").assertIsDisplayed()
        compose.onNodeWithText("Grant full access").performClick()
        assertTrue(requested)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Search shared storage").assertIsDisplayed()
    }

    @Test
    fun partialResultsIdentifyInaccessibleAndUnavailableVolumes() {
        val result = CategoryIndexState(
            status = CategoryScanStatus.PARTIAL,
            coverage = listOf(
                CategoryVolumeCoverage(
                    StorageVolumeInfo("Internal storage", "/storage/example", true, false, "mounted"),
                    CategoryScanStatus.PARTIAL, filesExamined = 3, inaccessibleEntries = 1, skippedEntries = 2,
                ),
                CategoryVolumeCoverage(
                    StorageVolumeInfo("Removed card", "/storage/card", false, true, "unmounted"),
                    CategoryScanStatus.UNAVAILABLE,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                StorageSearchContent(
                    StorageSearchState("report", "report", result), remember { SnackbarHostState() },
                    onQueryChange = {}, onSubmit = {}, onRefresh = {}, onCancel = {},
                    onRequestAllFilesAccess = {}, onNavigateBack = {}, onOpenFile = {}, onOpenLocation = {},
                )
            }
        }
        compose.onNodeWithText("Internal storage").assertIsDisplayed()
        compose.onNodeWithText("Files checked: 3 · Inaccessible entries: 1 · Skipped entries: 2").assertIsDisplayed()
        compose.onNodeWithText("Removed card").assertIsDisplayed()
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
    }

    @Test
    fun realSharedStorageResultOpensANewLocalSessionForItsFolder() {
        assumeTrue("Full storage access is required for the shared-storage navigation fixture", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        val fixture = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "voyager-search-${UUID.randomUUID()}")
        val needle = "voyager-search-${UUID.randomUUID()}"
        val firstFolder = fixture.resolve("one").apply { mkdirs() }
        val chosenFolder = fixture.resolve("two").apply { mkdirs() }
        val first = firstFolder.resolve("$needle.txt").apply { writeText("first") }
        val chosen = chosenFolder.resolve("$needle.txt").apply { writeText("chosen") }
        try {
            val browser = browserModel()
            compose.runOnIdle { browser.openLocalRoot(firstFolder.canonicalPath) }
            compose.setContent { MaterialTheme { AppNavigation(browser, true, onRequestAllFilesAccess = {}) } }
            compose.waitUntil(10_000) { browser.browseState.value.currentPath == firstFolder.canonicalPath }
            val originalSession = browser.activeSession.value?.id
            compose.onNodeWithContentDescription("Search shared storage").performClick()
            compose.onNodeWithTag("storage-search-query").performTextInput(needle)
            compose.onNodeWithTag("storage-search-query").performImeAction()
            val chosenPath = chosen.canonicalPath
            compose.waitUntil(45_000) { compose.onAllNodesWithText("2 results").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("storage-search-results").performScrollToNode(hasContentDescription("Open folder for $chosenPath"))
            compose.onNodeWithContentDescription("Open folder for $chosenPath").performClick()
            compose.waitUntil(10_000) {
                browser.browseState.value.currentPath == chosenFolder.canonicalPath && !browser.browseState.value.isLoading
            }
            compose.onNodeWithText("$needle.txt").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(FileSource.LOCAL, browser.browseState.value.source)
                assertEquals(chosenFolder.canonicalPath, browser.activeSession.value?.rootPath)
                assertNotEquals(originalSession, browser.activeSession.value?.id)
            }
            assertEquals("first", first.readText())
            assertEquals("chosen", chosen.readText())
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("storage-search-query").assertIsDisplayed()
        } finally {
            fixture.deleteRecursively()
        }
    }

    private fun searchModel(reads: AtomicInteger = AtomicInteger()): StorageSearchViewModel {
        lateinit var model: StorageSearchViewModel
        compose.runOnIdle {
            model = StorageSearchViewModel(application,
                volumeProvider = { reads.incrementAndGet(); listOf(StorageVolumeInfo("Test storage", root.path, true, false, "mounted")) },
                permissionGranted = { true },
            )
            store.put("search", model)
        }
        return model
    }

    private fun browserModel(): FileBrowserViewModel {
        lateinit var model: FileBrowserViewModel
        compose.runOnIdle { model = FileBrowserViewModel(application); store.put("browser", model) }
        return model
    }

    private fun showSearch(model: StorageSearchViewModel, onOpenFolder: (String) -> Unit) {
        compose.setContent {
            MaterialTheme { StorageSearchScreen(false, true, {}, {}, onOpenFolder, model) }
        }
    }

    private fun awaitResults(model: StorageSearchViewModel) {
        compose.waitUntil(10_000) { model.state.value.result.status == CategoryScanStatus.COMPLETE }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 results").fetchSemanticsNodes().isNotEmpty() }
    }
}
