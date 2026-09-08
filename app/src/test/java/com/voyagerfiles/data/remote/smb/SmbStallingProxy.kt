package com.voyagerfiles.data.remote.smb

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Passes real SMB2 authentication and file traffic, then retains a selected connection without forwarding. */
internal class SmbStallingProxy(private val serverPort: Int, private val stall: Stall) : Closeable {
    enum class Stall { WRITE_REPLY, WRITE_BODY, CLOSE_REPLY, READ_REPLY, NEGOTIATE_REPLY }

    private val listener = ServerSocket().apply {
        receiveBufferSize = 64 * 1024
        bind(InetSocketAddress("127.0.0.1", 0))
    }
    val port get() = listener.localPort
    val stalled = CountDownLatch(1)
    val release = CountDownLatch(1)
    val frames = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val armed = AtomicBoolean(false)
    private val selected = AtomicInteger(-1)
    private val nextConnection = AtomicInteger()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newCachedThreadPool()

    init {
        executor.execute {
            runCatching {
                while (!listener.isClosed) {
                    val client = listener.accept().also { sockets += it }
                    val server = Socket("127.0.0.1", serverPort).also { sockets += it }
                    val id = nextConnection.incrementAndGet()
                    executor.execute { forward(client, server, id, fromClient = true) }
                    executor.execute { forward(server, client, id, fromClient = false) }
                }
            }
        }
    }

    fun arm() { armed.set(true) }

    private fun forward(inputSocket: Socket, outputSocket: Socket, id: Int, fromClient: Boolean) {
        runCatching {
            val input = DataInputStream(inputSocket.getInputStream())
            val output = outputSocket.getOutputStream()
            var writes = 0
            while (true) {
                val header = ByteArray(4).also { input.readFully(it) }
                val length = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
                check(length in 1..(16 * 1024 * 1024))
                val prefix = ByteArray(minOf(length, 64)).also { input.readFully(it) }
                val command = if (prefix.size >= 14 && prefix[0] == 0xfe.toByte()) {
                    (prefix[12].toInt() and 255) or ((prefix[13].toInt() and 255) shl 8)
                } else -1
                frames += "$id ${if (fromClient) "request" else "reply"} $command"
                if (fromClient && command == WRITE) writes++
                if (armed.get() && fromClient) {
                    val select = when (stall) {
                        Stall.NEGOTIATE_REPLY -> command == NEGOTIATE
                        Stall.READ_REPLY -> command == READ
                        else -> command == WRITE
                    }
                    if (select) selected.compareAndSet(-1, id)
                }
                val stop = armed.get() && selected.get() == id && when (stall) {
                    Stall.WRITE_BODY -> fromClient && command == WRITE && writes == 2
                    Stall.WRITE_REPLY -> !fromClient && command == WRITE
                    Stall.CLOSE_REPLY -> !fromClient && command == CLOSE
                    Stall.READ_REPLY -> !fromClient && command == READ
                    Stall.NEGOTIATE_REPLY -> !fromClient && command == NEGOTIATE
                }
                if (stop) {
                    stalled.countDown()
                    release.await()
                }
                val rest = ByteArray(length - prefix.size).also { input.readFully(it) }
                output.write(header)
                output.write(prefix)
                output.write(rest)
                output.flush()
            }
        }
        runCatching { inputSocket.close() }
        runCatching { outputSocket.close() }
    }

    override fun close() {
        release.countDown()
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    private companion object {
        const val NEGOTIATE = 0
        const val CLOSE = 6
        const val READ = 8
        const val WRITE = 9
    }
}
