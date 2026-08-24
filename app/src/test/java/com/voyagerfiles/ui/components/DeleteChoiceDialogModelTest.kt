package com.voyagerfiles.ui.components

import com.voyagerfiles.R
import com.voyagerfiles.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteChoiceDialogModelTest {

    @Test
    fun singleItemNamesBothOutcomesAndIrreversibility() {
        val model = DeleteChoiceDialogModel.local(1, "notes.txt")

        assertEquals(
            UiText.Resource(
                R.string.dialog_delete_named_title,
                listOf(UiText.Dynamic("notes.txt")),
            ),
            model.title,
        )
        assertEquals(UiText.Resource(R.string.dialog_delete_choice_message), model.message)
        assertEquals(UiText.Resource(R.string.dialog_move_to_trash), model.trashLabel)
        assertEquals(UiText.Resource(R.string.dialog_delete_permanently), model.permanentLabel)
    }

    @Test
    fun multipleItemsUseTheSelectionCount() {
        assertEquals(
            UiText.Plural(R.plurals.dialog_delete_items_title, 3, listOf(3)),
            DeleteChoiceDialogModel.local(3, "").title,
        )
    }
}
