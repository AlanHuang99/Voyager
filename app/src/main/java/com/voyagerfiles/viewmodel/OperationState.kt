package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.TrashEntry

sealed interface OperationState {
    data object Idle : OperationState

    data class Running(val label: String) : OperationState
}

data class TrashState(
    val entries: List<TrashEntry> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
