package com.voyagerfiles.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionFeedbackTest {

    @Test
    fun hapticOccursOnlyWhenTheFirstItemBecomesSelected() {
        assertTrue(shouldPerformSelectionHaptic(emptySet(), "/first"))
        assertFalse(shouldPerformSelectionHaptic(setOf("/first"), "/second"))
        assertFalse(shouldPerformSelectionHaptic(setOf("/first"), "/first"))
    }
}
