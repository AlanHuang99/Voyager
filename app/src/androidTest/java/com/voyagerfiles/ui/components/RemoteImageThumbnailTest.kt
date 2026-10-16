package com.voyagerfiles.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.data.repository.TransferAbortable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteImageThumbnailTest {
    @get:Rule
    val compose = createComposeRule()
    private val sessions = mutableListOf<RemoteImageThumbnailSession>()

    @After
    fun tearDown() { sessions.forEach(RemoteImageThumbnailSession::close) }

    @Test
    fun samePathsFromDifferentSessionsUseDifferentImagesAndCaches() = runBlocking {
        val firstReads = AtomicInteger()
        val first = session(imageBytes(Color.RED), firstReads)
        val second = session(imageBytes(Color.BLUE))
        val file = image()
        val red = first.load(file, 40)!!
        val blue = second.load(file, 40)!!

        assertEquals(Color.RED, red.getPixel(0, 0))
        assertEquals(Color.BLUE, blue.getPixel(0, 0))
        assertSame(red, first.load(file, 40))
        assertNull(first.load(file.copy(name = "movie.mp4"), 40))
        assertNull(first.load(file.copy(source = FileSource.LOCAL), 40))
        assertEquals(1, firstReads.get())
        assertNotSame(red, first.load(file.copy(lastModified = Date(2_000)), 40))
        first.close()
        assertNull(first.load(file, 40))
        assertSame(blue, second.load(file, 40))
    }

    @Test
    fun samplesToRequestedBoundsAndCapsDecodedAllocation() = runBlocking {
        val session = session(imageBytes(Color.GREEN, 2048, 1024))
        val bitmap = session.load(image(), 48)!!
        assertTrue(bitmap.width <= 48 && bitmap.height <= 48)
        assertTrue(bitmap.allocationByteCount <= 48 * 48 * 4)
        val capped = session.load(image(), 10_000)!!
        assertTrue(capped.width <= 256 && capped.height <= 256)
        assertTrue(capped.allocationByteCount <= 256 * 256 * 4)
    }

    @Test
    fun rejectsInvalidImagesAndExcessiveSourceBounds() = runBlocking {
        assertNull(session(byteArrayOf(1, 2, 3)).load(image(), 48))
        val bytes = imageBytes(Color.RED)
        // PNG IHDR declares dimensions before any pixel data is decoded.
        java.nio.ByteBuffer.wrap(bytes, 16, 8).putInt(100_000).putInt(100_000)
        val checksum = java.util.zip.CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        java.nio.ByteBuffer.wrap(bytes, 29, 4).putInt(checksum)
        assertNull(session(bytes).load(image(), 48))
    }

    @Test
    fun tinyImagesCannotGrowTheCacheWithoutAnEntryLimit() = runBlocking {
        val reads = AtomicInteger()
        val session = session(imageBytes(Color.RED, 1, 1), reads)
        val first = session.load(image(), 40)!!
        repeat(300) { index -> session.load(image().copy(path = "/$index.png"), 40)!! }

        assertNotSame(first, session.load(image(), 40))
        assertEquals(302, reads.get())
    }

    @Test
    fun remoteImageRendersInTheExistingThumbnailSurface() {
        val session = session(imageBytes(Color.MAGENTA))
        compose.setContent {
            MaterialTheme { FileThumbnailOrIcon(image(), 48.dp, remoteImages = session) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(REMOTE_IMAGE_THUMBNAIL_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(REMOTE_IMAGE_THUMBNAIL_TEST_TAG).assertExists()
    }

    @Test
    fun respectsAllExifOrientationsWithinTheSampledBounds() = runBlocking {
        val expected = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        expected.forEachIndexed { index, corners ->
            val bitmap = session(jpegWithOrientation(index + 1)).load(image().copy(name = "photo.jpg"), 40)!!
            assertEquals(if (index < 4) 40 else 20, bitmap.width)
            assertEquals(if (index < 4) 20 else 40, bitmap.height)
            val samples = listOf(
                bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
                bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
                bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
                bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4),
            )
            assertEquals("EXIF orientation ${index + 1}", corners, samples.map(::nearestCornerColor))
        }
    }

    @Test
    fun cancellationAndSessionClosureNeverAbortStreamsOnMain() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val abortedOnMain = AtomicBoolean(false)
        val session = RemoteImageThumbnailSession {
            object : FileProvider by LocalFileProvider() {
                override suspend fun getInputStream(path: String) = Result.success(object : InputStream(), TransferAbortable {
                    override fun read(): Int {
                        entered.countDown()
                        check(released.await(5, TimeUnit.SECONDS))
                        return -1
                    }
                    override fun abortTransfer() {
                        if (Looper.myLooper() == Looper.getMainLooper()) abortedOnMain.set(true)
                        released.countDown()
                    }
                    override fun close() { closed.set(true) }
                } as InputStream)
            }
        }.also(sessions::add)
        var request: Job? = null
        try {
            compose.runOnIdle { request = CoroutineScope(Dispatchers.Main).launch { session.load(image(), 40) } }
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.runOnIdle { request?.cancel(); session.close() }
            compose.waitUntil(5_000) { closed.get() }
            assertFalse(abortedOnMain.get())
        } finally {
            released.countDown()
            compose.runOnIdle { request?.cancel(); session.close() }
        }
    }

    private fun jpegWithOrientation(orientation: Int): ByteArray {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val temporary = File.createTempFile("thumbnail-orientation-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (y in 0 until 40) for (x in 0 until 80) bitmap.setPixel(x, y, colors[(if (y < 20) 0 else 2) + if (x < 40) 0 else 1])
        return try {
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            ExifInterface(temporary).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            temporary.readBytes()
        } finally { bitmap.recycle(); temporary.delete() }
    }

    private fun nearestCornerColor(color: Int): Int =
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).indices.minBy { index ->
            val candidate = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)[index]
            kotlin.math.abs(Color.red(candidate) - Color.red(color)) +
                kotlin.math.abs(Color.green(candidate) - Color.green(color)) +
                kotlin.math.abs(Color.blue(candidate) - Color.blue(color))
        }

    private fun image() = FileItem("photo.png", "/photo.png", false, 0, Date(0), source = FileSource.SMB)

    private fun session(bytes: ByteArray, reads: AtomicInteger = AtomicInteger()) =
        RemoteImageThumbnailSession {
            object : FileProvider by LocalFileProvider() {
                override suspend fun getInputStream(path: String) = Result.success(
                    ByteArrayInputStream(bytes).also { reads.incrementAndGet() },
                )
            }
        }.also(sessions::add)

    private fun imageBytes(color: Int, width: Int = 80, height: Int = 40): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }
}
