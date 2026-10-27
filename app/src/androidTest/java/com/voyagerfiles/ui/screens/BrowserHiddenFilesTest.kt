package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.OperationOutcome
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class BrowserHiddenFilesTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val store = ViewModelStore()
    private lateinit var root: File
    private lateinit var preferences: PreferencesManager
    private var previousHidden = false
    private var previousView = ViewMode.LIST

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        preferences = PreferencesManager(app)
        runBlocking {
            previousHidden = preferences.showHidden.first()
            previousView = preferences.viewMode.first()
            preferences.setShowHidden(false)
            preferences.setViewMode(ViewMode.LIST)
        }
        root = File(app.cacheDir, "hidden-files-${System.nanoTime()}").apply {
            mkdirs()
            resolve("notes.txt").writeBytes(byteArrayOf(0, 1, 2, 127, -1))
            resolve("Folder").mkdirs()
            resolve("Folder/child.txt").writeText("folder contents")
        }
    }

    @After fun cleanup() {
        compose.runOnIdle { store.clear() }
        root.deleteRecursively()
        runBlocking {
            preferences.setShowHidden(previousHidden)
            preferences.setViewMode(previousView)
        }
    }

    @Test fun fileCanBeHiddenAndUnhiddenThroughItsRowMenu() {
        val model = launch()
        menu("notes.txt", "Hide")
        compose.waitUntil(10_000) {
            root.resolve(".notes.txt").exists() &&
                model.browseState.value.files.none { it.name == "notes.txt" || it.name == ".notes.txt" }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Show hidden files").performClick()
        waitForName(model, ".notes.txt")
        menu(".notes.txt", "Unhide")
        waitForName(model, "notes.txt")
        assertFalse(root.resolve(".notes.txt").exists())
        assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), root.resolve("notes.txt").readBytes())
    }

    @Test fun folderRoundTripPreservesItsChildren() {
        val model = launch()
        menu("Folder", "Hide")
        compose.waitUntil(10_000) { root.resolve(".Folder/child.txt").exists() }
        compose.runOnIdle { model.setShowHidden(true) }
        waitForName(model, ".Folder")
        menu(".Folder", "Unhide")
        waitForName(model, "Folder")
        assertEquals("folder contents", root.resolve("Folder/child.txt").readText())
        assertFalse(root.resolve(".Folder").exists())
    }

    @Test fun gridSelectionOffersHideAndUnhide() {
        val model = launch()
        compose.runOnIdle { model.setViewMode(ViewMode.GRID) }
        compose.waitUntil(10_000) { model.browseState.value.viewMode == ViewMode.GRID }
        select("notes.txt")
        compose.onNodeWithContentDescription("More selection actions").performClick()
        compose.onNodeWithText("Hide").performClick()
        compose.waitUntil(10_000) {
            root.resolve(".notes.txt").exists() && model.browseState.value.selectedFiles.isEmpty()
        }
        compose.runOnIdle { model.setShowHidden(true) }
        waitForName(model, ".notes.txt")
        select(".notes.txt")
        compose.onNodeWithContentDescription("More selection actions").performClick()
        compose.onNodeWithText("Unhide").performClick()
        waitForName(model, "notes.txt")
        assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), root.resolve("notes.txt").readBytes())
    }

    @Test fun hideAndUnhideNeverOverwriteExistingNames() {
        root.resolve(".notes.txt").writeText("existing hidden file")
        val model = launch()
        val beforeHide = model.lastOperationResult.value
        menu("notes.txt", "Hide")
        compose.waitUntil(10_000) {
            model.lastOperationResult.value !== beforeHide && model.lastOperationResult.value?.outcome == OperationOutcome.FAILED
        }
        assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), root.resolve("notes.txt").readBytes())
        assertEquals("existing hidden file", root.resolve(".notes.txt").readText())
        compose.runOnIdle { model.setShowHidden(true) }
        waitForName(model, ".notes.txt")
        val beforeUnhide = model.lastOperationResult.value
        menu(".notes.txt", "Unhide")
        compose.waitUntil(10_000) {
            model.lastOperationResult.value !== beforeUnhide && model.lastOperationResult.value?.outcome == OperationOutcome.FAILED
        }
        assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), root.resolve("notes.txt").readBytes())
        assertEquals("existing hidden file", root.resolve(".notes.txt").readText())
    }

    private fun menu(name: String, action: String) {
        compose.onNodeWithContentDescription("More options for $name").performClick()
        compose.onNodeWithText(action).performScrollTo().performClick()
    }

    private fun select(name: String) {
        compose.onNode(hasText(name) and hasClickAction()).performTouchInput { longClick() }
    }

    private fun waitForName(model: FileBrowserViewModel, name: String) {
        compose.waitUntil(10_000) {
            !model.browseState.value.isLoading &&
                model.browseState.value.files.any { it.name == name } &&
                compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun launch(): FileBrowserViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = FileBrowserViewModel(app)
        store.put("browser", model)
        compose.setContent { MaterialTheme { BrowserScreen(model, onNavigateBack = {}) } }
        compose.runOnIdle { model.openLocalRoot(root.path) }
        waitForName(model, "notes.txt")
        return model
    }
}
