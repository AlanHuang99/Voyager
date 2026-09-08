package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.data.repository.RootFileProvider
import com.voyagerfiles.data.repository.RootShell
import com.voyagerfiles.data.repository.StreamTransferProgress
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RootEditorOperationGateTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun pendingMoveRejectsSaveAndRetainsDraftWhileMovingOnlyOriginalBytes() {
        val directory = File(app.cacheDir, "root-save-move-${UUID.randomUUID()}").apply { mkdir() }
        val source = File(directory, "source.txt").apply { writeText("original bytes") }
        val destination = File(directory, "remote").apply { mkdir() }
        val sourceRead = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val backing = LocalFileProvider()
        val remote = object : FileProvider by backing {
            override suspend fun writeStream(path: String, input: InputStream, sourcePath: String, totalBytes: Long?,
                onProgress: (StreamTransferProgress) -> Unit): Result<Unit> = runCatching {
                val original = withContext(Dispatchers.IO) { input.readBytes() }
                sourceRead.complete(Unit)
                releaseUpload.await()
                backing.writeStream(path, original.inputStream(), sourcePath, totalBytes, onProgress).getOrThrow()
            }
        }
        val operations = TransferOperationController(startForeground = {})
        val model = FileBrowserViewModel(app,
            remoteProviderFactory = RemoteFileProviderFactory { _, _ -> remote },
            rootProviderFactory = ::rootProvider,
            operationController = operations)
        try {
            openRootFolder(model, directory)
            compose.runOnIdle {
                model.cutToClipboard(listOf(source.path))
                model.connectToRemote(RemoteConnection(id = -867, name = "Slow remote fixture", protocol = ConnectionProtocol.WEBDAV,
                    host = "fixture.invalid", port = 443, remotePath = destination.path))
            }
            compose.waitUntil(10_000) { model.browseState.value.source == FileSource.WEBDAV && !model.browseState.value.isLoading }
            compose.runOnIdle { model.paste() }
            compose.waitUntil(10_000) { sourceRead.isCompleted }
            val moveId = (operations.state.value as OperationState.Running).id
            compose.runOnIdle { model.activateSession("root:/") }
            compose.waitUntil(10_000) { model.browseState.value.source == FileSource.ROOT && !model.browseState.value.isLoading }
            openEditor(model, source)
            compose.runOnIdle { model.updateRootText("unsaved new bytes"); model.saveRootText() }
            compose.waitUntil(10_000) { model.rootEditor.value?.busy != true }
            assertEquals("original bytes", source.readText())
            assertEquals("unsaved new bytes", model.rootEditor.value!!.text)
            assertNotNull(model.rootEditor.value!!.error)
            assertEquals(moveId, (operations.state.value as OperationState.Running).id)
            releaseUpload.complete(Unit)
            compose.waitUntil(10_000) { operations.state.value == OperationState.Idle }
            assertEquals(OperationOutcome.COMPLETED, operations.lastResult.value!!.outcome)
            assertFalse(source.exists())
            assertEquals("original bytes", File(destination, source.name).readText())
            assertEquals("unsaved new bytes", model.rootEditor.value!!.text)
            assertFalse(model.rootEditor.value!!.busy)
        } finally {
            releaseUpload.complete(Unit)
            compose.waitUntil(10_000) { operations.state.value == OperationState.Idle }
            compose.runOnIdle { model.closeRootTextEditor(); model.closeActiveSession() }
            directory.deleteRecursively()
        }
    }

    @Test fun serviceStartFailureRetainsDraftAndOriginalAndAllowsSuccessfulRetry() {
        val directory = File(app.cacheDir, "root-save-start-${UUID.randomUUID()}").apply { mkdir() }
        val source = File(directory, "source.txt").apply { writeText("original bytes") }
        var allowStart = false
        val operations = TransferOperationController(startForeground = { check(allowStart) { "Foreground start failed" } })
        val model = FileBrowserViewModel(app, rootProviderFactory = ::rootProvider, operationController = operations)
        try {
            openRootFolder(model, directory)
            openEditor(model, source)
            compose.runOnIdle { model.updateRootText("saved on retry"); model.saveRootText() }
            compose.waitUntil(10_000) { model.rootEditor.value?.busy != true }
            assertEquals("original bytes", source.readText())
            assertEquals("saved on retry", model.rootEditor.value!!.text)
            assertTrue(model.rootEditor.value!!.error!!.contains("Foreground start failed"))
            assertEquals(OperationState.Idle, operations.state.value)
            assertEquals(OperationOutcome.FAILED, operations.lastResult.value!!.outcome)
            compose.runOnIdle { allowStart = true; model.saveRootText() }
            compose.waitUntil(10_000) { model.rootEditor.value == null && operations.state.value == OperationState.Idle }
            assertEquals("saved on retry", source.readText())
            assertEquals(OperationOutcome.COMPLETED, operations.lastResult.value!!.outcome)
            assertEquals(1, operations.lastResult.value!!.progress.completedItems)
            assertEquals(listOf("source.txt"), directory.list()!!.toList())
        } finally {
            compose.runOnIdle { model.closeRootTextEditor(); model.closeActiveSession() }
            directory.deleteRecursively()
        }
    }

    private fun rootProvider() = RootFileProvider(RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false))

    private fun openRootFolder(model: FileBrowserViewModel, directory: File) {
        compose.runOnIdle { model.openRootSession() }
        compose.waitUntil(10_000) { model.browseState.value.source == FileSource.ROOT && !model.browseState.value.isLoading }
        compose.runOnIdle { model.navigateTo(directory.path) }
        compose.waitUntil(10_000) { model.browseState.value.currentPath == directory.path && !model.browseState.value.isLoading }
    }

    private fun openEditor(model: FileBrowserViewModel, file: File) {
        compose.runOnIdle { model.openRootTextEditor(model.browseState.value.files.single { it.path == file.path }) }
        compose.waitUntil(10_000) { model.rootEditor.value?.document != null }
    }
}
