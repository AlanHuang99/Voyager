package com.voyagerfiles.playback

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class PlaybackEntry(
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val source: PlaybackRandomAccessSource,
)

internal class PlaybackTokenStore(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: SecureRandom = SecureRandom(),
    private val inactivityMillis: Long = DEFAULT_INACTIVITY_MILLIS,
) {
    private class StoredEntry(
        val entry: PlaybackEntry,
        var lastAccessMillis: Long,
        var closed: Boolean = false,
        var readers: Int = 0,
    )

    private val entries = ConcurrentHashMap<String, StoredEntry>()

    init {
        require(inactivityMillis > 0) { "Playback inactivity timeout must be positive" }
    }

    fun register(entry: PlaybackEntry): String {
        require(entry.size >= 0) { "Playback size must not be negative" }
        while (true) {
            val token = ByteArray(TOKEN_BYTES)
                .also(random::nextBytes)
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            if (entries.putIfAbsent(token, StoredEntry(entry, clock())) == null) return token
        }
    }

    fun lookup(token: String): PlaybackEntry? {
        val stored = entries[token] ?: return null
        var expiredSource: PlaybackRandomAccessSource? = null
        synchronized(stored) {
            if (stored.closed) return null
            val now = clock()
            val elapsed = now - stored.lastAccessMillis
            if (stored.readers == 0 && elapsed >= inactivityMillis && elapsed >= 0) {
                stored.closed = true
                expiredSource = stored.entry.source
            } else {
                stored.lastAccessMillis = now
                return stored.entry
            }
        }
        entries.remove(token, stored)
        runCatching { expiredSource?.close() }
        return null
    }

    fun acquire(token: String): Lease? {
        val stored = entries[token] ?: return null
        synchronized(stored) {
            if (lookup(token) == null) return null
            stored.readers++
        }
        return Lease(
            entry = stored.entry,
            onTouch = {
                synchronized(stored) {
                    if (stored.closed) false else {
                        stored.lastAccessMillis = clock()
                        true
                    }
                }
            },
            onClose = {
                synchronized(stored) {
                    stored.readers--
                    stored.lastAccessMillis = clock()
                }
            },
        )
    }

    fun sweepExpired() {
        val now = clock()
        entries.entries.toList().forEach { (token, stored) ->
            val expired = synchronized(stored) {
                val elapsed = now - stored.lastAccessMillis
                if (!stored.closed && stored.readers == 0 && elapsed >= inactivityMillis) {
                    stored.closed = true
                    true
                } else false
            }
            if (expired) {
                entries.remove(token, stored)
                runCatching { stored.entry.source.close() }
            }
        }
    }

    class Lease internal constructor(
        val entry: PlaybackEntry,
        private val onTouch: () -> Boolean,
        private val onClose: () -> Unit,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)
        fun touch(): Boolean = !released.get() && onTouch()
        override fun close() {
            if (released.compareAndSet(false, true)) onClose()
        }
    }

    fun remove(token: String) {
        entries.remove(token)?.let(::close)
    }

    fun clear() {
        entries.entries.toList().forEach { (token, stored) ->
            if (entries.remove(token, stored)) close(stored)
        }
    }

    private fun close(stored: StoredEntry) {
        val source = synchronized(stored) {
            if (stored.closed) return
            stored.closed = true
            stored.entry.source
        }
        runCatching { source.close() }
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val DEFAULT_INACTIVITY_MILLIS = 10 * 60 * 1_000L
    }
}
