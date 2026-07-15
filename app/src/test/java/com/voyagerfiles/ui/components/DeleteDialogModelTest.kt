package com.voyagerfiles.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteDialogModelTest {

    @Test
    fun localTrashDeleteExplainsThatItemsCanBeRestored() {
        val model = DeleteDialogModel.localTrash(count = 2, fileName = "")

        assertEquals("Move to Trash", model.title)
        assertEquals("Move 2 items to Trash? You can restore them later.", model.message)
        assertEquals("Move", model.confirmLabel)
    }

    @Test
    fun permanentDeleteExplainsIrreversibility() {
        val model = DeleteDialogModel.permanent(count = 1, fileName = "report.txt")

        assertEquals("Delete permanently", model.title)
        assertTrue(model.message.contains("cannot be undone"))
        assertEquals("Delete", model.confirmLabel)
    }

    @Test
    fun singleTrashItemIncludesItsName() {
        assertEquals(
            "Move \"report.txt\" to Trash? You can restore it later.",
            DeleteDialogModel.localTrash(count = 1, fileName = "report.txt").message,
        )
    }
}
