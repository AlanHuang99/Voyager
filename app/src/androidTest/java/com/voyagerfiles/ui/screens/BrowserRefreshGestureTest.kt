package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.RemoteFileProviderFactory
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Date
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowserRefreshGestureTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val root = File(app.cacheDir, "refresh-${UUID.randomUUID()}").apply { mkdirs() }
    private lateinit var viewModel: FileBrowserViewModel

    @After fun cleanup() {
        if (::viewModel.isInitialized) compose.runOnIdle { viewModel.setViewMode(ViewMode.LIST) }
        root.deleteRecursively()
    }

    @Test fun listPullRefreshesAndPreservesExistingSelection() = verifyRefresh(ViewMode.LIST)
    @Test fun gridPullRefreshesAndPreservesExistingSelection() = verifyRefresh(ViewMode.GRID)

    @Test fun emptyFolderCanBePulledToRefresh() {
        viewModel = FileBrowserViewModel(app)
        showBrowser()
        compose.runOnIdle { viewModel.openLocalRoot(root.path) }
        compose.waitUntil(5_000) { !viewModel.browseState.value.isLoading }
        compose.onNodeWithText("Empty folder").assertExists()
        root.resolve("new.txt").writeText("new")
        pull("Empty folder")
        compose.waitUntil(5_000) { viewModel.browseState.value.files.any { it.name == "new.txt" } }
    }

    @Test fun failedListingCanBePulledToRetry() {
        val provider = ControlledProvider("recovered.txt").apply { failure = true }
        viewModel = FileBrowserViewModel(app, remoteProviderFactory = RemoteFileProviderFactory { _, _ -> provider })
        showBrowser()
        compose.runOnIdle { viewModel.connectToRemote(connection(1)) }
        compose.waitUntil(5_000) { viewModel.browseState.value.error != null }
        provider.failure = false
        pull("Could not load files")
        compose.waitUntil(5_000) {
            !viewModel.browseState.value.isLoading && viewModel.browseState.value.files.any { it.name == "recovered.txt" }
        }
        assertTrue(viewModel.browseState.value.error == null)
    }

    @Test fun switchingSessionsDuringRefreshHidesPreviousServersRows() {
        val first = ControlledProvider("first.txt")
        val second = ControlledProvider("second.txt")
        viewModel = FileBrowserViewModel(app, remoteProviderFactory = RemoteFileProviderFactory { _, connection ->
            if (connection.id == 1L) first else second
        })
        showBrowser()
        compose.runOnIdle { viewModel.connectToRemote(connection(1)) }
        compose.waitUntil(5_000) { viewModel.browseState.value.files.any { it.name == "first.txt" } }
        first.gate = CompletableDeferred()
        second.gate = CompletableDeferred()
        try {
            pull("first.txt")
            compose.waitUntil(5_000) { viewModel.browseState.value.isLoading }
            compose.runOnIdle { viewModel.connectToRemote(connection(2)) }
            compose.waitUntil(5_000) { viewModel.activeSession.value?.connectionId == 2L }
            compose.onNodeWithText("first.txt").assertDoesNotExist()
            second.gate!!.complete(Unit)
            compose.waitUntil(5_000) { viewModel.browseState.value.files.any { it.name == "second.txt" } }
            first.gate!!.complete(Unit)
            compose.waitForIdle()
            compose.onNodeWithText("first.txt").assertDoesNotExist()
        } finally {
            first.gate?.complete(Unit)
            second.gate?.complete(Unit)
        }
    }

    private fun showBrowser() {
        compose.setContent {
            MaterialTheme { BrowserScreen(viewModel, onNavigateBack = {}, isTelevision = false) }
        }
        compose.runOnIdle { viewModel.setViewMode(ViewMode.LIST) }
    }

    private fun pull(text: String) {
        compose.onNodeWithText(text).performTouchInput {
            swipe(start = center, end = center + Offset(0f, 600f), durationMillis = 400)
        }
    }

    private fun connection(id: Long) = RemoteConnection(
        id = id, name = "Refresh $id", protocol = ConnectionProtocol.SFTP, host = "refresh-$id.test", port = 22,
    )

    private class ControlledProvider(name: String) : FileProvider by LocalFileProvider() {
        private val file = FileItem(name, "/$name", false, 1, Date(0), source = FileSource.SFTP)
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun listFiles(path: String): Result<List<FileItem>> {
            gate?.await()
            return if (failure) Result.failure(IllegalStateException("Unavailable")) else Result.success(listOf(file))
        }
        override fun getParentPath(path: String): String? = null
    }

    private fun verifyRefresh(mode: ViewMode) {
        val selected = root.resolve("keep.txt").apply { writeText("keep") }
        viewModel = FileBrowserViewModel(app)
        compose.setContent {
            MaterialTheme { BrowserScreen(viewModel, onNavigateBack = {}, isTelevision = false) }
        }
        compose.runOnIdle {
            viewModel.setViewMode(mode)
            viewModel.openLocalRoot(root.path)
        }
        compose.waitUntil(10_000) {
            val state = viewModel.browseState.value
            !state.isLoading && state.files.any { it.name == selected.name } && state.viewMode == mode
        }
        compose.onNode(hasText(selected.name) and hasClickAction()).performTouchInput { longClick() }
        root.resolve("new.txt").writeText("new")
        compose.onNodeWithText(selected.name).performTouchInput {
            swipe(start = center, end = center + Offset(0f, 600f), durationMillis = 400)
        }
        compose.waitUntil(5_000) { viewModel.browseState.value.files.any { it.name == "new.txt" } }
        assertTrue(selected.path in viewModel.browseState.value.selectedFiles)
        compose.onNodeWithText("new.txt").assertExists()
    }
}
