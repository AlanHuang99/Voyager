package com.voyagerfiles.playback

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PlaybackServiceKind { MEDIA, DATA }

internal class PlaybackLifetimeStore(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pendingMillis: Long = 2 * 60 * 1_000L,
    private val probeMillis: Long = 5_000L,
) {
    private class Record(
        val id: Long,
        val entry: PlaybackEntry,
        val kind: PlaybackServiceKind,
        val group: String?,
        var expires: Long,
        val finish: () -> Unit,
    ) {
        val ready = CompletableDeferred<Unit>()
        var service: Any? = null
        var readers = 0
    }

    class Preparation internal constructor(val id: Long, val kind: PlaybackServiceKind, val ready: Deferred<Unit>)

    private val lock = Any()
    private var nextId = 0L
    private val records = mutableMapOf<Long, Record>()
    private val byEntry = IdentityHashMap<PlaybackEntry, Record>()
    private val _kinds = MutableStateFlow<Set<PlaybackServiceKind>>(emptySet())
    val kinds = _kinds.asStateFlow()

    fun prepare(entry: PlaybackEntry, group: String? = null, finish: () -> Unit): Preparation = synchronized(lock) {
        check(!byEntry.containsKey(entry)) { "Playback source is already prepared" }
        val kind = if (entry.mimeType.startsWith("audio/") || entry.mimeType.startsWith("video/")) {
            PlaybackServiceKind.MEDIA
        } else PlaybackServiceKind.DATA
        val record = Record(++nextId, entry, kind, group, clock() + pendingMillis, finish)
        records[record.id] = record
        byEntry[entry] = record
        publish()
        Preparation(record.id, kind, record.ready)
    }

    fun started(id: Long, service: Any) = synchronized(lock) {
        records[id]?.let {
            it.service = service
            it.ready.complete(Unit)
        }
        Unit
    }

    fun acquire(entry: PlaybackEntry): AutoCloseable? = synchronized(lock) {
        val record = byEntry[entry] ?: return null
        if (record.service == null || (record.readers == 0 && clock() >= record.expires)) return null
        record.readers++
        val closed = AtomicBoolean(false)
        AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(lock) {
                if (records[record.id] === record) {
                    record.readers--
                    if (record.readers == 0) record.expires = clock() + probeMillis
                }
            }
        }
    }

    fun cancel(id: Long) = removeWhere(IllegalStateException("Playback launch was cancelled")) { it.id == id }

    fun cancelGroup(group: String) = removeWhere(IllegalStateException("Playback was revoked")) { it.group == group }

    fun fail(kind: PlaybackServiceKind, error: Throwable) = removeWhere(error) { it.kind == kind }

    fun serviceLost(service: Any) = removeWhere(IllegalStateException("Playback service stopped")) { it.service === service }

    fun sweep() = removeWhere(IllegalStateException("Playback launch expired")) {
        it.readers == 0 && clock() >= it.expires
    }

    fun clear() = removeWhere(IllegalStateException("Playback ended")) { true }

    private fun removeWhere(error: Throwable, predicate: (Record) -> Boolean) {
        val removed = synchronized(lock) {
            records.values.filter(predicate).also { removed ->
                removed.forEach {
                    records.remove(it.id)
                    byEntry.remove(it.entry)
                    it.ready.completeExceptionally(error)
                }
                publish()
            }
        }
        removed.forEach { runCatching(it.finish) }
    }

    private fun publish() { _kinds.value = records.values.map { it.kind }.toSet() }
}
