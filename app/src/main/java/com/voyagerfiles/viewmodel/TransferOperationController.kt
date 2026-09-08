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

/** Owns the active operation independently of an Activity's lifecycle. */
class TransferOperationController(
    private val startForeground: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow<OperationState>(OperationState.Idle)
    val state = mutableState.asStateFlow()
    private var nextId = 0L
    private var cancellation: TransferCancellation? = null

    fun launch(
        label: UiText,
        onFailure: (Throwable) -> Unit,
        onFinished: () -> Unit,
        block: suspend () -> Unit,
    ): Boolean {
        if (mutableState.value is OperationState.Running) return false
        val token = TransferCancellation()
        cancellation = token
        mutableState.value = OperationState.Running(TransferProgress(label), id = ++nextId, cancellable = true)
        try {
            startForeground()
        } catch (error: Throwable) {
            cancellation = null
            mutableState.value = OperationState.Idle
            onFailure(error)
            return true
        }
        scope.launch(token.contextElement()) {
            try {
                block()
                TransferCancellation.check()
            } catch (error: Throwable) {
                onFailure(if (token.isRequested) CancellationException("Transfer cancelled") else error)
            } finally {
                cancellation = null
                mutableState.value = OperationState.Idle
                onFinished()
            }
        }
        return true
    }

    fun update(progress: TransferProgress) {
        TransferCancellation.check()
        val running = mutableState.value as? OperationState.Running ?: return
        mutableState.value = running.copy(progress = progress)
    }

    fun cancel(expectedId: Long? = null) {
        val running = mutableState.value as? OperationState.Running ?: return
        if (expectedId != null && expectedId != running.id) return
        cancellation?.cancel()
        mutableState.value = running.copy(cancelling = true)
    }
}
