package com.voyagerfiles.ui.components

internal enum class RemoteSelectState {
    Idle,
    Pressed,
    LongPressed,
}

internal sealed interface RemoteSelectEvent {
    data class Down(val repeatCount: Int) : RemoteSelectEvent
    data object Up : RemoteSelectEvent
    data object Cancel : RemoteSelectEvent
}

internal enum class RemoteSelectAction {
    Click,
    LongClick,
}

internal data class RemoteSelectResult(
    val state: RemoteSelectState,
    val action: RemoteSelectAction? = null,
)

internal fun reduceRemoteSelect(
    state: RemoteSelectState,
    event: RemoteSelectEvent,
): RemoteSelectResult = when (event) {
    is RemoteSelectEvent.Down -> when {
        state == RemoteSelectState.Idle && event.repeatCount == 0 -> {
            RemoteSelectResult(RemoteSelectState.Pressed)
        }
        state == RemoteSelectState.Pressed && event.repeatCount > 0 -> {
            RemoteSelectResult(
                state = RemoteSelectState.LongPressed,
                action = RemoteSelectAction.LongClick,
            )
        }
        else -> RemoteSelectResult(state)
    }
    RemoteSelectEvent.Up -> RemoteSelectResult(
        state = RemoteSelectState.Idle,
        action = if (state == RemoteSelectState.Pressed) RemoteSelectAction.Click else null,
    )
    RemoteSelectEvent.Cancel -> RemoteSelectResult(RemoteSelectState.Idle)
}
