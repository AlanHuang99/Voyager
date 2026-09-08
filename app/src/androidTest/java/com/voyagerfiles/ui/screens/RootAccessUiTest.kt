package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.RootFileProvider
import com.voyagerfiles.data.repository.RootShell
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RootAccessUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rootRequiresConfirmationAndDenialDoesNotAffectLocalBrowsing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var rootRequests = 0
        val model = FileBrowserViewModel(app, rootProviderFactory = {
            rootRequests++
            RootFileProvider(RootShell(startProcess = { ProcessBuilder("sh", "-c", "echo 'Root access denied' >&2; exit 1").start() }))
        })
        compose.setContent { AppNavigation(model, hasAllFilesAccess = true, onRequestAllFilesAccess = {}) }
        compose.onNodeWithContentDescription(app.getString(R.string.content_desc_settings)).performClick()
        compose.onNodeWithText(app.getString(R.string.root_open)).performScrollTo().performClick()
        assertEquals(0, rootRequests)
        compose.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
        assertEquals(0, rootRequests)
        compose.onNodeWithText(app.getString(R.string.root_open)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.root_warning)).assertExists()
        // The confirmation dialog and underlying Settings button have the same label.
        compose.onNode(hasText(app.getString(R.string.root_open)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { model.browseState.value.source == FileSource.ROOT && model.browseState.value.error != null }
        assertEquals(1, rootRequests)
        compose.runOnIdle { model.openLocalRoot(app.cacheDir.path) }
        compose.waitUntil(10_000) { model.browseState.value.source == FileSource.LOCAL && !model.browseState.value.isLoading }
        assertNull(model.browseState.value.error)
        assertEquals(1, rootRequests)
    }

    @Test fun explicitSaveEditsRootFileAndDiscardRetainsOriginal() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.cacheDir, "root-editor-${UUID.randomUUID()}").apply { mkdir() }
        val file = File(directory, "config.txt").apply { writeText("original") }
        val model = FileBrowserViewModel(app, rootProviderFactory = {
            RootFileProvider(RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false))
        })
        compose.setContent { BrowserScreen(model, onNavigateBack = {}) }
        try {
            compose.runOnIdle { model.openRootSession() }
            compose.waitUntil(10_000) { model.browseState.value.source == FileSource.ROOT && !model.browseState.value.isLoading }
            compose.runOnIdle { model.navigateTo(directory.path) }
            compose.waitUntil(10_000) { model.browseState.value.currentPath == directory.path && !model.browseState.value.isLoading }
            compose.onNodeWithText(file.name).performClick()
            compose.waitUntil(10_000) { model.rootEditor.value?.document != null }
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("saved")
            assertEquals("original", file.readText())
            compose.onNodeWithText(app.getString(R.string.root_editor_save)).performClick()
            compose.waitUntil(10_000) { model.rootEditor.value == null }
            assertEquals("saved", file.readText())
            compose.waitUntil(10_000) { compose.onAllNodesWithText(file.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(file.name).performClick()
            compose.waitUntil(10_000) { model.rootEditor.value?.document != null }
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("discard me")
            compose.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
            compose.onNodeWithText(app.getString(R.string.action_discard)).performClick()
            compose.waitUntil(10_000) { model.rootEditor.value == null }
            assertEquals("saved", file.readText())
            compose.runOnIdle { model.toggleSelection(file.path) }
            compose.onNodeWithContentDescription(app.getString(R.string.action_delete)).performClick()
            compose.onNodeWithText(app.getString(R.string.dialog_delete_permanently)).assertExists()
            compose.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
            assertTrue(file.exists())
            compose.onNodeWithContentDescription(app.getString(R.string.action_delete)).performClick()
            compose.onNodeWithText(app.getString(R.string.action_delete)).performClick()
            compose.waitUntil(10_000) { !file.exists() }
        } finally {
            compose.runOnIdle { model.closeActiveSession() }
            directory.deleteRecursively()
        }
    }
}
