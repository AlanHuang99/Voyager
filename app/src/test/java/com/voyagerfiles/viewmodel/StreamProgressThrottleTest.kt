package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.StreamTransferProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProgressThrottleTest {

    @Test
    fun publishesSlowProgressWhenTimeThresholdIsReached() {
        val throttle = StreamProgressThrottle(
            byteThreshold = 256 * 1024,
            timeThresholdNanos = 250_000_000,
        )

        assertTrue(throttle.shouldPublish(progress(bytes = 64 * 1024, elapsedNanos = 10_000_000)))
        assertFalse(throttle.shouldPublish(progress(bytes = 128 * 1024, elapsedNanos = 200_000_000)))
        assertTrue(throttle.shouldPublish(progress(bytes = 192 * 1024, elapsedNanos = 300_000_000)))
    }

    @Test
    fun publishesWhenByteThresholdTotalPathOrForceRequiresIt() {
        val throttle = StreamProgressThrottle(
            byteThreshold = 256 * 1024,
            timeThresholdNanos = 250_000_000,
        )

        assertTrue(throttle.shouldPublish(progress(bytes = 1, elapsedNanos = 1)))
        assertTrue(throttle.shouldPublish(progress(bytes = 256 * 1024 + 1, elapsedNanos = 2)))
        assertTrue(
            throttle.shouldPublish(
                progress(bytes = 300 * 1024, elapsedNanos = 3, totalBytes = 300 * 1024L),
            ),
        )
        assertTrue(
            throttle.shouldPublish(
                progress(path = "/remote/next.bin", bytes = 1, elapsedNanos = 1),
            ),
        )
        assertTrue(
            throttle.shouldPublish(
                progress(path = "/remote/next.bin", bytes = 2, elapsedNanos = 2),
                force = true,
            ),
        )
    }

    private fun progress(
        path: String = "/remote/slow.bin",
        bytes: Int,
        elapsedNanos: Long,
        totalBytes: Long? = null,
    ) = StreamTransferProgress(
        path = path,
        bytesTransferred = bytes.toLong(),
        totalBytes = totalBytes,
        elapsedNanos = elapsedNanos,
    )
}
