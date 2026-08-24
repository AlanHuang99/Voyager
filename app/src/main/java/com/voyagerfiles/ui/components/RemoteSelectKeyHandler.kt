package com.voyagerfiles.ui.components

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent

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

internal fun Modifier.remoteSelectActions(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    var state by remember { mutableStateOf(RemoteSelectState.Idle) }

    onFocusChanged { focusState ->
        if (!focusState.isFocused) state = RemoteSelectState.Idle
    }.onPreviewKeyEvent { event ->
        val nativeEvent = event.nativeKeyEvent
        if (!enabled || nativeEvent.keyCode !in remoteSelectKeyCodes) {
            return@onPreviewKeyEvent false
        }

        val input = when (nativeEvent.action) {
            KeyEvent.ACTION_DOWN -> RemoteSelectEvent.Down(nativeEvent.repeatCount)
            KeyEvent.ACTION_UP -> RemoteSelectEvent.Up
            else -> RemoteSelectEvent.Cancel
        }
        val result = reduceRemoteSelect(state, input)
        state = result.state
        when (result.action) {
            RemoteSelectAction.Click -> onClick()
            RemoteSelectAction.LongClick -> onLongClick()
            null -> Unit
        }
        true
    }
}

private val remoteSelectKeyCodes = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
)
