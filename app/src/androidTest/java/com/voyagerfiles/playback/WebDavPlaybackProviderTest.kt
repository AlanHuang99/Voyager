package com.voyagerfiles.playback

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.voyagerfiles.app.MainActivity
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule

class WebDavPlaybackProviderTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun register(context: Context, entry: PlaybackEntry, identity: PlaybackResourceIdentity? = null): Uri =
        runBlocking { WebDavPlaybackProvider.prepare(context, entry, identity).uri }

    @Before
    fun setUp() {
        WebDavPlaybackProvider.resetForTest()
    }

    @After
    fun tearDown() {
        WebDavPlaybackProvider.resetForTest()
    }

    @Test
    fun registersOpaqueUriAndExposesSafeMetadata() {
        val source = ByteArraySource(ByteArray(10) { it.toByte() }, "https://tester:secret@example.test/song.mp3")
        val uri = register(
            context,
            PlaybackEntry("private song.mp3", "audio/mpeg", 10, source),
        )

        assertEquals("${context.packageName}.webdavplayback", uri.authority)
        assertEquals(1, uri.pathSegments.size)
        assertTrue(uri.pathSegments.single().matches(Regex("[A-Za-z0-9_-]{43}")))
        assertFalse(uri.toString().contains("private"))
        assertFalse(uri.toString().contains("tester"))
        assertFalse(uri.toString().contains("secret"))
        assertFalse(uri.toString().contains("example"))
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("private song.mp3", cursor.getString(0))
            assertEquals(10L, cursor.getLong(1))
        }
        assertEquals("audio/mpeg", context.contentResolver.getType(uri))
    }

    @Test
    fun proxyDescriptorSupportsSeekedReadsAndCanBeReopenedAfterInspection() {
        val source = ByteArraySource(ByteArray(10) { it.toByte() }, "remote")
        val uri = register(
            context,
            PlaybackEntry("song.mp3", "audio/mpeg", 10, source),
        )

        context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                input.channel.position(4)
                assertArrayEquals(byteArrayOf(4, 5, 6), input.readNBytes(3))
            }
        }

        context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                input.channel.position(7)
                assertArrayEquals(byteArrayOf(7, 8, 9), input.readNBytes(3))
            }
        }
        assertEquals(0, source.closeCount)
        WebDavPlaybackProvider.revoke(uri)
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(uri, "r")
        }
        assertEquals(1, source.closeCount)
    }

    @Test
    fun closingOneDescriptorKeepsAnotherDocumentDescriptorReadable() {
        val source = ByteArraySource(ByteArray(10) { it.toByte() }, "remote")
        val uri = register(
            context, PlaybackEntry("report.pdf", "application/pdf", 10, source),
        )
        val first = context.contentResolver.openFileDescriptor(uri, "r")!!
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { second ->
            first.close()
            FileInputStream(second.fileDescriptor).use { input ->
                input.channel.position(5)
                assertArrayEquals(byteArrayOf(5, 6), input.readNBytes(2))
            }
        }
        assertEquals(0, source.closeCount)
    }

    @Test
    fun reopeningAResourceKeepsItsUriAndExistingDescriptorsRemainReadable() {
        val identity = PlaybackResourceIdentity(14, "https://example.test/movie.mp4", "tester")
        val oldSource = ByteArraySource(byteArrayOf(1, 2, 3), "old")
        val firstUri = register(
            context, PlaybackEntry("movie.mp4", "video/mp4", 3, oldSource), identity,
        )

        context.contentResolver.openFileDescriptor(firstUri, "r")!!.use { oldDescriptor ->
            val freshSource = ByteArraySource(byteArrayOf(4, 5, 6), "fresh")
            val secondUri = register(
                context, PlaybackEntry("movie.mp4", "video/mp4", 3, freshSource), identity,
            )
            assertEquals(firstUri, secondUri)
            FileInputStream(oldDescriptor.fileDescriptor).use { input ->
                assertArrayEquals(byteArrayOf(1, 2, 3), input.readNBytes(3))
            }
            context.contentResolver.openFileDescriptor(secondUri, "r")!!.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    assertArrayEquals(byteArrayOf(4, 5, 6), input.readNBytes(3))
                }
            }
        }

        WebDavPlaybackProvider.resetForTest()
        val afterRestart = register(
            context, PlaybackEntry("movie.mp4", "video/mp4", 3, ByteArraySource(byteArrayOf(7, 8, 9), "restart")), identity,
        )
        assertEquals(firstUri, afterRestart)
    }

    @Test
    fun activeDescriptorRemainsReadableBeyondTheIdleTimeout() {
        val clock = FakeClock()
        WebDavPlaybackProvider.setStoreForTest(
            PlaybackTokenStore(clock::now, SecureRandom(), inactivityMillis = 100),
        )
        val uri = register(
            context, PlaybackEntry("movie.mp4", "video/mp4", 3, ByteArraySource(byteArrayOf(1, 2, 3), "remote")),
        )

        context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            clock.advanceBy(1_000)
            FileInputStream(descriptor.fileDescriptor).use { input ->
                assertArrayEquals(byteArrayOf(1, 2, 3), input.readNBytes(3))
            }
        }
    }

    @Test
    fun rejectsWritesUnknownTokensAndExpiredEntries() {
        val clock = FakeClock()
        WebDavPlaybackProvider.setStoreForTest(
            PlaybackTokenStore(clock::now, SecureRandom(), inactivityMillis = 100),
        )
        val source = ByteArraySource(byteArrayOf(1, 2, 3), "remote")
        val uri = register(
            context,
            PlaybackEntry("song.mp3", "audio/mpeg", 3, source),
        )

        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(uri, "w")
        }
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(
                Uri.parse("content://${context.packageName}.webdavplayback/unknown"),
                "r",
            )
        }
        clock.advanceBy(100)
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(uri, "r")
        }
        assertEquals(1, source.closeCount)
        assertNull(context.contentResolver.query(uri, null, null, null, null))
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(50) {
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue(predicate())
    }

    private class FakeClock {
        private var millis = 0L
        fun now(): Long = millis
        fun advanceBy(delta: Long) {
            millis += delta
        }
    }

    private class ByteArraySource(
        private val bytes: ByteArray,
        val label: String,
    ) : PlaybackRandomAccessSource {
        var closeCount = 0

        override fun read(offset: Long, byteCount: Int): Result<ByteArray> = runCatching {
            require(offset >= 0 && offset < bytes.size)
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + byteCount))
        }

        override fun close() {
            closeCount += 1
        }
    }
}
