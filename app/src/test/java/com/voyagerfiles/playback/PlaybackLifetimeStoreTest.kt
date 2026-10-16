package com.voyagerfiles.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackLifetimeStoreTest {
    private var now = 0L
    private val store = PlaybackLifetimeStore({ now }, pendingMillis = 100, probeMillis = 10)
    private val service = Any()

    @Test fun preparationWaitsForPromotionAndRejectsUnpreparedReaders() {
        val entry = entry("video/mp4")
        assertNull(store.acquire(entry))
        val pending = store.prepare(entry) {}
        assertFalse(pending.ready.isCompleted)
        assertNull(store.acquire(entry))
        store.started(pending.id, service)
        assertTrue(pending.ready.isCompleted)
        assertNotNull(store.acquire(entry))
        assertEquals(setOf(PlaybackServiceKind.MEDIA), store.kinds.value)
    }

    @Test fun activeReadersSurviveLongBufferingAndOnlyLastClosureStartsGrace() {
        val entry = entry("audio/mpeg")
        var finished = 0
        val pending = store.prepare(entry) { finished++ }
        store.started(pending.id, service)
        val first = store.acquire(entry)!!
        val second = store.acquire(entry)!!
        now = 24 * 60 * 60 * 1_000L
        store.sweep()
        first.close()
        first.close()
        now += 100
        store.sweep()
        assertEquals(0, finished)
        second.close()
        now += 9
        store.sweep()
        assertEquals(0, finished)
        now++
        store.sweep()
        assertEquals(1, finished)
        assertTrue(store.kinds.value.isEmpty())
    }

    @Test fun decoderProbeCanCloseAndReopenWithinGrace() {
        val entry = entry("video/mp4")
        val pending = store.prepare(entry) {}
        store.started(pending.id, service)
        store.acquire(entry)!!.close()
        now = 9
        val reader = store.acquire(entry)!!
        now = 1_000
        store.sweep()
        assertEquals(setOf(PlaybackServiceKind.MEDIA), store.kinds.value)
        reader.close()
        now += 10
        store.sweep()
        assertTrue(store.kinds.value.isEmpty())
    }

    @Test fun neverOpenedLaunchExpiresAndCannotBePromotedAfterCleanup() {
        var finished = 0
        val pending = store.prepare(entry("video/mp4")) { finished++ }
        now = 100
        store.sweep()
        store.started(pending.id, service)
        store.cancel(pending.id)
        assertEquals(1, finished)
        assertTrue(pending.ready.isCancelled)
        assertTrue(store.kinds.value.isEmpty())
    }

    @Test fun failedPromotionClosesEveryAffectedGenerationOnce() {
        var finished = 0
        val first = store.prepare(entry("video/mp4")) { finished++ }
        val second = store.prepare(entry("audio/mpeg")) { finished++ }
        store.fail(PlaybackServiceKind.MEDIA, IllegalStateException("denied"))
        store.cancel(first.id)
        store.cancel(second.id)
        assertEquals(2, finished)
        assertTrue(first.ready.isCancelled)
        assertTrue(second.ready.isCancelled)
        assertTrue(store.kinds.value.isEmpty())
    }

    @Test fun dataSyncTimeoutLeavesMediaAndItsReaderAlive() {
        var mediaClosed = false
        var documentClosed = false
        val media = entry("video/mp4")
        val document = entry("application/pdf")
        store.started(store.prepare(media) { mediaClosed = true }.id, service)
        store.started(store.prepare(document) { documentClosed = true }.id, Any())
        val reader = store.acquire(media)!!
        store.acquire(document)!!
        store.fail(PlaybackServiceKind.DATA, IllegalStateException("timeout"))
        assertFalse(mediaClosed)
        assertTrue(documentClosed)
        assertEquals(setOf(PlaybackServiceKind.MEDIA), store.kinds.value)
        assertNotNull(store.acquire(media))
        reader.close()
    }

    @Test fun oldServiceDestructionDoesNotCancelNewPendingStart() {
        var oldClosed = false
        var newClosed = false
        val old = store.prepare(entry("video/mp4")) { oldClosed = true }
        store.started(old.id, service)
        val next = store.prepare(entry("video/mp4")) { newClosed = true }
        store.serviceLost(service)
        assertTrue(oldClosed)
        assertFalse(newClosed)
        assertFalse(next.ready.isCompleted)
        store.started(next.id, Any())
        assertTrue(next.ready.isCompleted)
    }

    @Test fun failedNewLaunchAndPendingExpiryPreserveOlderStableUriReader() {
        val tokens = PlaybackTokenStore()
        val old = entry("video/mp4")
        val token = tokens.register(old, "stable")
        val descriptor = tokens.acquire(token)!!
        store.started(store.prepare(old) { tokens.remove(token, old) }.id, service)
        val foregroundReader = store.acquire(old)!!
        val fresh = entry("video/mp4")
        tokens.register(fresh, token)
        val freshLaunch = store.prepare(fresh) { tokens.remove(token, fresh) }
        store.cancel(freshLaunch.id)
        assertTrue(descriptor.touch())
        assertSame(old, tokens.lookup(token))
        assertEquals(setOf(PlaybackServiceKind.MEDIA), store.kinds.value)
        val third = entry("video/mp4")
        tokens.register(third, token)
        store.prepare(third) { tokens.remove(token, third) }
        now = 100
        store.sweep()
        assertTrue(descriptor.touch())
        assertSame(old, tokens.lookup(token))
        foregroundReader.close()
        descriptor.close()
    }

    private fun entry(mime: String) = PlaybackEntry("private", mime, 1, object : PlaybackRandomAccessSource {
        override fun read(offset: Long, byteCount: Int) = Result.success(byteArrayOf(1))
        override fun close() = Unit
    })
}
