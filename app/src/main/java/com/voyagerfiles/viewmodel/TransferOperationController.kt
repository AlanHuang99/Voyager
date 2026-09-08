package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.TransferCancellation
import com.voyagerfiles.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the active operation independently of an Activity's lifecycle. */
class TransferOperationController(
    private val startForeground: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow<OperationState>(OperationState.Idle)
    val state = mutableState.asStateFlow()
    private val mutableResult = MutableStateFlow<OperationResult?>(null)
    val lastResult = mutableResult.asStateFlow()
    val conflicts = TransferConflictDecisions()
    private var reportedFailure: Throwable? = null
    private var nextId = 0L
    private var cancellation: TransferCancellation? = null

    fun launch(
        label: UiText,
        onFailure: (Throwable) -> Unit,
        onFinished: () -> Unit,
        cancellable: Boolean = true,
        block: suspend () -> Unit,
    ): Boolean {
        if (mutableState.value is OperationState.Running) return false
        mutableResult.value = null
        conflicts.reset()
        reportedFailure = null
        val token = TransferCancellation()
        cancellation = token
        mutableState.value = OperationState.Running(TransferProgress(label), id = ++nextId, cancellable = cancellable)
        try {
            startForeground()
        } catch (error: Throwable) {
            cancellation = null
            finish(OperationOutcome.FAILED)
            mutableState.value = OperationState.Idle
            onFailure(error)
            return true
        }
        scope.launch(token.contextElement()) {
            try {
                block()
                TransferCancellation.check()
            } catch (error: Throwable) {
                reportedFailure = error
                onFailure(if (token.isRequested) CancellationException("Transfer cancelled") else error)
            } finally {
                finish(when {
                    token.isRequested || reportedFailure is CancellationException -> OperationOutcome.CANCELLED
                    reportedFailure != null -> OperationOutcome.FAILED
                    else -> OperationOutcome.COMPLETED
                })
                cancellation = null
                conflicts.reset()
                mutableState.value = OperationState.Idle
                onFinished()
            }
        }
        return true
    }

    /** Final settings commits must finish once accepted; all copying remains cancellable. */
    suspend fun beginCommit() = withContext(Dispatchers.Main.immediate) {
        TransferCancellation.check()
        val running = mutableState.value as? OperationState.Running ?: error("No active operation")
        mutableState.value = running.copy(cancellable = false)
    }

    fun recordFailure(error: Throwable) {
        if (mutableState.value is OperationState.Running && reportedFailure == null) reportedFailure = error
    }

    fun dismissResult() { mutableResult.value = null }

    private fun finish(outcome: OperationOutcome) {
        val progress = (mutableState.value as? OperationState.Running)?.progress ?: return
        mutableResult.value = OperationResult(progress.copy(currentItemName = null), outcome)
    }

    fun update(progress: TransferProgress) {
        TransferCancellation.check()
        val running = mutableState.value as? OperationState.Running ?: return
        mutableState.value = running.copy(progress = progress)
    }

    fun cancel(expectedId: Long? = null) {
        val running = mutableState.value as? OperationState.Running ?: return
        if (!running.cancellable) return
        if (expectedId != null && expectedId != running.id) return
        cancellation?.cancel()
        mutableState.value = running.copy(cancelling = true)
    }
}
