package com.voyagerfiles.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAutoCloseTrackerTest {

    private val tracker = SessionAutoCloseTracker()

    @Test
    fun foregroundBelowThresholdKeepsSessionsOpen() {
        tracker.onBackgrounded(nowMillis = 1_000L)

        assertEquals(
            SessionAutoCloseDecision.KEEP_OPEN,
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

        assertEquals(
            SessionAutoCloseDecision.CLOSE_NOW,
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

        assertEquals(
            SessionAutoCloseDecision.KEEP_OPEN,
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

        assertEquals(
            SessionAutoCloseDecision.KEEP_OPEN,
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

        assertEquals(
            SessionAutoCloseDecision.DEFER_UNTIL_OPERATION_FINISHES,
            tracker.onForegrounded(
                nowMillis = 1_000L + TIMEOUT_MILLIS,
                enabled = true,
                timeoutMillis = TIMEOUT_MILLIS,
                operationRunning = true,
            )
        )
    }

    @Test
    fun deferredDecisionIsNotReusedOnAnotherForegroundEvent() {
        tracker.onBackgrounded(nowMillis = 1_000L)
        tracker.onForegrounded(
            nowMillis = 1_000L + TIMEOUT_MILLIS,
            enabled = true,
            timeoutMillis = TIMEOUT_MILLIS,
            operationRunning = true,
        )

        assertEquals(
            SessionAutoCloseDecision.KEEP_OPEN,
            tracker.onForegrounded(
                nowMillis = 1_000L + TIMEOUT_MILLIS,
                enabled = true,
                timeoutMillis = TIMEOUT_MILLIS,
                operationRunning = false,
            ),
        )
    }

    @Test
    fun foregroundWithoutBackgroundEventKeepsSessionsOpen() {
        assertEquals(
            SessionAutoCloseDecision.KEEP_OPEN,
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
