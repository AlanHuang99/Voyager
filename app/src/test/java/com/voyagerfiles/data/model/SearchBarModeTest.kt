package com.voyagerfiles.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchBarModeTest {
    @Test fun absentOrUnknownSettingKeepsExistingTopSearch() {
        assertEquals(SearchBarMode.TOP, SearchBarMode.fromName(null))
        assertEquals(SearchBarMode.TOP, SearchBarMode.fromName("unknown"))
    }
    @Test fun savedModesRoundTrip() {
        SearchBarMode.entries.forEach { assertEquals(it, SearchBarMode.fromName(it.name)) }
    }
}
