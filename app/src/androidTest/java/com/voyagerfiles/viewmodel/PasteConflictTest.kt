package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PasteConflictTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sameProviderMoveAppliesReplacementToWholeBatch() = batch(ConflictDecision.REPLACE)

    @Test
    fun sameProviderMoveSkipsWithoutCountingOrDeletingSources() = batch(ConflictDecision.SKIP)

    private fun batch(decision: ConflictDecision) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.cacheDir, "paste-conflicts-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "source").apply { mkdir() }
        val destination = File(root, "destination").apply { mkdir() }
        val names = listOf("one.txt", "two.txt")
        names.forEach { name -> File(source, name).writeText("new-$name"); File(destination, name).writeText("old-$name") }
        val viewModel = FileBrowserViewModel(app)
        compose.setContent {}
        try {
            compose.runOnIdle { viewModel.openLocalRoot(root.path) }
            compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == root.path && !viewModel.browseState.value.isLoading }
            compose.runOnIdle { viewModel.navigateTo(source.path) }
            compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == source.path && !viewModel.browseState.value.isLoading }
            compose.runOnIdle {
                viewModel.cutToClipboard(names.map { File(source, it).path })
                viewModel.navigateTo(destination.path)
            }
            compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == destination.path && !viewModel.browseState.value.isLoading }
            compose.runOnIdle { viewModel.paste() }
            compose.waitUntil(10_000) { viewModel.transferConflict.value != null }
            names.forEach { assertEquals("old-$it", File(destination, it).readText()) }
            compose.runOnIdle { viewModel.resolveTransferConflict(viewModel.transferConflict.value!!, ConflictResponse(decision, true)) }
            compose.waitUntil(10_000) { viewModel.operationState.value == OperationState.Idle }
            val replaced = decision == ConflictDecision.REPLACE
            names.forEach {
                assertEquals(if (replaced) "new-$it" else "old-$it", File(destination, it).readText())
                assertEquals(!replaced, File(source, it).exists())
            }
            val result = viewModel.lastOperationResult.value!!
            assertEquals(OperationOutcome.COMPLETED, result.outcome)
            assertEquals(if (replaced) 2 else 0, result.progress.completedItems)
            assertEquals(if (replaced) 0 else 2, result.progress.skippedItems)
            assertEquals(2, destination.listFiles()!!.size)
        } finally {
            compose.runOnIdle { viewModel.cancelOperation() }
            compose.waitUntil(10_000) { viewModel.operationState.value == OperationState.Idle }
            root.deleteRecursively()
        }
    }
}
