package com.voyagerfiles.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAutoCloseTimeoutTest {

    @Test
    fun choicesExposeExpectedLabelsAndDurations() {
        assertEquals(
            listOf("5 minutes", "15 minutes", "30 minutes", "1 hour"),
            SessionAutoCloseTimeout.entries.map { it.label },
        )
        assertEquals(
            listOf(5L, 15L, 30L, 60L).map { it * 60_000L },
            SessionAutoCloseTimeout.entries.map { it.durationMillis },
        )
    }

    @Test
    fun persistedNameFallsBackToFifteenMinutes() {
        assertEquals(
            SessionAutoCloseTimeout.FIVE_MINUTES,
            SessionAutoCloseTimeout.fromName(SessionAutoCloseTimeout.FIVE_MINUTES.name),
        )
        assertEquals(
            SessionAutoCloseTimeout.FIFTEEN_MINUTES,
            SessionAutoCloseTimeout.fromName(null),
        )
        assertEquals(
            SessionAutoCloseTimeout.FIFTEEN_MINUTES,
            SessionAutoCloseTimeout.fromName("REMOVED_CHOICE"),
        )
    }
}
