package com.voyagerfiles.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import com.voyagerfiles.R
import com.voyagerfiles.data.model.ConnectionProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProtocolSelector(
    protocol: ConnectionProtocol,
    onSelect: (ConnectionProtocol) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val anchorFocus = remember { FocusRequester() }
    val itemFocus = remember { ConnectionProtocol.entries.map { FocusRequester() } }
    fun closeMenu() {
        expanded = false
        restoreFocus = true
    }
    LaunchedEffect(restoreFocus, expanded) {
        if (restoreFocus && !expanded) {
            anchorFocus.requestFocus()
            restoreFocus = false
        }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (expanded) closeMenu() else expanded = true },
    ) {
        OutlinedTextField(
            value = stringResource(protocol.displayNameRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.connection_protocol)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(anchorFocus)
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    if (key.keyCode in selectKeys) {
                        if (key.action == KeyEvent.ACTION_UP) expanded = true
                        true
                    } else if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || key.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (key.action == KeyEvent.ACTION_DOWN) {
                            focusManager.moveFocus(
                                if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) FocusDirection.Down else FocusDirection.Up,
                            )
                        }
                        true
                    } else false
                }
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = ::closeMenu) {
            LaunchedEffect(expanded) {
                if (expanded) itemFocus[protocol.ordinal].requestFocus()
            }
            ConnectionProtocol.entries.forEachIndexed { index, item ->
                fun select() {
                    onSelect(item)
                    closeMenu()
                }
                DropdownMenuItem(
                    text = { Text(stringResource(item.displayNameRes)) },
                    onClick = ::select,
                    modifier = Modifier
                        .focusRequester(itemFocus[index])
                        .onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            when (key.keyCode) {
                                in selectKeys -> {
                                    if (key.action == KeyEvent.ACTION_UP) select()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (key.action == KeyEvent.ACTION_DOWN) {
                                        val delta = if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                                        val next = (index + delta).coerceIn(itemFocus.indices)
                                        itemFocus[next].requestFocus()
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
        }
    }
}

private val selectKeys = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
)
