package com.voyagerfiles.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DragSelectionRangeTest {
    @Test fun reversingRestoresInitialSelectionOutsideRange() {
        val drag = DragSelectionRange(listOf("a", "b", "c", "d", "e"), "b", setOf("e"))
        assertEquals(setOf("b", "c", "d", "e"), drag.selectionAt("d"))
        assertEquals(setOf("a", "b", "e"), drag.selectionAt("a"))
        assertEquals(setOf("b", "e"), drag.selectionAt("b"))
    }
    @Test fun draggingSelectedAnchorRemovesRangeAndReverseRestoresIt() {
        val drag = DragSelectionRange(listOf("a", "c", "e"), "c", setOf("a", "c", "e"))
        assertEquals(setOf("a"), drag.selectionAt("e"))
        assertEquals(setOf("a", "e"), drag.selectionAt("c"))
        assertEquals(setOf("a", "c", "e"), drag.selectionAt("missing"))
    }
}
