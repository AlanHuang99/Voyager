package com.voyagerfiles.viewmodel

/** First visible item of a directory listing and how far it is scrolled past the top, in pixels. */
data class ScrollPosition(val index: Int = 0, val offset: Int = 0) {
    companion object {
        val TOP = ScrollPosition()
    }
}

/** Remembers how far each directory of a browser session was scrolled, so that returning to a directory picks up where the user left it while entering a directory afresh starts at the top. */
class DirectoryScrollMemory {
    private data class Key(val sessionId: String?, val path: String)

    private val remembered = HashMap<Key, ScrollPosition>()

    /** Position of the directory on screen, as last reported by the UI. */
    var current: ScrollPosition = ScrollPosition.TOP
        private set

    fun update(position: ScrollPosition) {
        current = position
    }

    fun leave(sessionId: String?, path: String) {
        remembered[Key(sessionId, path)] = current
    }

    /** Makes [path] the directory on screen and returns where it starts: the remembered position when [returning] to it, otherwise the top. Either way the directory's memory is consumed. */
    fun enter(sessionId: String?, path: String, returning: Boolean): ScrollPosition {
        val stored = remembered.remove(Key(sessionId, path))
        current = if (returning) stored ?: ScrollPosition.TOP else ScrollPosition.TOP
        return current
    }

    fun forgetSession(sessionId: String) {
        remembered.keys.removeAll { it.sessionId == sessionId }
    }
}
