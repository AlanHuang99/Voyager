package com.voyagerfiles.data.remote.saf

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.voyagerfiles.data.repository.TransferAbortable
import com.voyagerfiles.data.repository.TransferCancellation
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Keeps pipe/socket backpressure outside blocking writes so cancellation can reach rollback. */
internal class SafOutputStream(
    private val descriptor: ParcelFileDescriptor,
    useNonBlockingFlag: Boolean = Build.VERSION.SDK_INT >= 30,
) : OutputStream(), TransferAbortable {
    private val fd = descriptor.fileDescriptor
    private val mode = Os.fstat(fd).st_mode
    private val isPipe = OsConstants.S_ISFIFO(mode)
    private val isSocket = OsConstants.S_ISSOCK(mode)
    private val nonBlockingPipe = isPipe && useNonBlockingFlag && Build.VERSION.SDK_INT >= 30
    private val aborted = AtomicBoolean(false)
    private var failed = false
    private var closed = false

    init {
        if (nonBlockingPipe && Build.VERSION.SDK_INT >= 30) {
            Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        }
    }

    override fun abortTransfer() { aborted.set(true) }

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        if (offset < 0 || count < 0 || offset > buffer.size - count) throw IndexOutOfBoundsException()
        if (closed) throw IOException("Document output is closed")
        var position = offset
        val end = offset + count
        try {
            while (position < end) {
                checkCancellation()
                // Before API 30, fcntlInt is not public. With this operation as the only writer, Linux POLLOUT reports room for a PIPE_BUF-sized write without blocking.
                if (isPipe && !nonBlockingPipe) awaitWritable()
                val length = if (isPipe && !nonBlockingPipe) minOf(end - position, PIPE_ATOMIC_BYTES) else end - position
                try {
                    val written = if (isSocket) {
                        Os.sendto(fd, buffer, position, length, MSG_DONTWAIT or MSG_NOSIGNAL, null as InetAddress?, 0)
                    } else {
                        Os.write(fd, buffer, position, length)
                    }
                    if (written == 0) throw IOException("Document output made no progress")
                    position += written
                } catch (error: ErrnoException) {
                    when (error.errno) {
                        OsConstants.EAGAIN -> awaitWritable()
                        OsConstants.EINTR -> checkCancellation()
                        else -> throw IOException("Unable to write document", error)
                    }
                }
            }
            checkCancellation()
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    private fun awaitWritable() {
        val poll = StructPollfd().apply {
            fd = this@SafOutputStream.fd
            events = OsConstants.POLLOUT.toShort()
        }
        while (true) {
            checkCancellation()
            try {
                val ready = Os.poll(arrayOf(poll), POLL_MILLIS)
                checkCancellation()
                if (ready == 0) continue
                if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                    throw IOException("Document output is no longer available")
                }
                if (poll.revents.toInt() and OsConstants.POLLOUT != 0) return
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EINTR) throw IOException("Unable to wait for document output", error)
            }
        }
    }

    private fun checkCancellation() {
        TransferCancellation.check()
        if (aborted.get()) throw CancellationException("Transfer cancelled")
    }

    override fun close() {
        if (closed) return
        closed = true
        if (failed || aborted.get()) descriptor.closeWithError("Document transfer did not complete")
        else descriptor.close()
    }

    private companion object {
        // Linux send flags are not exposed by OsConstants on the API 26 SDK surface.
        const val MSG_DONTWAIT = 0x40
        const val MSG_NOSIGNAL = 0x4000
        const val POLL_MILLIS = 100
        // Linux PIPE_BUF is 4096 on all Android-supported ABIs.
        const val PIPE_ATOMIC_BYTES = 4096
    }
}
