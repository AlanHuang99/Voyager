package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.TrashEntry
import com.voyagerfiles.ui.text.UiText

sealed interface OperationState {
    data object Idle : OperationState

    data class Running(
        val progress: TransferProgress,
        val id: Long = 0,
        val cancellable: Boolean = false,
        val cancelling: Boolean = false,
    ) : OperationState {
        val label: UiText
            get() = progress.label
    }
}

data class TrashState(
    val entries: List<TrashEntry> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: UiText? = null,
)
