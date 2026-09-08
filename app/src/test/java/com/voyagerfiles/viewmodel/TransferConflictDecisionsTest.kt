package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.repository.TransferCancellation
import com.voyagerfiles.ui.text.UiText
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TransferConflictDecisionsTest {
    private val conflict = TransferConflict(ConflictSource("file.txt", 42, Date(1000)), FileItem("file.txt", "/target/file.txt", false, 21, Date(2000)))

    @Test
    fun applyAllReusesChoiceAndResetsForNextBatch() = runBlocking {
        for (decision in listOf(ConflictDecision.REPLACE, ConflictDecision.SKIP)) {
            val decisions = TransferConflictDecisions()
            val waiting = async { decisions.resolve(conflict) }
            val request = decisions.pending.filterNotNull().first()
            assertEquals(42L, request.conflict.source.size)
            assertEquals(21L, request.conflict.destination.size)
            decisions.respond(request, ConflictResponse(decision, true))
            assertEquals(decision, waiting.await())
            assertNull(decisions.pending.value)
            assertEquals(decision, decisions.resolve(conflict))
            decisions.reset()
            val next = async { decisions.resolve(conflict) }
            val newRequest = decisions.pending.filterNotNull().first()
            decisions.respond(request, ConflictResponse(decision))
            assertFalse(next.isCompleted)
            decisions.respond(newRequest, ConflictResponse(ConflictDecision.SKIP))
            assertEquals(ConflictDecision.SKIP, next.await())
        }
    }

    @Test
    fun individualChoicePromptsAgain() = runBlocking {
        val decisions = TransferConflictDecisions()
        repeat(2) {
            val waiting = async { decisions.resolve(conflict) }
            decisions.respond(decisions.pending.filterNotNull().first(), ConflictResponse(ConflictDecision.REPLACE))
            assertEquals(ConflictDecision.REPLACE, waiting.await())
        }
    }

    @Test
    fun tokenCancellationUnblocksPendingDecisionAndClearsDialog() = runBlocking {
        val token = TransferCancellation()
        val decisions = TransferConflictDecisions()
        val waiting = async(token.contextElement()) { runCatching { decisions.resolve(conflict) } }
        decisions.pending.filterNotNull().first()
        token.cancel()
        assertTrue(withTimeout(2000) { waiting.await() }.exceptionOrNull() is CancellationException)
        assertNull(decisions.pending.value)
    }

    @Test
    fun dialogCancelEndsControllerAsCancelledWithoutContinuingBatch() = runBlocking {
        val controller = TransferOperationController({}, this)
        var continued = false
        controller.launch(UiText.Dynamic("Copying"), {}, {}) {
            controller.update(TransferProgress(UiText.Dynamic("Copying"), completedItems = 1, totalItems = 3, skippedItems = 1))
            controller.conflicts.resolve(conflict)
            continued = true
        }
        val request = controller.conflicts.pending.filterNotNull().first()
        controller.conflicts.respond(request, ConflictResponse(ConflictDecision.CANCEL))
        yield()
        assertFalse(continued)
        assertNull(controller.conflicts.pending.value)
        assertEquals(OperationOutcome.CANCELLED, controller.lastResult.value!!.outcome)
        assertEquals(1, controller.lastResult.value!!.progress.completedItems)
        assertEquals(1, controller.lastResult.value!!.progress.skippedItems)
    }
}
