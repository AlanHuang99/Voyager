package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.TransferCancellation
import com.voyagerfiles.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TransferOperationControllerTest {
    @Test
    fun unsupportedOperationIgnoresCancelAndFinishesNormally() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        val controller = TransferOperationController({}, this)
        var failure: Throwable? = null
        controller.launch(UiText.Dynamic("Deleting"), { failure = it }, {}, cancellable = false) { finish.await() }
        assertFalse((controller.state.value as OperationState.Running).cancellable)
        controller.cancel()
        assertFalse((controller.state.value as OperationState.Running).cancelling)
        finish.complete(Unit)
        yield()
        assertNull(failure)
        assertEquals(OperationState.Idle, controller.state.value)
    }

    @Test
    fun cancellationKeepsOperationRunningUntilCleanupFinishes() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        var finished = false
        val controller = TransferOperationController({}, this)
        assertTrue(controller.launch(UiText.Dynamic("Copying"), { failure = it }, { finished = true }) {
            start.complete(Unit)
            proceed.await()
            try { TransferCancellation.check() } finally {
                cleanup.complete(Unit)
                finishCleanup.await()
            }
        })
        start.await()
        assertFalse(controller.launch(UiText.Dynamic("Second"), {}, {}) { fail("Must not start") })
        controller.cancel()
        assertTrue((controller.state.value as OperationState.Running).cancelling)
        proceed.complete(Unit)
        cleanup.await()
        assertTrue(controller.state.value is OperationState.Running)
        assertFalse(finished)
        finishCleanup.complete(Unit)
        yield()
        assertTrue(failure is CancellationException)
        assertTrue(finished)
        assertEquals(OperationState.Idle, controller.state.value)
    }

    @Test
    fun serviceStartFailureDoesNotRunFileOperation() = runBlocking {
        val error = IllegalStateException("Service unavailable")
        var failure: Throwable? = null
        val controller = TransferOperationController({ throw error }, this)
        controller.launch(UiText.Dynamic("Copying"), { failure = it }, {}) { fail("Must not run") }
        assertSame(error, failure)
        assertEquals(OperationState.Idle, controller.state.value)
    }

    @Test
    fun staleCancelDoesNotCancelNextOperation() = runBlocking {
        val controller = TransferOperationController({}, this)
        controller.launch(UiText.Dynamic("First"), {}, {}) {}
        val oldId = (controller.state.value as OperationState.Running).id
        yield()
        val finish = CompletableDeferred<Unit>()
        controller.launch(UiText.Dynamic("Second"), { throw it }, {}) { finish.await() }
        controller.cancel(oldId)
        assertFalse((controller.state.value as OperationState.Running).cancelling)
        finish.complete(Unit)
        yield()
        assertEquals(OperationState.Idle, controller.state.value)
    }
}
