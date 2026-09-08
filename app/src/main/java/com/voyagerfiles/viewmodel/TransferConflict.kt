package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.repository.TransferCancellation
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransferDisposition { COMPLETED, SKIPPED }
enum class ConflictDecision { REPLACE, SKIP, CANCEL }
data class ConflictSource(val name: String, val size: Long?, val lastModified: Date?)
data class TransferConflict(val source: ConflictSource, val destination: FileItem)
data class ConflictResponse(val decision: ConflictDecision, val applyToAll: Boolean = false)
typealias ConflictResolver = suspend (TransferConflict) -> ConflictDecision

/** One instance per batch; decisions and the pending request survive Activity recreation. */
class TransferConflictDecisions {
    data class Request(val conflict: TransferConflict, internal val response: CompletableDeferred<ConflictResponse>)
    private val mutablePending = MutableStateFlow<Request?>(null)
    val pending = mutablePending.asStateFlow()
    private var allDecision: ConflictDecision? = null

    fun reset() {
        mutablePending.value?.response?.cancel()
        mutablePending.value = null
        allDecision = null
    }

    suspend fun resolve(conflict: TransferConflict): ConflictDecision {
        TransferCancellation.check()
        allDecision?.let { return it }
        val request = Request(conflict, CompletableDeferred())
        mutablePending.value = request
        val abort = TransferCancellation.registerAbort { request.response.cancel(CancellationException("Transfer cancelled")) }
        try {
            val response = request.response.await()
            TransferCancellation.check()
            if (response.decision == ConflictDecision.CANCEL) throw CancellationException("Transfer cancelled")
            if (response.applyToAll) allDecision = response.decision
            return response.decision
        } finally {
            abort.close()
            mutablePending.compareAndSet(request, null)
        }
    }

    fun respond(request: Request, response: ConflictResponse) {
        if (mutablePending.value === request) request.response.complete(response)
    }
}
