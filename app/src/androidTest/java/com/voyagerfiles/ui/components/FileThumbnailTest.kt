package com.voyagerfiles.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

class FileThumbnailTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        PdfThumbnailLoader.clear()
        testFiles.forEach(File::delete)
    }

    @Test
    fun localImageRendersWhileThumbnailIsLoadingOrUnavailable() {
        composeTestRule.setContent {
            MaterialTheme {
                FileThumbnailOrIcon(
                    file = FileItem(
                        name = "missing-local-image.jpg",
                        path = "/storage/emulated/0/Pictures/missing-local-image.jpg",
                        isDirectory = false,
                    ),
                    iconSize = 40.dp,
                )
            }
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun validLocalPdfEventuallyRendersThumbnail() {
        val pdf = createPdf()
        composeTestRule.setContent {
            MaterialTheme {
                FileThumbnailOrIcon(
                    file = FileItem(
                        name = pdf.name,
                        path = pdf.absolutePath,
                        isDirectory = false,
                    ),
                    iconSize = 48.dp,
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(PDF_THUMBNAIL_TEST_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(PDF_THUMBNAIL_TEST_TAG).assertExists()
    }

    @Test
    fun remotePdfUsesGenericIconFallback() {
        composeTestRule.setContent {
            MaterialTheme {
                FileThumbnailOrIcon(
                    file = FileItem(
                        name = "remote.pdf",
                        path = "/remote.pdf",
                        isDirectory = false,
                        source = FileSource.SFTP,
                    ),
                    iconSize = 48.dp,
                )
            }
        }

        composeTestRule.onNodeWithTag(FILE_ICON_TEST_TAG).assertExists()
    }

    private fun createPdf(): File {
        val file = context.cacheDir.resolve("file-thumbnail-${System.nanoTime()}.pdf")
            .also(testFiles::add)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(100, 100, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawCircle(
                50f,
                50f,
                30f,
                Paint().apply { color = Color.BLUE },
            )
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }
}
