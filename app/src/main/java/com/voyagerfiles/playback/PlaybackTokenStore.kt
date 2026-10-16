package com.voyagerfiles.playback

import java.security.SecureRandom
import java.util.Base64
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
    private class Generation(val entry: PlaybackEntry) {
        var readers = 0
        var closed = false
    }

    private class StoredToken(var current: Generation, var lastAccessMillis: Long) {
        val generations = mutableSetOf(current)
        val readers get() = generations.sumOf { it.readers }
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, StoredToken>()

    init {
        require(inactivityMillis > 0) { "Playback inactivity timeout must be positive" }
    }

    fun register(entry: PlaybackEntry, stableToken: String? = null): String {
        require(entry.size >= 0) { "Playback size must not be negative" }
        val sourcesToClose = mutableListOf<PlaybackRandomAccessSource>()
        val token = synchronized(lock) {
            val token = stableToken ?: generateSequence(::randomToken).first { it !in entries }
            val stored = entries[token]
            if (stored == null) {
                entries[token] = StoredToken(Generation(entry), clock())
            } else if (stored.current.entry == entry) {
                stored.lastAccessMillis = clock()
            } else {
                val previous = stored.current
                stored.current = Generation(entry)
                stored.generations += stored.current
                stored.lastAccessMillis = clock()
                if (previous.readers == 0) retire(stored, previous, sourcesToClose)
            }
            token
        }
        closeSources(sourcesToClose)
        return token
    }

    fun lookup(token: String): PlaybackEntry? {
        val sourcesToClose = mutableListOf<PlaybackRandomAccessSource>()
        val entry = synchronized(lock) {
            liveToken(token, sourcesToClose)?.also { it.lastAccessMillis = clock() }?.current?.entry
        }
        closeSources(sourcesToClose)
        return entry
    }

    fun acquire(token: String): Lease? {
        val sourcesToClose = mutableListOf<PlaybackRandomAccessSource>()
        val lease = synchronized(lock) {
            val stored = liveToken(token, sourcesToClose)
            stored?.let {
                val generation = stored.current
                generation.readers++
                stored.lastAccessMillis = clock()
                Lease(
                    entry = generation.entry,
                    onTouch = {
                        synchronized(lock) {
                            if (generation.closed) false else {
                                stored.lastAccessMillis = clock()
                                true
                            }
                        }
                    },
                    onClose = {
                        val retiredSources = mutableListOf<PlaybackRandomAccessSource>()
                        synchronized(lock) {
                            generation.readers--
                            stored.lastAccessMillis = clock()
                            if (!generation.closed && generation !== stored.current && generation.readers == 0) {
                                retire(stored, generation, retiredSources)
                            }
                        }
                        closeSources(retiredSources)
                    },
                )
            }
        }
        closeSources(sourcesToClose)
        return lease
    }

    fun sweepExpired() {
        val sourcesToClose = mutableListOf<PlaybackRandomAccessSource>()
        synchronized(lock) {
            entries.keys.toList().forEach { liveToken(it, sourcesToClose) }
        }
        closeSources(sourcesToClose)
    }

    fun remove(token: String) {
        val sourcesToClose = mutableListOf<PlaybackRandomAccessSource>()
        synchronized(lock) {
            entries.remove(token)?.let { closeToken(it, sourcesToClose) }
        }
        closeSources(sourcesToClose)
    }

    fun clear() {
        val sourcesToClose = mutableListOf<PlaybackRandomAccessSource>()
        synchronized(lock) {
            entries.values.forEach { closeToken(it, sourcesToClose) }
            entries.clear()
        }
        closeSources(sourcesToClose)
    }

    private fun liveToken(token: String, sourcesToClose: MutableList<PlaybackRandomAccessSource>): StoredToken? {
        val stored = entries[token] ?: return null
        val elapsed = clock() - stored.lastAccessMillis
        if (stored.readers == 0 && elapsed >= inactivityMillis && elapsed >= 0) {
            entries.remove(token)
            closeToken(stored, sourcesToClose)
            return null
        }
        return stored
    }

    private fun retire(
        stored: StoredToken,
        generation: Generation,
        sourcesToClose: MutableList<PlaybackRandomAccessSource>,
    ) {
        generation.closed = true
        stored.generations -= generation
        if (stored.generations.none { it.entry.source === generation.entry.source }) {
            sourcesToClose += generation.entry.source
        }
    }

    private fun closeToken(stored: StoredToken, sourcesToClose: MutableList<PlaybackRandomAccessSource>) {
        stored.generations.toList().forEach { retire(stored, it, sourcesToClose) }
    }

    private fun closeSources(sources: List<PlaybackRandomAccessSource>) {
        sources.forEach { runCatching(it::close) }
    }

    private fun randomToken(): String = ByteArray(TOKEN_BYTES)
        .also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

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

    private companion object {
        const val TOKEN_BYTES = 32
        const val DEFAULT_INACTIVITY_MILLIS = 10 * 60 * 1_000L
    }
}
