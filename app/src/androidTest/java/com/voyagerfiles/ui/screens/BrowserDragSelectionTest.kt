package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowserDragSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val root = File(app.cacheDir, "drag-${UUID.randomUUID()}").apply { mkdirs() }
    private lateinit var vm: FileBrowserViewModel

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnIdle { vm.setViewMode(ViewMode.LIST) }
        root.deleteRecursively()
    }

    @Test fun listDragSelectsRangeAndReverses() = verifyRange(ViewMode.LIST)
    @Test fun compactDragSelectsRangeAndReverses() = verifyRange(ViewMode.COMPACT)
    @Test fun gridDragSelectsRangeAndReverses() = verifyRange(ViewMode.GRID)

    @Test fun gridLongPressNearCellEdgeUsesPaddedBounds() {
        show(ViewMode.GRID)
        val cell = compose.onNode(hasText("00-keep.txt") and hasClickAction())
        cell.performTouchInput { longClick(Offset(width - 2f, height - 2f)) }
        assertEquals(setOf("00-keep.txt"), selectedNames())
    }

    @Test fun filteredDragSelectsOnlyVisiblePaths() {
        show(ViewMode.LIST)
        compose.runOnIdle { vm.setSearchQuery("keep") }
        compose.waitForIdle()
        drag("00-keep.txt", "04-keep.txt")
        assertEquals(setOf("00-keep.txt", "02-keep.txt", "04-keep.txt"), selectedNames())
    }

    @Test fun edgeHoldScrollsAndExtendsSelection() {
        show(ViewMode.COMPACT, count = 100)
        val bounds = compose.onNodeWithTag("browser-files").fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithText("00-keep.txt").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val bottom = Offset(start.x, bounds.height - 5f)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("browser-files").performTouchInput {
            down(start)
            advanceEventTime(650)
            moveTo(start)
            moveTo(bottom, delayMillis = 300)
        }
        // Let frame-driven scrolling run while the pointer remains held at the bottom edge.
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithTag("browser-files").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("Edge scrolling should select beyond the initial viewport: ${selectedNames()}", selectedNames().size > 20)
    }

    private fun verifyRange(mode: ViewMode) {
        show(mode)
        drag("00-keep.txt", "04-keep.txt", reverseTo = "02-keep.txt")
        assertEquals(setOf("00-keep.txt", "01-other.txt", "02-keep.txt"), selectedNames())
    }

    private fun drag(first: String, last: String, reverseTo: String? = null) {
        val bounds = compose.onNodeWithTag("browser-files").fetchSemanticsNode().boundsInRoot
        fun point(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.center -
            compose.onNodeWithTag("browser-files").fetchSemanticsNode().boundsInRoot.topLeft
        val start = point(first)
        compose.onNodeWithTag("browser-files").performTouchInput {
            down(start)
            advanceEventTime(650)
            moveTo(start)
        }
        compose.waitForIdle()
        val end = point(last)
        val reverse = reverseTo?.let(::point)
        compose.onNodeWithTag("browser-files").performTouchInput {
            moveTo(end, delayMillis = 250)
            if (reverse != null) moveTo(reverse, delayMillis = 250)
            up()
        }
        compose.waitForIdle()
    }

    private fun selectedNames() = vm.browseState.value.selectedFiles.map { File(it).name }.toSet()

    private fun show(mode: ViewMode, count: Int = 8) {
        repeat(count) { index -> root.resolve("${index.toString().padStart(2, '0')}-${if (index % 2 == 0) "keep" else "other"}.txt").writeText("fixture") }
        vm = FileBrowserViewModel(app)
        compose.setContent { MaterialTheme { BrowserScreen(vm, onNavigateBack = {}, isTelevision = false) } }
        compose.runOnIdle { vm.setViewMode(mode); vm.openLocalRoot(root.path) }
        compose.waitUntil(10_000) { vm.browseState.value.files.size == count && !vm.browseState.value.isLoading }
        compose.waitForIdle()
    }
}
