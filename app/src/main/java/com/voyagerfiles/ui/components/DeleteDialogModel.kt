package com.voyagerfiles.ui.components

import com.voyagerfiles.R
import com.voyagerfiles.ui.text.UiText

data class DeleteDialogModel(
    val title: UiText,
    val message: UiText,
    val confirmLabel: UiText,
) {
    companion object {
        fun localTrash(count: Int, fileName: String): DeleteDialogModel =
            DeleteDialogModel(
                title = UiText.Resource(R.string.dialog_move_to_trash),
                message = if (count == 1) {
                    UiText.Resource(
                        R.string.dialog_move_named_to_trash,
                        listOf(UiText.Dynamic(fileName)),
                    )
                } else {
                    UiText.Plural(R.plurals.dialog_move_items_to_trash, count, listOf(count))
                },
                confirmLabel = UiText.Resource(R.string.action_move),
            )

        fun permanent(count: Int, fileName: String): DeleteDialogModel =
            DeleteDialogModel(
                title = UiText.Resource(R.string.dialog_delete_permanently),
                message = if (count == 1) {
                    UiText.Resource(
                        R.string.dialog_delete_named_permanently,
                        listOf(UiText.Dynamic(fileName)),
                    )
                } else {
                    UiText.Plural(R.plurals.dialog_delete_items_permanently, count, listOf(count))
                },
                confirmLabel = UiText.Resource(R.string.action_delete),
            )
    }
}
