package com.voyagerfiles.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteChoiceDialogModelTest {

    @Test
    fun singleItemNamesBothOutcomesAndIrreversibility() {
        val model = DeleteChoiceDialogModel.local(1, "notes.txt")

        assertEquals("Delete \"notes.txt\"?", model.title)
        assertTrue(model.message.contains("restore"))
        assertTrue(model.message.contains("cannot be undone"))
        assertEquals("Move to Trash", model.trashLabel)
        assertEquals("Delete permanently", model.permanentLabel)
    }

    @Test
    fun multipleItemsUseTheSelectionCount() {
        assertEquals("Delete 3 items?", DeleteChoiceDialogModel.local(3, "").title)
    }
}
