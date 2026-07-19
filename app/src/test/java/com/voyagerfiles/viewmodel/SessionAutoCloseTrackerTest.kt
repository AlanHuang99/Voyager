package com.voyagerfiles.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAutoCloseTrackerTest {

    private val tracker = SessionAutoCloseTracker()

    @Test
    fun foregroundBelowThresholdKeepsSessionsOpen() {
        tracker.onBackgrounded(nowMillis = 1_000L)

        assertFalse(
            tracker.onForegrounded(
                nowMillis = 1_000L + TIMEOUT_MILLIS - 1L,
                enabled = true,
                timeoutMillis = TIMEOUT_MILLIS,
                operationRunning = false,
            )
        )
    }

    @Test
    fun foregroundAtThresholdClosesSessions() {
        tracker.onBackgrounded(nowMillis = 1_000L)

        assertTrue(
            tracker.onForegrounded(
                nowMillis = 1_000L + TIMEOUT_MILLIS,
                enabled = true,
                timeoutMillis = TIMEOUT_MILLIS,
                operationRunning = false,
            )
        )
    }

    @Test
    fun disabledSettingKeepsSessionsOpen() {
        tracker.onBackgrounded(nowMillis = 1_000L)

        assertFalse(
            tracker.onForegrounded(
                nowMillis = 1_000L + TIMEOUT_MILLIS,
                enabled = false,
                timeoutMillis = TIMEOUT_MILLIS,
                operationRunning = false,
            )
        )
    }

    @Test
    fun clockRollbackKeepsSessionsOpen() {
        tracker.onBackgrounded(nowMillis = 2_000L)

        assertFalse(
            tracker.onForegrounded(
                nowMillis = 1_000L,
                enabled = true,
                timeoutMillis = TIMEOUT_MILLIS,
                operationRunning = false,
            )
        )
    }

    @Test
    fun expiredIntervalDefersWhileOperationRuns() {
        tracker.onBackgrounded(nowMillis = 1_000L)

        assertFalse(
            tracker.onForegrounded(
                nowMillis = 1_000L + TIMEOUT_MILLIS,
                enabled = true,
                timeoutMillis = TIMEOUT_MILLIS,
                operationRunning = true,
            )
        )
        assertTrue(tracker.consumePendingAfterOperation())
        assertFalse(tracker.consumePendingAfterOperation())
    }

    @Test
    fun canceledPendingClosureIsNotConsumed() {
        tracker.onBackgrounded(nowMillis = 1_000L)
        tracker.onForegrounded(
            nowMillis = 1_000L + TIMEOUT_MILLIS,
            enabled = true,
            timeoutMillis = TIMEOUT_MILLIS,
            operationRunning = true,
        )

        tracker.cancelPending()

        assertFalse(tracker.consumePendingAfterOperation())
    }

    @Test
    fun foregroundWithoutBackgroundEventKeepsSessionsOpen() {
        assertFalse(
            tracker.onForegrounded(
                nowMillis = TIMEOUT_MILLIS,
                enabled = true,
                timeoutMillis = TIMEOUT_MILLIS,
                operationRunning = false,
            )
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5L * 60_000L
    }
}
