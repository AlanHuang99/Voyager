package com.voyagerfiles.data.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.TransferAbortable
import com.voyagerfiles.data.repository.TransferCancellation
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException

/** Captures sockets before connect, so abort never needs SMBJ's transport write lock. */
internal class SmbTransferSocketFactory(
    private val newSocket: () -> Socket = { Socket() },
) : SocketFactory(), Closeable {
    private val lock = Any()
    private val sockets = mutableSetOf<Socket>()
    private var closed = false

    override fun createSocket(): Socket {
        val socket = newSocket()
        synchronized(lock) {
            if (closed) {
                socket.close()
                throw SocketException("SMB transfer transport is closed")
            }
            sockets += socket
        }
        return socket
    }

    override fun createSocket(host: String, port: Int): Socket = connect(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(host.hostAddress!!, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connect(host, port, InetSocketAddress(localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connect(host.hostAddress!!, port, InetSocketAddress(localHost, localPort))

    private fun connect(host: String, port: Int, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    override fun close() {
        val owned = synchronized(lock) {
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        owned.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 30_000
    }
}

/** Owns a separate client/session and keeps its abort hook alive through final flush and CLOSE. */
internal class SmbTransferFile(
    private val sockets: SmbTransferSocketFactory,
) : Closeable, TransferAbortable {
    private val aborted = AtomicBoolean(false)
    private val registration = TransferCancellation.registerAbort(::abortTransfer)
    private var session: SmbSessionHandle? = null
    private var share: DiskShare? = null
    private var ownedFile: com.hierynomus.smbj.share.File? = null
    val file get() = checkNotNull(ownedFile)

    fun open(factory: SmbSessionHandleFactory, connection: RemoteConnection, shareName: String, path: String, writing: Boolean) {
        try {
            checkCancellation()
            session = factory.connectTransfer(connection, sockets)
            checkCancellation()
            share = session!!.connectShare(shareName)
            checkCancellation()
            ownedFile = share!!.openFile(
                path,
                EnumSet.of(if (writing) AccessMask.GENERIC_WRITE else AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                if (writing) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            checkCancellation()
        } catch (error: Throwable) {
            abortTransfer()
            runCatching { close() }.onFailure(error::addSuppressed)
            TransferCancellation.check()
            throw error
        }
    }

    override fun abortTransfer() {
        aborted.set(true)
        // Closing the raw socket releases blocked writes and makes PacketReader fail pending response futures.
        sockets.close()
    }

    fun finishStream(closeStream: () -> Unit) {
        try {
            use {
                try {
                    checkCancellation()
                    closeStream()
                    checkCancellation()
                } catch (error: Throwable) {
                    abortTransfer()
                    throw error
                }
            }
        } catch (error: Throwable) {
            TransferCancellation.check()
            throw error
        }
    }

    private fun checkCancellation() {
        TransferCancellation.check()
        if (aborted.get()) throw CancellationException("Transfer cancelled")
    }

    override fun close() {
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                sockets.close()
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        try {
            release { ownedFile?.close() }
            ownedFile = null
            release { share?.close() }
            share = null
            release { session?.close() }
            session = null
        } finally {
            sockets.close()
            registration.close()
        }
        failure?.let { throw it }
    }
}
