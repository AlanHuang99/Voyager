package com.voyagerfiles.ui.components

import android.content.Context
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.FileSource
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MediaThumbnailLoaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "media-previews-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun cleanup() {
        MediaThumbnailLoader.clear()
        directory.deleteRecursively()
    }

    @Test
    fun decodesMp4AndWebmWithinBounds() = runBlocking {
        for (extension in listOf("mp4", "webm")) {
            val item = previewVideo(directory, extension).previewItem()
            val bitmap = MediaThumbnailLoader.load(context, item, 64, 40).getOrThrow()
            assertTrue(bitmap.width in 1..64 && bitmap.height in 1..40)
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue(Color.red(pixel) > 200 && Color.green(pixel) < 30)
        }
    }

    @Test
    fun decodesVideoAndOfficeThroughDocumentUris() = runBlocking {
        for (file in listOf(previewVideo(directory), previewOffice(directory))) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val item = file.previewItem().copy(source = FileSource.SAF, path = uri.toString())
            val bitmap = MediaThumbnailLoader.load(context, item, 40, 40).getOrThrow()
            assertTrue(bitmap.width in 1..40 && bitmap.height in 1..40)
        }
        assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("office-preview-") })
    }

    @Test
    fun decodesEmbeddedImageInEachOfficeFormat() = runBlocking {
        for (extension in listOf("docx", "pptx", "xlsx")) {
            val useJpeg = extension == "pptx"
            val format = if (useJpeg) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG
            val item = previewOffice(directory, extension, previewImage(format), if (useJpeg) "docProps/thumbnail.jpeg" else "docProps/thumbnail.png").previewItem()
            val bitmap = MediaThumbnailLoader.load(context, item, 64, 64).getOrThrow()
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue(Color.red(pixel) > 240 && Color.green(pixel) < 10 && Color.blue(pixel) < 10)
            assertEquals(64, bitmap.width)
            assertEquals(36, bitmap.height)
        }
    }

    @Test
    fun capsUnreasonableRequestedDimensions() = runBlocking {
        val item = previewVideo(directory).previewItem()
        val bitmap = MediaThumbnailLoader.load(context, item, Int.MAX_VALUE, Int.MAX_VALUE).getOrThrow()
        assertTrue(bitmap.width <= 512 && bitmap.height <= 512)
        assertTrue(MediaThumbnailLoader.load(context, item, 0, 40).isFailure)
    }

    @Test
    fun rejectsMalformedMissingAndUnsupportedEmbeddedPreviews() = runBlocking {
        val files = listOf(
            previewOffice(directory, thumbnail = null),
            previewOffice(directory, thumbnail = "not an image".toByteArray()),
            previewOffice(directory, target = "docProps/thumbnail.wmf"),
            previewOffice(directory, relationTarget = "../thumbnail.png"),
            previewOffice(directory, external = true),
            File(directory, "malformed.docx").apply { writeText("not a zip") },
            File(directory, "malformed.mp4").apply { writeText("not a video") },
            File(directory, "missing.mp4"),
        )
        for (file in files) assertTrue(file.name, MediaThumbnailLoader.load(context, file.previewItem(), 40, 40).isFailure)
    }

    @Test
    fun rejectsOversizedArchiveAndCompressedImageBomb() = runBlocking {
        val imageBomb = previewOffice(directory, thumbnail = ByteArray(MediaThumbnailLoader.MAX_IMAGE_BYTES + 1))
        val archive = File(directory, "oversized.docx")
        RandomAccessFile(archive, "rw").use { it.setLength(MediaThumbnailLoader.MAX_ARCHIVE_BYTES + 1) }
        for (file in listOf(imageBomb, archive)) {
            assertTrue(MediaThumbnailLoader.load(context, file.previewItem(), 40, 40).isFailure)
        }
    }

    @Test
    fun rejectsExcessiveImageDimensionsAndDisguisedImageFormats() = runBlocking {
        val dimensions = previewImage().copyOf()
        java.nio.ByteBuffer.wrap(dimensions, 16, 8).putInt(5000).putInt(4000)
        val crc = java.util.zip.CRC32().apply { update(dimensions, 12, 17) }
        java.nio.ByteBuffer.wrap(dimensions, 29, 4).putInt(crc.value.toInt())
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        for (image in listOf(dimensions, gif)) {
            val file = previewOffice(directory, thumbnail = image)
            assertTrue(MediaThumbnailLoader.load(context, file.previewItem(), 40, 40).isFailure)
        }
    }

    @Test
    fun rejectsUnsupportedFilesAndRemoteSourcesEvenWithReadableLocalPath() = runBlocking {
        val office = previewOffice(directory).previewItem()
        for (source in listOf(FileSource.SFTP, FileSource.FTP, FileSource.SMB, FileSource.WEBDAV)) {
            assertTrue(MediaThumbnailLoader.load(context, office.copy(source = source), 40, 40).isFailure)
        }
        assertTrue(MediaThumbnailLoader.load(context, office.copy(source = FileSource.SAF, path = "https://example.invalid/file.docx"), 40, 40).isFailure)
        assertTrue(MediaThumbnailLoader.load(context, office.copy(name = "old.doc"), 40, 40).isFailure)
        assertTrue(MediaThumbnailLoader.load(context, office.copy(isDirectory = true), 40, 40).isFailure)
    }

    @Test
    fun cachesBySizeAndVersionAndHonorsInvalidationAndDeletedPayload() = runBlocking {
        val file = previewOffice(directory)
        val item = file.previewItem()
        val first = MediaThumbnailLoader.load(context, item, 40, 40).getOrThrow()
        assertSame(first, MediaThumbnailLoader.load(context, item, 40, 40).getOrThrow())
        assertNotSame(first, MediaThumbnailLoader.load(context, item, 60, 60).getOrThrow())
        MediaThumbnailLoader.invalidate(file.path)
        val second = MediaThumbnailLoader.load(context, item, 40, 40).getOrThrow()
        assertNotSame(first, second)
        assertTrue(file.setLastModified(file.lastModified() + 2000))
        assertNotSame(second, MediaThumbnailLoader.load(context, item, 40, 40).getOrThrow())
        assertTrue(file.delete())
        assertTrue(MediaThumbnailLoader.load(context, item, 40, 40).isFailure)
    }
}
