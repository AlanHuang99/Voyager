package com.voyagerfiles.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick as semanticLongClick
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileItem
import java.text.DateFormat

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    file: FileItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enableRemoteSelect: Boolean = false,
    enableDragSelection: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    isHighlighted: Boolean = false,
) {
    val selectionContentDescription = stringResource(
        if (isSelected) R.string.content_desc_deselect_named else R.string.content_desc_select_named,
        file.name,
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            // Marks the row whose menu is open, like a pressed state, without looking selected.
            isHighlighted -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                .compositeOver(MaterialTheme.colorScheme.surface)
            else -> MaterialTheme.colorScheme.surface
        },
        label = "selection_bg",
    )
    val horizontalPadding = if (compact) 8.dp else 16.dp
    val verticalPadding = if (compact) 4.dp else 12.dp
    val iconSize = if (compact) 32.dp else 40.dp
    val iconSpacing = if (compact) 12.dp else 16.dp
    val nameStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    // Give trailing content the row's full height through the screen edge.
    val trailingWidth = if (compact) 48.dp else 56.dp
    // D-pad focus moves explicitly between the row and its trailing child.
    val rowFocus = remember { FocusRequester() }
    val trailingFocus = remember { FocusRequester() }
    var rowFocused by remember { mutableStateOf(false) }

    Surface(
        color = backgroundColor,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (trailingContent != null) {
                    Modifier
                        .focusRequester(rowFocus)
                        .onFocusChanged { rowFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            val moves = rowFocused && event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionRight
                            if (moves) trailingFocus.requestFocus()
                            moves
                        }
                } else {
                    Modifier
                },
            )
            .remoteSelectActions(
                enabled = enableRemoteSelect,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (enableDragSelection) null else onLongClick,
            )
            .semantics {
                if (enableDragSelection) semanticLongClick { onLongClick(); true }
            },
    ) {
        Box {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(
                        start = horizontalPadding,
                        end = if (trailingContent != null) trailingWidth else horizontalPadding,
                        top = verticalPadding,
                        bottom = verticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.semantics {
                            contentDescription = selectionContentDescription
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                FileThumbnailOrIcon(file = file, iconSize = iconSize)

                Spacer(modifier = Modifier.width(iconSpacing))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = file.name,
                        style = nameStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!file.isDirectory) {
                            Text(
                                text = file.formattedSize,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = formatDate(file.lastModified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (trailingContent != null) {
                Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(trailingWidth)
                            .focusRequester(trailingFocus)
                            .onPreviewKeyEvent { event ->
                                val moves = event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft
                                if (moves) rowFocus.requestFocus()
                                moves
                            },
                    ) {
                        trailingContent()
                    }
                }
            }
        }
    }
}

private fun formatDate(date: java.util.Date): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
