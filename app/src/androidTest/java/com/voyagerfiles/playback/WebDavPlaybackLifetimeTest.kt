package com.voyagerfiles.playback

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.voyagerfiles.app.MainActivity
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WebDavPlaybackLifetimeTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() { WebDavPlaybackProvider.resetForTest() }
    @After fun teardown() { WebDavPlaybackProvider.resetForTest() }

    @Test fun detachedReaderKeepsForegroundUntilTheLastNativeDescriptorCloses() = runBlocking {
        val source = Source(7)
        val prepared = WebDavPlaybackProvider.prepare(context, entry(source))
        assertTrue(foreground(WebDavMediaPlaybackService::class.java))
        context.getSystemService(NotificationManager::class.java).activeNotifications.forEach {
            val text = it.notification.extras.toString()
            assertFalse(text.contains("secret-file"))
            assertFalse(text.contains("example.test"))
        }
        val wrapper = context.contentResolver.openFileDescriptor(prepared.uri, "r")!!
        val detached = ParcelFileDescriptor.adoptFd(wrapper.detachFd())
        wrapper.close()
        activity.scenario.moveToState(Lifecycle.State.CREATED)
        try {
            Thread.sleep(500)
            assertTrue(foreground(WebDavMediaPlaybackService::class.java))
            assertEquals(0, source.closed.get())
            FileInputStream(detached.fileDescriptor).use { assertEquals(7, it.read()) }
        } finally { detached.close() }
        waitUntil { source.closed.get() == 1 && !foreground(WebDavMediaPlaybackService::class.java) }
    }

    @Test fun failedNewStableUriLaunchAndMissingPromotionKeepOldDescriptorReadable() = runBlocking {
        val identity = PlaybackResourceIdentity(872, "https://example.test/video.mp4", "private-user")
        val old = Source(1)
        val prepared = WebDavPlaybackProvider.prepare(context, entry(old), identity)
        context.contentResolver.openFileDescriptor(prepared.uri, "r")!!.use { descriptor ->
            val failed = Source(2)
            try {
                WebDavPlaybackProvider.prepare(context, entry(failed), identity, start = { _, _ -> error("FGS denied") })
                fail("Expected foreground start failure")
            } catch (_: IllegalStateException) { }
            assertEquals(1, failed.closed.get())
            val missing = Source(3)
            try {
                WebDavPlaybackProvider.prepare(context, entry(missing), identity, startupTimeoutMillis = 50, start = { _, _ -> })
                fail("Expected promotion timeout")
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
            assertEquals(1, missing.closed.get())
            assertEquals(0, old.closed.get())
            assertTrue(foreground(WebDavMediaPlaybackService::class.java))
            FileInputStream(descriptor.fileDescriptor).use { assertEquals(1, it.read()) }
        }
    }

    @Test fun dataSyncTimeoutStopsDocumentServingAndPreservesMediaPlayback() = runBlocking {
        val media = Source(4)
        val document = Source(5)
        val playback = WebDavPlaybackProvider.prepare(context, entry(media))
        val openDocument = WebDavPlaybackProvider.prepare(context, entry(document, "application/pdf"))
        context.contentResolver.openFileDescriptor(playback.uri, "r")!!.use { video ->
            context.contentResolver.openFileDescriptor(openDocument.uri, "r")!!.use {
                assertTrue(foreground(WebDavFileServingService::class.java))
                PlaybackLifetimeKeeper.store.fail(PlaybackServiceKind.DATA, IllegalStateException("timeout"))
                waitUntil { document.closed.get() == 1 && !foreground(WebDavFileServingService::class.java) }
                assertEquals(0, media.closed.get())
                assertTrue(foreground(WebDavMediaPlaybackService::class.java))
                FileInputStream(video.fileDescriptor).use { input -> assertEquals(4, input.read()) }
            }
        }
    }

    @Test fun registrationFailureCleansItsSourceBeforeStartingAService() = runBlocking {
        val source = Source(6)
        var started = false
        try {
            WebDavPlaybackProvider.prepare(context, entry(source).copy(size = -1), start = { _, _ -> started = true })
            fail("Expected invalid source size")
        } catch (_: IllegalArgumentException) { }
        assertFalse(started)
        assertEquals(1, source.closed.get())
        assertTrue(PlaybackLifetimeKeeper.store.kinds.value.isEmpty())
    }

    @Suppress("DEPRECATION")
    private fun foreground(type: Class<*>) = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == type.name && it.foreground }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(100) { if (predicate()) return; Thread.sleep(100) }
        assertTrue(predicate())
    }

    private fun entry(source: Source, mime: String = "video/mp4") = PlaybackEntry("secret-file.mp4", mime, 1, source)

    private class Source(private val value: Int) : PlaybackRandomAccessSource {
        val closed = AtomicInteger()
        override fun read(offset: Long, byteCount: Int) = Result.success(if (offset == 0L) byteArrayOf(value.toByte()) else byteArrayOf())
        override fun close() { closed.incrementAndGet() }
    }
}
