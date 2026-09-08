package com.voyagerfiles.data.repository

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement

/** Cooperative cancellation lets providers finish rollback before the operation exits. */
class TransferCancellation {
    private val requested = AtomicBoolean(false)
    val isRequested: Boolean get() = requested.get()

    fun cancel() { requested.set(true) }
    fun contextElement() = current.asContextElement(this)

    companion object {
        private val current = ThreadLocal<TransferCancellation?>()

        fun check() {
            if (current.get()?.isRequested == true) throw CancellationException("Transfer cancelled")
        }
    }
}
