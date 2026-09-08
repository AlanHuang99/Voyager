package com.voyagerfiles.data.repository

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement

/** Cooperative cancellation lets providers finish rollback before the operation exits. */
class TransferCancellation {
    private val requested = AtomicBoolean(false)
    private val aborts = ConcurrentHashMap.newKeySet<AbortRegistration>()
    val isRequested: Boolean get() = requested.get()

    fun cancel() {
        if (requested.compareAndSet(false, true)) aborts.forEach { it.dispatch() }
    }

    fun contextElement() = current.asContextElement(this)

    private class AbortRegistration(private val action: () -> Unit) : Closeable {
        private var active = true
        fun dispatch() {
            abortExecutor.execute {
                synchronized(this) {
                    if (active) {
                        active = false
                        runCatching(action)
                    }
                }
            }
        }
        override fun close() {
            synchronized(this) { active = false }
        }
    }

    companion object {
        private val current = ThreadLocal<TransferCancellation?>()
        private val abortExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "transfer-abort").apply { isDaemon = true }
        }

        /** The callback must only abort resources owned by this operation. */
        fun registerAbort(action: () -> Unit): Closeable {
            val token = current.get() ?: return Closeable { }
            val registration = AbortRegistration(action)
            token.aborts.add(registration)
            if (token.isRequested) registration.dispatch()
            return Closeable {
                registration.close()
                token.aborts.remove(registration)
            }
        }

        fun check() {
            if (current.get()?.isRequested == true) throw CancellationException("Transfer cancelled")
        }
    }
}

/** Aborts transport I/O without waiting for normal stream completion handshakes. */
interface TransferAbortable {
    fun abortTransfer()
}
