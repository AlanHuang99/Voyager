package com.voyagerfiles.data.remote.smb

import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SmbTransferSocketFactoryTest {
    @Test
    fun closeReleasesEveryOwnedSocketAndRejectsNewSockets() {
        val factory = SmbTransferSocketFactory()
        val first = factory.createSocket()
        val second = factory.createSocket()
        factory.close()
        factory.close()
        assertTrue(first.isClosed)
        assertTrue(second.isClosed)
        assertThrows(SocketException::class.java) { factory.createSocket() }
    }

    @Test
    fun socketCreatedConcurrentlyWithAbortIsClosedBeforeItCanConnect() {
        val socket = Socket()
        val created = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val factory = SmbTransferSocketFactory {
            created.countDown()
            check(proceed.await(3, TimeUnit.SECONDS))
            socket
        }
        try {
            val result = executor.submit<Result<Socket>> { runCatching { factory.createSocket() } }
            assertTrue(created.await(3, TimeUnit.SECONDS))
            factory.close()
            proceed.countDown()
            assertTrue(result.get(3, TimeUnit.SECONDS).exceptionOrNull() is SocketException)
            assertTrue(socket.isClosed)
        } finally {
            proceed.countDown()
            factory.close()
            socket.close()
            executor.shutdownNow()
        }
    }
}
