package com.voyagerfiles.ui.components

data class DeleteDialogModel(
    val title: String,
    val message: String,
    val confirmLabel: String,
) {
    companion object {
        fun localTrash(count: Int, fileName: String): DeleteDialogModel =
            DeleteDialogModel(
                title = "Move to Trash",
                message = if (count == 1) {
                    "Move \"$fileName\" to Trash? You can restore it later."
                } else {
                    "Move $count items to Trash? You can restore them later."
                },
                confirmLabel = "Move",
            )

        fun permanent(count: Int, fileName: String): DeleteDialogModel =
            DeleteDialogModel(
                title = "Delete permanently",
                message = if (count == 1) {
                    "Permanently delete \"$fileName\"? This cannot be undone."
                } else {
                    "Permanently delete $count items? This cannot be undone."
                },
                confirmLabel = "Delete",
            )
    }
}
