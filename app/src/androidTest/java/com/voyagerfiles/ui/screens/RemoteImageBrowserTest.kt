package com.voyagerfiles.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.ui.components.REMOTE_IMAGE_THUMBNAIL_TEST_TAG
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RemoteImageBrowserTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun authenticatedRemoteImagesRenderInListGridAndCompactWithoutOpeningLargeFiles() {
        val payload = imageBytes()
        val reads = CopyOnWriteArrayList<String>()
        val authorization = Credentials.basic("preview", "fixture")
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != authorization) {
                        return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=preview")
                    }
                    return when (request.method) {
                        "PROPFIND" -> MockResponse().setResponseCode(207)
                            .setHeader("Content-Type", "application/xml").setBody(listing(payload.size))
                        "GET" -> {
                            reads += request.path.orEmpty()
                            MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(payload))
                        }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
            start()
        }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        var originalMode: ViewMode? = null
        try {
            compose.setContent { MaterialTheme { BrowserScreen(viewModel, {}, isTelevision = false) } }
            compose.runOnIdle {
                viewModel.connectToRemote(RemoteConnection(
                    id = System.nanoTime(), name = "Thumbnail fixture", protocol = ConnectionProtocol.WEBDAV,
                    host = "127.0.0.1", port = server.port, useTls = false,
                    username = "preview", password = "fixture",
                ))
            }
            compose.waitUntil(10_000) { !viewModel.browseState.value.isLoading && viewModel.browseState.value.files.size == 2 }
            originalMode = viewModel.browseState.value.viewMode
            listOf(ViewMode.LIST, ViewMode.GRID, ViewMode.COMPACT).forEach { mode ->
                compose.runOnIdle { viewModel.setViewMode(mode) }
                compose.waitUntil(10_000) {
                    viewModel.browseState.value.viewMode == mode &&
                        compose.onAllNodesWithTag(REMOTE_IMAGE_THUMBNAIL_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 1
                }
            }
            assertEquals(setOf("/photo.png"), reads.toSet())
        } finally {
            compose.runOnIdle {
                originalMode?.let(viewModel::setViewMode)
                viewModel.closeActiveSession()
            }
            compose.waitUntil(5_000) { viewModel.activeSession.value == null }
            server.shutdown()
        }
    }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun listing(size: Int) = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>/photo.png</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>$size</D:getcontentlength><D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>/large.png</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>9437184</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>
    """.trimIndent()
}
