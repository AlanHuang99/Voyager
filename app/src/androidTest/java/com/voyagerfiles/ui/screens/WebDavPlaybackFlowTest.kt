package com.voyagerfiles.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.RemoteFileProviderFactory
import com.voyagerfiles.viewmodel.WebDavPlaybackPreparer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WebDavPlaybackFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val downloadedNames = mutableListOf<String>()

    @After
    fun tearDown() {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadedNames.forEach { downloads.resolve(it).delete() }
    }

    @Test
    fun successfulWebDavMediaPreparationLaunchesReadGrantedIntent() {
        val file = remoteFile("song-${System.nanoTime()}.mp3", FileSource.WEBDAV)
        val provider = FakeProvider(listOf(file))
        val launched = mutableListOf<Intent>()
        val viewModel = launch(
            protocol = ConnectionProtocol.WEBDAV,
            provider = provider,
            playbackResult = Result.success(Uri.parse("content://com.voyagerfiles.debug.webdavplayback/token")),
            launched = launched,
        )
        waitForFiles(viewModel, listOf(file.name))

        composeTestRule.onNode(hasText(file.name) and hasClickAction()).performClick()

        composeTestRule.waitUntil(5_000) { launched.size == 1 }
        assertEquals(Intent.ACTION_VIEW, launched.single().action)
        assertEquals("audio/mpeg", launched.single().type)
        assertTrue(launched.single().flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, provider.readCount)
    }

    @Test
    fun unsupportedRangesRequireExplicitDownloadChoice() {
        val file = remoteFile("fallback-${System.nanoTime()}.mp3", FileSource.WEBDAV)
        downloadedNames += file.name
        val provider = FakeProvider(listOf(file))
        val viewModel = launch(
            protocol = ConnectionProtocol.WEBDAV,
            provider = provider,
            playbackResult = Result.failure(IllegalStateException("ranges unavailable")),
        )
        waitForFiles(viewModel, listOf(file.name))

        composeTestRule.onNode(hasText(file.name) and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Direct opening unavailable").assertExists()
        assertEquals(0, provider.readCount)

        composeTestRule.onNodeWithText("Download").performClick()
        composeTestRule.waitUntil(5_000) { provider.readCount == 1 }
    }

    @Test
    fun sftpMediaDownloadsImmediately() {
        val sftpAudio = remoteFile("sftp-${System.nanoTime()}.mp3", FileSource.SFTP)
        downloadedNames += sftpAudio.name
        val sftpProvider = FakeProvider(listOf(sftpAudio))
        val sftpViewModel = launch(ConnectionProtocol.SFTP, sftpProvider, Result.failure(AssertionError("unused")))
        waitForFiles(sftpViewModel, listOf(sftpAudio.name))
        composeTestRule.onNode(hasText(sftpAudio.name) and hasClickAction()).performClick()
        composeTestRule.waitUntil(5_000) { sftpProvider.readCount == 1 }
    }

    @Test
    fun webDavDocumentOpensWithoutDownloading() {
        val webDavText = remoteFile("webdav-${System.nanoTime()}.pdf", FileSource.WEBDAV)
        val webDavProvider = FakeProvider(listOf(webDavText))
        val launched = mutableListOf<Intent>()
        val uri = Uri.parse("content://com.voyagerfiles.debug.webdavplayback/document")
        val webDavViewModel = launch(ConnectionProtocol.WEBDAV, webDavProvider, Result.success(uri), launched)
        waitForFiles(webDavViewModel, listOf(webDavText.name))
        composeTestRule.onNode(hasText(webDavText.name) and hasClickAction()).performClick()
        composeTestRule.waitUntil(5_000) { launched.size == 1 }
        assertEquals("application/pdf", launched.single().type)
        assertEquals(uri, launched.single().data)
        assertEquals(0, webDavProvider.readCount)
        assertEquals(0, launched.single().flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertTrue(!downloads.resolve(webDavText.name).exists())
    }

    @Test
    fun webDavDocumentOpenWithLaunchesAReadOnlyChooser() {
        val file = remoteFile("choose-${System.nanoTime()}.pdf", FileSource.WEBDAV)
        val provider = FakeProvider(listOf(file))
        val launched = mutableListOf<Intent>()
        val uri = Uri.parse("content://com.voyagerfiles.debug.webdavplayback/chooser")
        val viewModel = launch(ConnectionProtocol.WEBDAV, provider, Result.success(uri), launched)
        waitForFiles(viewModel, listOf(file.name))
        composeTestRule.onNode(hasText(file.name) and hasClickAction())
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("More selection actions").performClick()
        composeTestRule.onNodeWithText("Open with").performClick()
        composeTestRule.waitUntil(5_000) { launched.size == 1 }
        val chooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals("application/pdf", target.type)
        assertEquals(uri, target.data)
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, target.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, provider.readCount)
    }

    private fun launch(
        protocol: ConnectionProtocol,
        provider: FakeProvider,
        playbackResult: Result<Uri>,
        launched: MutableList<Intent> = mutableListOf(),
    ): FileBrowserViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(
            application = application,
            remoteProviderFactory = RemoteFileProviderFactory { _, _ -> provider },
            playbackPreparer = WebDavPlaybackPreparer { _, _, _ -> playbackResult },
        )
        composeTestRule.setContent {
            MaterialTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    isTelevision = false,
                    launchPlaybackIntent = { intent -> launched.add(intent) },
                )
            }
        }
        composeTestRule.runOnIdle {
            viewModel.connectToRemote(
                RemoteConnection(
                    id = System.nanoTime(),
                    name = "Playback test",
                    protocol = protocol,
                    host = "example.test",
                    port = protocol.defaultPort,
                ),
            )
        }
        return viewModel
    }

    private fun waitForFiles(viewModel: FileBrowserViewModel, names: List<String>) {
        composeTestRule.waitUntil(10_000) {
            val state = viewModel.browseState.value
            !state.isLoading && state.files.map { it.name } == names
        }
    }

    private fun remoteFile(name: String, source: FileSource) = FileItem(
        name = name,
        path = "/$name",
        isDirectory = false,
        size = 3,
        lastModified = Date(0),
        source = source,
    )

    private class FakeProvider(
        private val files: List<FileItem>,
    ) : FileProvider {
        var readCount = 0

        override suspend fun listFiles(path: String) = Result.success(files)
        override suspend fun getFileInfo(path: String) = Result.success(files.first { it.path == path })
        override suspend fun getInputStream(path: String): Result<InputStream> {
            readCount += 1
            return Result.success(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        override suspend fun exists(path: String) = files.any { it.path == path }
        override fun getParentPath(path: String): String? = if (path == "/") null else "/"
        override suspend fun createDirectory(path: String, name: String) = unsupported<FileItem>()
        override suspend fun createFile(path: String, name: String) = unsupported<FileItem>()
        override suspend fun delete(path: String) = unsupported<Unit>()
        override suspend fun rename(oldPath: String, newName: String) = unsupported<FileItem>()
        override suspend fun copy(sourcePath: String, destPath: String) = unsupported<Unit>()
        override suspend fun move(sourcePath: String, destPath: String) = unsupported<Unit>()
        override suspend fun getOutputStream(path: String) = unsupported<OutputStream>()

        private fun <T> unsupported(): Result<T> = Result.failure(UnsupportedOperationException())
    }
}
