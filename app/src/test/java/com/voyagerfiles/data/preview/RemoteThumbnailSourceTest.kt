package com.voyagerfiles.data.preview

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.data.repository.TransferAbortable
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteThumbnailSourceTest {
    @Test
    fun oversizedMetadataSkipsTheProviderBeforeOpeningAnyConnection() = runBlocking {
        val opened = AtomicInteger()
        val source = RemoteThumbnailSource { opened.incrementAndGet(); error("Must not open") }
        try {
            assertTrue(runCatching { source.load(image(size = 9L * 1024 * 1024)) { it } }.isFailure)
            assertEquals(0, opened.get())
        } finally {
            source.close()
        }
    }

    @Test
    fun readsAndTransformsOffTheCallingThreadAndReusesItsReaders() = runBlocking {
        val callingThread = Thread.currentThread()
        val opened = AtomicInteger()
        val source = RemoteThumbnailSource {
            opened.incrementAndGet()
            provider {
                assertFalse(callingThread === Thread.currentThread())
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }
        }
        try {
            repeat(3) {
                assertArrayEquals(byteArrayOf(1, 2, 3), source.load(image()) {
                    assertFalse(callingThread === Thread.currentThread())
                    it
                })
            }
            assertTrue(opened.get() <= 2)
        } finally {
            source.close()
        }
    }

    @Test
    fun unknownSizeReadsAtMostTheCapPlusOneAndAbortsWithoutDraining() = runBlocking {
        val count = AtomicInteger()
        val aborted = AtomicInteger()
        val input = object : InputStream(), TransferAbortable {
            override fun read(): Int { count.incrementAndGet(); return 1 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                count.addAndGet(length)
                bytes.fill(1, offset, offset + length)
                return length
            }
            override fun abortTransfer() { aborted.incrementAndGet() }
        }
        val source = RemoteThumbnailSource { provider { input } }
        try {
            assertTrue(runCatching { source.load(image(size = 0)) { it } }.isFailure)
            assertEquals(8 * 1024 * 1024 + 1, count.get())
            assertEquals(1, aborted.get())
        } finally {
            source.close()
        }
    }

    @Test
    fun cancellationAbortsABlockedReadAndAllowsLaterRequests() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val opens = AtomicInteger()
        val source = RemoteThumbnailSource {
            provider {
                if (opens.incrementAndGet() == 1) blockingInput(entered, released, closed)
                else ByteArrayInputStream(byteArrayOf(4, 5))
            }
        }
        try {
            val read = async { source.load(image()) { it } }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            withTimeout(5_000) { read.cancelAndJoin() }
            withContext(Dispatchers.IO) { assertTrue(closed.await(5, TimeUnit.SECONDS)) }
            assertArrayEquals(byteArrayOf(4, 5), withTimeout(5_000) { source.load(image()) { it } })
        } finally {
            released.countDown()
            source.close()
        }
    }

    @Test
    fun closingTheSessionCancelsReadersAndRejectsNewWork() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val source = RemoteThumbnailSource { provider { blockingInput(entered, released, closed) } }
        try {
            val read = async { runCatching { source.load(image()) { it } } }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            source.close()
            withTimeout(5_000) { assertTrue(read.await().isFailure) }
            withContext(Dispatchers.IO) { assertTrue(closed.await(5, TimeUnit.SECONDS)) }
            assertTrue(runCatching { source.load(image()) { it } }.isFailure)
        } finally {
            released.countDown()
            source.close()
        }
    }

    @Test
    fun requestDeadlineAbortsAStalledStream() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val source = RemoteThumbnailSource(requestTimeoutMillis = 1_000) {
            provider { blockingInput(entered, released, closed) }
        }
        try {
            assertTrue(withTimeout(5_000) { runCatching { source.load(image()) { it } }.isFailure })
            withContext(Dispatchers.IO) { assertTrue(closed.await(5, TimeUnit.SECONDS)) }
        } finally {
            released.countDown()
            source.close()
        }
    }

    @Test
    fun sessionsShareATwoRequestLimit() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val sources = List(3) {
            RemoteThumbnailSource {
                provider {
                    ByteArrayInputStream(byteArrayOf(1))
                }
            }
        }
        try {
            val requests = sources.flatMap { source ->
                List(2) {
                    async {
                        source.load(image()) {
                            val concurrent = active.incrementAndGet()
                            maximum.accumulateAndGet(concurrent, ::maxOf)
                            entered.countDown()
                            try {
                                check(release.await(5, TimeUnit.SECONDS))
                                it
                            } finally { active.decrementAndGet() }
                        }
                    }
                }
            }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            assertEquals(2, maximum.get())
            release.countDown()
            withTimeout(5_000) { requests.awaitAll() }
            assertEquals(2, maximum.get())
        } finally {
            release.countDown()
            sources.forEach(RemoteThumbnailSource::close)
        }
    }

    private fun image(size: Long = 3) = FileItem("photo.jpg", "/photo.jpg", false, size, source = FileSource.SMB)

    private fun provider(open: () -> InputStream): FileProvider = object : FileProvider by LocalFileProvider() {
        override suspend fun getInputStream(path: String) = Result.success(open())
    }

    private fun blockingInput(entered: CountDownLatch, released: CountDownLatch, closed: CountDownLatch) =
        object : InputStream(), TransferAbortable {
            override fun read(): Int {
                entered.countDown()
                check(released.await(5, TimeUnit.SECONDS))
                return -1
            }
            override fun abortTransfer() { released.countDown() }
            override fun close() { closed.countDown() }
        }
}
