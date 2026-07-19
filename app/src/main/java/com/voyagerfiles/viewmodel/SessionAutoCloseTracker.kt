package com.voyagerfiles.viewmodel

class SessionAutoCloseTracker {
    private var backgroundedAtMillis: Long? = null

    fun onBackgrounded(nowMillis: Long) {
        backgroundedAtMillis = nowMillis
    }

    fun onForegrounded(
        nowMillis: Long,
        enabled: Boolean,
        timeoutMillis: Long,
        operationRunning: Boolean,
    ): SessionAutoCloseDecision {
        val backgroundedAt = backgroundedAtMillis ?: return SessionAutoCloseDecision.KEEP_OPEN
        backgroundedAtMillis = null
        if (!enabled) return SessionAutoCloseDecision.KEEP_OPEN

        val elapsedMillis = nowMillis - backgroundedAt
        if (elapsedMillis < timeoutMillis || elapsedMillis < 0L) {
            return SessionAutoCloseDecision.KEEP_OPEN
        }
        return if (operationRunning) {
            SessionAutoCloseDecision.DEFER_UNTIL_OPERATION_FINISHES
        } else {
            SessionAutoCloseDecision.CLOSE_NOW
        }
    }
}

enum class SessionAutoCloseDecision {
    KEEP_OPEN,
    CLOSE_NOW,
    DEFER_UNTIL_OPERATION_FINISHES,
}
