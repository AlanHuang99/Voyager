package com.voyagerfiles.ui.components

import com.voyagerfiles.R
import com.voyagerfiles.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteDialogModelTest {

    @Test
    fun localTrashDeleteExplainsThatItemsCanBeRestored() {
        val model = DeleteDialogModel.localTrash(count = 2, fileName = "")

        assertEquals(UiText.Resource(R.string.dialog_move_to_trash), model.title)
        assertEquals(UiText.Plural(R.plurals.dialog_move_items_to_trash, 2, listOf(2)), model.message)
        assertEquals(UiText.Resource(R.string.action_move), model.confirmLabel)
    }

    @Test
    fun permanentDeleteExplainsIrreversibility() {
        val model = DeleteDialogModel.permanent(count = 1, fileName = "report.txt")

        assertEquals(UiText.Resource(R.string.dialog_delete_permanently), model.title)
        assertEquals(
            UiText.Resource(
                R.string.dialog_delete_named_permanently,
                listOf(UiText.Dynamic("report.txt")),
            ),
            model.message,
        )
        assertEquals(UiText.Resource(R.string.action_delete), model.confirmLabel)
    }

    @Test
    fun singleTrashItemIncludesItsName() {
        assertEquals(
            UiText.Resource(
                R.string.dialog_move_named_to_trash,
                listOf(UiText.Dynamic("report.txt")),
            ),
            DeleteDialogModel.localTrash(count = 1, fileName = "report.txt").message,
        )
    }
}
