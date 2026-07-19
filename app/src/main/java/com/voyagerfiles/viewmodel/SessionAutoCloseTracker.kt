package com.voyagerfiles.viewmodel

class SessionAutoCloseTracker {
    private var backgroundedAtMillis: Long? = null
    private var pendingAfterOperation = false

    fun onBackgrounded(nowMillis: Long) {
        backgroundedAtMillis = nowMillis
    }

    fun onForegrounded(
        nowMillis: Long,
        enabled: Boolean,
        timeoutMillis: Long,
        operationRunning: Boolean,
    ): Boolean {
        val backgroundedAt = backgroundedAtMillis ?: return false
        backgroundedAtMillis = null
        if (!enabled) {
            pendingAfterOperation = false
            return false
        }

        val elapsedMillis = nowMillis - backgroundedAt
        if (elapsedMillis < timeoutMillis || elapsedMillis < 0L) return false
        if (operationRunning) {
            pendingAfterOperation = true
            return false
        }
        return true
    }

    fun consumePendingAfterOperation(): Boolean {
        val pending = pendingAfterOperation
        pendingAfterOperation = false
        return pending
    }

    fun cancelPending() {
        pendingAfterOperation = false
    }
}
