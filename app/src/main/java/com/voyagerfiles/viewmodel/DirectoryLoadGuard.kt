package com.voyagerfiles.viewmodel

class DirectoryLoadGuard {
    private var latestRequestId = 0L
    private var latestSessionId: String? = null

    @Synchronized
    fun nextRequest(sessionId: String?): Long {
        latestSessionId = sessionId
        latestRequestId += 1
        return latestRequestId
    }

    @Synchronized
    fun isCurrent(requestId: Long, sessionId: String?): Boolean =
        requestId == latestRequestId && sessionId == latestSessionId
}
