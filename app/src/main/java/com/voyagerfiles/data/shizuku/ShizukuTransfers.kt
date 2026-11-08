package com.voyagerfiles.data.shizuku

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.EINTR
import android.system.OsConstants.POLLIN
import android.system.StructPollfd
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Calls a client must make; [check] verifies the caller, [release] frees the operation slot exactly once. */
internal class TransferHooks(val check: () -> Unit, val describe: (Throwable) -> String, val release: () -> Unit)

/**
 * Streams bytes from [produce] to the client through a pipe. The producer thread owns its source and the
 * pipe's write end; a client that stops reading closes its end, which ends the producer with EPIPE.
 */
internal class ServiceRead(
    private val hooks: TransferHooks,
    private val produce: (OutputStream) -> Unit,
) : IShizukuRead.Stub() {
    private val pipe = ParcelFileDescriptor.createPipe()
    private val done = CountDownLatch(1)
    private var taken = false
    @Volatile private var written = 0L
    @Volatile private var failure: Throwable? = null

    init {
        thread(name = "shizuku-read", isDaemon = true) {
            try {
                FileOutputStream(pipe[1].fileDescriptor).let { raw ->
                    produce(object : OutputStream() {
                        override fun write(byte: Int) { raw.write(byte); written++ }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) { raw.write(bytes, offset, length); written += length }
                    })
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                runCatching { pipe[1].close() }
                done.countDown()
                hooks.release()
            }
        }
        // A client that never takes its end would leave the producer blocked on a full pipe.
        timer.schedule(Runnable { synchronized(this) { if (!taken) { taken = true; runCatching { pipe[0].close() } } } }, IDLE_SECONDS, TimeUnit.SECONDS)
    }

    @Synchronized override fun output(): ParcelFileDescriptor {
        hooks.check()
        check(!taken) { "Read already taken" }
        taken = true
        return handOver(pipe[0])
    }

    override fun finish(): Long {
        hooks.check()
        if (!awaitQuietly(done)) throw IllegalStateException("Shizuku read timed out")
        failure?.let { throw IllegalStateException(hooks.describe(it)) }
        return written
    }
}

/**
 * A staged replacement. The pump copies the client's pipe into the payload. EOF only drains: [commit]
 * replaces the target, and everything else ([abort], client death, an idle client, service shutdown)
 * discards the stage. The payload descriptor closes only after the pump stops, so its number can't be
 * reused by another file while a write is still in flight.
 */
internal class ServiceWrite(
    private val hooks: TransferHooks,
    private val staged: ShizukuFileOperations.StagedWrite,
    private val client: IBinder,
) : IShizukuWrite.Stub(), IBinder.DeathRecipient {
    private enum class State { OPEN, COMMITTING, DONE, ABORTED }

    private val pipe = ParcelFileDescriptor.createPipe()
    private val drained = CountDownLatch(1)
    private var state = State.OPEN
    private var taken = false
    private var finished = false
    @Volatile private var failure: Throwable? = null
    /** Set on abort; the pump polls it, so a client that keeps its pipe open can't hold the slot. */
    @Volatile private var stopped = false

    init {
        try {
            client.linkToDeath(this, 0)
        } catch (error: Throwable) {
            pipe.forEach { runCatching { it.close() } }
            throw error
        }
        thread(name = "shizuku-write", isDaemon = true) {
            try {
                FileInputStream(pipe[0].fileDescriptor).let { input ->
                    val output = FileOutputStream(staged.payload.fd)
                    val buffer = ByteArray(BUFFER_BYTES)
                    val readable = StructPollfd().apply { fd = pipe[0].fileDescriptor; events = POLLIN.toShort() }
                    while (!stopped) {
                        readable.revents = 0
                        val ready = try { Os.poll(arrayOf(readable), POLL_MILLIS) } catch (error: ErrnoException) {
                            if (error.errno == EINTR) continue
                            throw error
                        }
                        if (ready == 0) continue
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                runCatching { pipe[0].close() }
                drained.countDown()
                timer.schedule(Runnable { synchronized(this) { if (state == State.OPEN) abortLocked() } }, IDLE_SECONDS, TimeUnit.SECONDS)
                synchronized(this) { if (state == State.ABORTED) finishLocked() }
            }
        }
        timer.schedule(Runnable { synchronized(this) { if (!taken && state == State.OPEN) abortLocked() } }, IDLE_SECONDS, TimeUnit.SECONDS)
    }

    @Synchronized override fun input(): ParcelFileDescriptor {
        hooks.check()
        check(!taken && state == State.OPEN) { "Write already taken" }
        taken = true
        return handOver(pipe[1])
    }

    override fun commit() {
        hooks.check()
        synchronized(this) {
            check(state == State.OPEN) { if (state == State.ABORTED) "Write was cancelled" else "Write already committed" }
            state = State.COMMITTING
        }
        // Wait outside the lock so abort() can still end a stuck write.
        val complete = awaitQuietly(drained)
        synchronized(this) {
            try {
                if (state == State.ABORTED) throw IllegalStateException("Write was cancelled")
                if (!complete) throw IllegalStateException("Shizuku write timed out")
                failure?.let { throw IllegalStateException(hooks.describe(it)) }
                staged.commit()
                state = State.DONE
            } catch (error: Throwable) {
                abortLocked()
                throw if (error is IllegalStateException) error else IllegalStateException(hooks.describe(error))
            } finally {
                if (state == State.DONE) { staged.discard(); finishLocked() }
            }
        }
    }

    override fun abort() {
        hooks.check()
        synchronized(this) { abortLocked() }
    }

    override fun binderDied() { synchronized(this) { abortLocked() } }

    /** Service shutdown. */
    fun terminate() { synchronized(this) { abortLocked() } }

    private fun abortLocked() {
        if (state == State.DONE || state == State.ABORTED) return
        state = State.ABORTED
        stopped = true
        // An untaken write end would keep the pump waiting for EOF forever.
        if (!taken) { taken = true; runCatching { pipe[1].close() } }
        staged.discard()
        if (drained.count == 0L) finishLocked()
    }

    private fun finishLocked() {
        if (finished) return
        finished = true
        runCatching { client.unlinkToDeath(this, 0) }
        staged.close()
        hooks.release()
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val POLL_MILLIS = 1000
    }
}

/** Gives the client its own copy of [end] and closes the service's. A checked exception must not escape a transaction. */
private fun handOver(end: ParcelFileDescriptor): ParcelFileDescriptor = try {
    ParcelFileDescriptor.dup(end.fileDescriptor)
} catch (error: java.io.IOException) {
    throw IllegalStateException(error.message ?: "Could not pass the transfer pipe")
} finally {
    runCatching { end.close() }
}

/** A checked InterruptedException must not escape a transaction either. */
private fun awaitQuietly(latch: CountDownLatch): Boolean = try {
    latch.await(IDLE_SECONDS, TimeUnit.SECONDS)
} catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw IllegalStateException("Shizuku transfer interrupted")
}

private const val IDLE_SECONDS = 30L
private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "shizuku-transfer-timeout").apply { isDaemon = true } }
