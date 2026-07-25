package com.voyagerfiles.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PdfThumbnailLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        PdfThumbnailLoader.clear()
    }

    @After
    fun tearDown() {
        PdfThumbnailLoader.clear()
        testFiles.forEach(File::delete)
    }

    @Test
    fun rendersFirstPageWithinRequestedBounds() = runBlocking {
        val pdf = createPdf("render.pdf")
        val file = pdf.asFileItem()

        val bitmap = PdfThumbnailLoader.load(context, file, maxWidth = 96, maxHeight = 64)
            .getOrThrow()

        assertTrue(bitmap.width in 1..96)
        assertTrue(bitmap.height in 1..64)
        assertNotEquals(Color.WHITE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        assertEquals(Color.WHITE, bitmap.getPixel(1, 1))
    }

    @Test
    fun cachesByFileVersionAndBounds() = runBlocking {
        val file = createPdf("cached.pdf").asFileItem()

        val first = PdfThumbnailLoader.load(context, file, 80, 80).getOrThrow()
        val second = PdfThumbnailLoader.load(context, file, 80, 80).getOrThrow()

        assertSame(first, second)
    }

    @Test
    fun rejectsMalformedAndPageLessDocuments() = runBlocking {
        val malformed = newFile("malformed.pdf").apply { writeText("not a PDF") }
        val pageLess = newFile("page-less.pdf")
        val document = PdfDocument()
        try {
            pageLess.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }

        assertTrue(PdfThumbnailLoader.load(context, malformed.asFileItem(), 64, 64).isFailure)
        assertTrue(PdfThumbnailLoader.load(context, pageLess.asFileItem(), 64, 64).isFailure)
    }

    @Test
    fun rejectsRemotePdfWithoutDownloadingIt() = runBlocking {
        val remote = FileItem(
            name = "remote.pdf",
            path = "/remote.pdf",
            isDirectory = false,
            source = FileSource.SFTP,
        )

        val result = PdfThumbnailLoader.load(context, remote, 64, 64)

        assertTrue(result.isFailure)
    }

    @Test
    fun rendersPdfThroughContentResolverForSafSource() = runBlocking {
        val pdf = createPdf("saf.pdf")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdf,
        )
        val safFile = pdf.asFileItem().copy(
            path = uri.toString(),
            source = FileSource.SAF,
        )

        val bitmap = PdfThumbnailLoader.load(context, safFile, 72, 72).getOrThrow()

        assertTrue(bitmap.width in 1..72)
        assertTrue(bitmap.height in 1..72)
        assertNotEquals(Color.WHITE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    private fun createPdf(name: String): File {
        val file = newFile(name)
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(200, 200, 1).create()
            val page = document.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawRect(
                50f,
                50f,
                150f,
                150f,
                Paint().apply { color = Color.BLACK },
            )
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun newFile(name: String): File =
        context.cacheDir.resolve("pdf-thumbnail-${System.nanoTime()}-$name")
            .also(testFiles::add)

    private fun File.asFileItem(): FileItem = FileItem(
        name = name,
        path = absolutePath,
        isDirectory = false,
        size = length(),
        lastModified = java.util.Date(lastModified()),
        source = FileSource.LOCAL,
    )
}
