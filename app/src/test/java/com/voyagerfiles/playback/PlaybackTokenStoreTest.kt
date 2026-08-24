package com.voyagerfiles.playback

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTokenStoreTest {
    @Test
    fun tokensAreUniqueOpaqueAndContainAtLeast256RandomBits() {
        val clock = FakeClock()
        val store = PlaybackTokenStore(clock::now, SecureRandom(), INACTIVITY_MILLIS)
        val source = FakeSource("https://tester:secret@example.test/media/song.mp3")
        val entry = PlaybackEntry("private song.mp3", "audio/mpeg", 10, source)

        val tokens = (1..1_000).map { store.register(entry.copy(source = FakeSource(source.label))) }

        assertEquals(1_000, tokens.toSet().size)
        assertTrue(tokens.all { it.matches(Regex("[A-Za-z0-9_-]{43}")) })
        tokens.forEach { token ->
            assertFalse(token.contains("private"))
            assertFalse(token.contains("audio"))
            assertFalse(token.contains("tester"))
            assertFalse(token.contains("secret"))
            assertFalse(token.contains("example"))
        }
        store.clear()
    }

    @Test
    fun lookupRefreshesTheInactivityDeadline() {
        val clock = FakeClock()
        val store = PlaybackTokenStore(clock::now, SecureRandom(), INACTIVITY_MILLIS)
        val entry = PlaybackEntry("song.mp3", "audio/mpeg", 10, FakeSource("remote"))
        val token = store.register(entry)

        clock.advanceBy(INACTIVITY_MILLIS - 1)
        assertSame(entry, store.lookup(token))
        clock.advanceBy(INACTIVITY_MILLIS - 1)
        assertSame(entry, store.lookup(token))
    }

    @Test
    fun expiresAtExactlyTenInactiveMinutesAndClosesOnce() {
        val clock = FakeClock()
        val store = PlaybackTokenStore(clock::now, SecureRandom(), INACTIVITY_MILLIS)
        val source = FakeSource("remote")
        val token = store.register(PlaybackEntry("song.mp3", "audio/mpeg", 10, source))

        clock.advanceBy(INACTIVITY_MILLIS)

        assertNull(store.lookup(token))
        assertNull(store.lookup(token))
        assertEquals(1, source.closeCount)
    }

    @Test
    fun removeAndClearCloseEverySourceExactlyOnce() {
        val store = PlaybackTokenStore({ 0L }, SecureRandom(), INACTIVITY_MILLIS)
        val removed = FakeSource("removed")
        val cleared = FakeSource("cleared")
        val removedToken = store.register(PlaybackEntry("one", "audio/mpeg", 1, removed))
        store.register(PlaybackEntry("two", "video/mp4", 2, cleared))

        store.remove(removedToken)
        store.remove(removedToken)
        store.clear()
        store.clear()

        assertEquals(1, removed.closeCount)
        assertEquals(1, cleared.closeCount)
        assertNull(store.lookup("unknown"))
    }

    private class FakeClock {
        private var millis = 0L
        fun now(): Long = millis
        fun advanceBy(delta: Long) {
            millis += delta
        }
    }

    private class FakeSource(val label: String) : PlaybackRandomAccessSource {
        var closeCount = 0

        override fun read(offset: Long, byteCount: Int): Result<ByteArray> = Result.success(ByteArray(byteCount))

        override fun close() {
            closeCount += 1
        }
    }

    private companion object {
        const val INACTIVITY_MILLIS = 10 * 60 * 1_000L
    }
}
