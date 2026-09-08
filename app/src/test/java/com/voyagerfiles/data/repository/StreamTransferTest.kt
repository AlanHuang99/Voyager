package com.voyagerfiles.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async

class StreamTransferTest {

    @Test
    fun cancellationUnblocksSocketReadWithoutWaitingForServer() = kotlinx.coroutines.runBlocking {
        val server = java.net.ServerSocket(0)
        val source = java.net.Socket("127.0.0.1", server.localPort)
        val peer = server.accept()
        val token = TransferCancellation()
        val started = java.util.concurrent.CountDownLatch(1)
        try {
            val task = async(kotlinx.coroutines.Dispatchers.IO + token.contextElement()) {
                runCatching {
                    StreamTransfer.copy(source.getInputStream(), ByteArrayOutputStream(), "stalled", null) {
                        started.countDown()
                    }
                }
            }
            peer.getOutputStream().write(byteArrayOf(1))
            peer.getOutputStream().flush()
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val before = System.nanoTime()
            token.cancel()
            assertTrue("Cancel must not wait on socket I/O", System.nanoTime() - before < 500_000_000L)
            val result = kotlinx.coroutines.withTimeout(3_000) { task.await() }
            assertTrue(result.isFailure)
        } finally {
            source.close()
            peer.close()
            server.close()
        }
    }

    @Test
    fun reportsMonotonicProgressAfterSuccessfulWrites() {
        val payload = ByteArray(2 * 64 * 1024 + 17) { index -> (index % 251).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<StreamTransferProgress>()
        var currentTime = 1_000_000L

        StreamTransfer.copy(
            input = ByteArrayInputStream(payload),
            output = output,
            path = "/remote/large.bin",
            totalBytes = payload.size.toLong(),
            nanoTime = {
                currentTime += 1_000L
                currentTime
            },
            onProgress = progress::add,
        )

        assertTrue(progress.size >= 3)
        assertTrue(
            progress.zipWithNext().all { (first, second) ->
                first.bytesTransferred < second.bytesTransferred
            },
        )
        assertTrue(progress.all { it.elapsedNanos > 0 })
        assertTrue(
            progress.zipWithNext().all { (first, second) ->
                first.elapsedNanos <= second.elapsedNanos
            },
        )
        assertTrue(progress.all { it.path == "/remote/large.bin" })
        assertTrue(progress.all { it.totalBytes == payload.size.toLong() })
        assertEquals(payload.size.toLong(), progress.last().bytesTransferred)
        assertEquals(payload.toList(), output.toByteArray().toList())
    }

    @Test
    fun doesNotReportBytesBeforeAFailingWriteCompletes() {
        val progress = mutableListOf<StreamTransferProgress>()
        val output = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("simulated write failure")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("simulated write failure")
            }
        }

        assertThrows(IOException::class.java) {
            StreamTransfer.copy(
                input = ByteArrayInputStream("contents".toByteArray()),
                output = output,
                path = "/remote/report.txt",
                totalBytes = 8,
                onProgress = progress::add,
            )
        }
        assertTrue(progress.isEmpty())
    }

    @Test
    fun emptyStreamReportsKnownZeroTotal() {
        val progress = mutableListOf<StreamTransferProgress>()

        StreamTransfer.copy(
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            path = "/remote/empty.txt",
            totalBytes = 0,
            nanoTime = { 42L },
            onProgress = progress::add,
        )

        assertEquals(
            listOf(
                StreamTransferProgress(
                    path = "/remote/empty.txt",
                    bytesTransferred = 0,
                    totalBytes = 0,
                    elapsedNanos = 0,
                ),
            ),
            progress,
        )
    }
}
