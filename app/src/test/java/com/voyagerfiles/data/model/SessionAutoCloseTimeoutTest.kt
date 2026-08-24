package com.voyagerfiles.data.model

import com.voyagerfiles.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAutoCloseTimeoutTest {

    @Test
    fun choicesExposeExpectedLabelsAndDurations() {
        assertEquals(
            listOf(
                R.string.session_timeout_5_minutes,
                R.string.session_timeout_15_minutes,
                R.string.session_timeout_30_minutes,
                R.string.session_timeout_1_hour,
            ),
            SessionAutoCloseTimeout.entries.map { it.labelRes },
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
