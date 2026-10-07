package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.archive.ArchivePhase
import com.voyagerfiles.data.archive.ArchiveProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveProgressThrottleTest {
    private var now = 0L
    private val throttle = ArchiveProgressThrottle(nanoTime = { now }, intervalNanos = 100)

    @Test
    fun manySmallEntriesArePacedByTime() {
        assertEquals(0L, throttle.accept(writing(completed = 0)))
        now = 40
        assertNull(throttle.accept(writing(completed = 1)))
        now = 99
        assertNull(throttle.accept(writing(completed = 2)))
        now = 100
        assertEquals(100L, throttle.accept(writing(completed = 3)))
    }

    @Test
    fun exhaustedCompressedSourceDoesNotBypassEntryPacing() {
        throttle.accept(writing(completed = 0, totalEntries = null))
        now = 1
        assertNull(throttle.accept(writing(completed = 1, processed = 50, totalEntries = null)))
        now = 2
        assertNull(throttle.accept(writing(completed = 2, processed = 50, totalEntries = null)))
        now = 100
        assertEquals(100L, throttle.accept(writing(completed = 3, processed = 50, totalEntries = null)))
    }

    @Test
    fun phaseChangesPublishAndRestartTheElapsedTime() {
        assertEquals(0L, throttle.accept(ArchiveProgress(phase = ArchivePhase.READING_SOURCE)))
        now = 30
        assertEquals(0L, throttle.accept(writing(completed = 0)))
        now = 250
        assertEquals(220L, throttle.accept(writing(completed = 1)))
    }

    @Test
    fun completionAlwaysPublishes() {
        throttle.accept(writing(completed = 0))
        now = 1
        assertEquals(1L, throttle.accept(writing(completed = 10).copy(isComplete = true)))
        now = 2
        assertEquals(2L, throttle.accept(writing(completed = 0, processed = 50, totalEntries = null).copy(isComplete = true)))
    }

    @Test
    fun resetForcesTheNextUpdate() {
        throttle.accept(writing(completed = 0))
        now = 10
        throttle.reset()
        assertEquals(0L, throttle.accept(writing(completed = 1)))
    }

    private fun writing(completed: Int, processed: Long = 0, totalEntries: Int? = 10) = ArchiveProgress(
        completedEntries = completed,
        totalEntries = totalEntries,
        processedBytes = processed,
        totalBytes = 50,
    )
}
