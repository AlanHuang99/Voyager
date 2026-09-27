package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.archive.ArchivePhase
import com.voyagerfiles.data.archive.ArchiveProgress

/**
 * Archives can hold thousands of tiny entries, so updates are paced by time rather than per entry.
 * Phase changes and completion always publish so the label and the final count stay accurate.
 */
internal class ArchiveProgressThrottle(
    private val nanoTime: () -> Long = System::nanoTime,
    private val intervalNanos: Long = PROGRESS_PUBLICATION_NANOS,
) {
    private var phase: ArchivePhase? = null
    private var phaseStartedAt = 0L
    private var lastPublishedAt = 0L

    /** Returns the time spent in the current phase when [progress] should be shown, otherwise null. */
    fun accept(progress: ArchiveProgress): Long? {
        val now = nanoTime()
        val phaseChanged = progress.phase != phase
        if (phaseChanged) {
            phase = progress.phase
            phaseStartedAt = now
        }
        val finished = progress.totalEntries?.let { total -> progress.completedEntries + progress.skippedEntries >= total } == true ||
            progress.totalBytes?.let { total -> total > 0 && progress.processedBytes >= total } == true
        if (!phaseChanged && !finished && now - lastPublishedAt < intervalNanos) return null
        lastPublishedAt = now
        return (now - phaseStartedAt).coerceAtLeast(0)
    }

    /** Forces the next update through, e.g. after another label replaced the archive progress. */
    fun reset() {
        phase = null
    }
}
