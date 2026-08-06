package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.StreamTransferProgress

internal const val PROGRESS_PUBLICATION_BYTES = 256L * 1024L
internal const val PROGRESS_PUBLICATION_NANOS = 250L * 1_000_000L

internal class StreamProgressThrottle(
    private val byteThreshold: Long = PROGRESS_PUBLICATION_BYTES,
    private val timeThresholdNanos: Long = PROGRESS_PUBLICATION_NANOS,
) {
    private var lastPath: String? = null
    private var lastPublishedBytes = 0L
    private var lastPublishedElapsedNanos = 0L

    init {
        require(byteThreshold > 0) { "Byte threshold must be positive" }
        require(timeThresholdNanos > 0) { "Time threshold must be positive" }
    }

    fun shouldPublish(progress: StreamTransferProgress, force: Boolean = false): Boolean {
        val pathChanged = progress.path != lastPath
        val reachedKnownTotal = progress.totalBytes
            ?.takeIf { it > 0 }
            ?.let { total ->
                progress.bytesTransferred >= total &&
                    (pathChanged || lastPublishedBytes < total)
            }
            ?: false
        val crossedByteThreshold = !pathChanged &&
            progress.bytesTransferred - lastPublishedBytes >= byteThreshold
        val crossedTimeThreshold = !pathChanged &&
            progress.elapsedNanos - lastPublishedElapsedNanos >= timeThresholdNanos
        val shouldPublish = force ||
            pathChanged ||
            reachedKnownTotal ||
            crossedByteThreshold ||
            crossedTimeThreshold

        if (shouldPublish) {
            lastPath = progress.path
            lastPublishedBytes = progress.bytesTransferred
            lastPublishedElapsedNanos = progress.elapsedNanos
        }
        return shouldPublish
    }

    fun reset() {
        lastPath = null
        lastPublishedBytes = 0
        lastPublishedElapsedNanos = 0
    }
}
