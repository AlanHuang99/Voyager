package com.voyagerfiles.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A drag recomputes its range from the initial selection, so reversing restores untouched items. */
internal class DragSelectionRange(private val paths: List<String>, private val anchor: String, private val initial: Set<String>) {
    private val selecting = anchor !in initial
    fun selectionAt(path: String): Set<String> {
        val first = paths.indexOf(anchor)
        val last = paths.indexOf(path)
        if (first < 0 || last < 0) return initial
        val range = paths.subList(minOf(first, last), maxOf(first, last) + 1).toSet()
        return if (selecting) initial + range else initial - range
    }
}

private data class DragItem(val key: String, val bounds: Rect)

@Composable
internal fun Modifier.dragFileSelection(
    paths: List<String>,
    selected: Set<String>,
    enabled: Boolean,
    listState: LazyListState? = null,
    gridState: LazyGridState? = null,
    gridContentOffset: Offset = Offset.Zero,
    onSelection: (Set<String>) -> Unit,
    onStartHaptic: () -> Unit,
): Modifier {
    val latestSelection by rememberUpdatedState(selected)
    val updateSelection by rememberUpdatedState(onSelection)
    val haptic by rememberUpdatedState(onStartHaptic)
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val speed = with(LocalDensity.current) { 720.dp.toPx() }
    return if (!enabled) this else pointerInput(paths, listState, gridState, gridContentOffset) {
        coroutineScope {
            var range: DragSelectionRange? = null
            var pointer = Offset.Zero
            var scrollJob: Job? = null
            fun items(): List<DragItem> = listState?.layoutInfo?.visibleItemsInfo?.map {
                DragItem(it.key as String, Rect(0f, it.offset.toFloat(), size.width.toFloat(), (it.offset + it.size).toFloat()))
            } ?: gridState!!.layoutInfo.visibleItemsInfo.map {
                DragItem(it.key as String, Rect(it.offset.x + gridContentOffset.x, it.offset.y + gridContentOffset.y,
                    it.offset.x + it.size.width + gridContentOffset.x, it.offset.y + it.size.height + gridContentOffset.y))
            }
            fun itemAt(position: Offset, nearest: Boolean): String? {
                val visible = items()
                return visible.firstOrNull { it.bounds.contains(position) }?.key
                    ?: if (nearest) visible.minByOrNull { item ->
                        val dx = (item.bounds.left - position.x).coerceAtLeast(0f) + (position.x - item.bounds.right).coerceAtLeast(0f)
                        val dy = (item.bounds.top - position.y).coerceAtLeast(0f) + (position.y - item.bounds.bottom).coerceAtLeast(0f)
                        dx * dx + dy * dy
                    }?.key else null
            }
            fun update() {
                val active = range ?: return
                itemAt(pointer, nearest = true)?.let { updateSelection(active.selectionAt(it)) }
            }
            fun velocity(): Float = when {
                pointer.y < edge && (listState?.canScrollBackward ?: gridState!!.canScrollBackward) ->
                    -speed * ((edge - pointer.y) / edge).coerceIn(0f, 1f)
                pointer.y > size.height - edge && (listState?.canScrollForward ?: gridState!!.canScrollForward) ->
                    speed * ((pointer.y - size.height + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            fun updateAutoScroll() {
                if (velocity() == 0f) {
                    scrollJob?.cancel()
                    scrollJob = null
                } else if (scrollJob?.isActive != true) {
                    scrollJob = launch {
                        var previous = withFrameNanos { it }
                        while (velocity() != 0f) {
                            val now = withFrameNanos { it }
                            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                            previous = now
                            val delta = velocity() * seconds
                            if (listState != null) listState.scrollBy(delta) else gridState!!.scrollBy(delta)
                            update()
                        }
                    }
                }
            }
            fun finish() {
                scrollJob?.cancel()
                scrollJob = null
                range = null
            }
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val anchor = itemAt(press.position, nearest = false) ?: return@awaitEachGesture
                    pointer = press.position
                    range = DragSelectionRange(paths, anchor, latestSelection)
                    haptic()
                    update()
                    updateAutoScroll()
                    try {
                        while (true) {
                            // Own motion and release before the child's click or scroll recognizer.
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == press.id }
                                ?: break
                            if (change.isConsumed) break
                            change.consume()
                            if (!change.pressed) break
                            pointer = change.position
                            update()
                            updateAutoScroll()
                            awaitPointerEvent(PointerEventPass.Final)
                        }
                    } finally {
                        finish()
                    }
                }
            } finally {
                finish()
            }
        }
    }
}
