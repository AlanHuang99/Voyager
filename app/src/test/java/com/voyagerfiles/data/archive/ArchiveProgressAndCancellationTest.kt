package com.voyagerfiles.data.archive

import com.voyagerfiles.data.repository.TransferCancellation
import com.voyagerfiles.data.repository.TransferAbortable
import com.voyagerfiles.viewmodel.ArchiveProgressThrottle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArchiveProgressAndCancellationTest {

    @Test
    fun zipExtractionReportsTheSourceCopyThenOverallTotals() = runBlocking {
        val files = mapOf(
            "a.bin" to ByteArray(150_000) { 1 },
            "folder/b.bin" to ByteArray(90_000) { 2 },
            "folder/c.txt" to "small".toByteArray(),
        )
        val archive = zipBytes(files, directories = listOf("folder/"))
        val provider = workspaceWith("bundle.zip", archive)
        val progress = mutableListOf<ArchiveProgress>()

        ArchiveService.extract(
            provider,
            provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
            "/workspace",
            onProgress = progress::add,
        ).getOrThrow()

        val reading = progress.takeWhile { it.phase == ArchivePhase.READING_SOURCE }
        assertTrue(reading.isNotEmpty())
        assertEquals(archive.size.toLong(), reading.last().processedBytes)
        assertEquals(archive.size.toLong(), reading.last().totalBytes)

        val writing = progress.drop(reading.size)
        assertTrue(writing.all { it.phase == ArchivePhase.WRITING })
        assertTrue(writing.all { it.totalEntries == 4 })
        val totalBytes = files.values.sumOf { it.size.toLong() }
        assertTrue(writing.all { it.totalBytes == totalBytes })
        assertMonotonic(writing)
        assertEquals(totalBytes, writing.last().processedBytes)
        assertEquals(4, writing.last().completedEntries)
    }

    @Test
    fun streamFormatsReportProgressThroughTheCompressedSource() = runBlocking {
        val archive = gzip(tarBytes(mapOf("one.bin" to ByteArray(200_000) { 3 }, "two.bin" to ByteArray(200_000) { 4 })))
        val provider = workspaceWith("bundle.tar.gz", archive)
        val progress = mutableListOf<ArchiveProgress>()

        ArchiveService.extract(
            provider,
            provider.getFileInfo("/workspace/bundle.tar.gz").getOrThrow(),
            "/workspace",
            onProgress = progress::add,
        ).getOrThrow()

        assertTrue(progress.all { it.phase == ArchivePhase.WRITING })
        assertTrue(progress.all { it.totalBytes == archive.size.toLong() })
        assertTrue(progress.filterNot { it.isComplete }.all { it.totalEntries == null })
        assertMonotonic(progress)
        assertTrue(progress.last().processedBytes in 1..archive.size.toLong())
        assertEquals(2, progress.last().completedEntries)
        assertEquals(2, progress.last().totalEntries)
    }

    @Test
    fun tarCompletionPublishesTheFinalCountThroughTheThrottle() = runBlocking {
        val archive = tarBytes((1..10).associate { "file-$it.txt" to byteArrayOf(1) })
        val provider = workspaceWith("bundle.tar", archive)
        val throttle = ArchiveProgressThrottle(nanoTime = { 0 }, intervalNanos = Long.MAX_VALUE)
        var publishedCount = -1
        var publishedBytes = -1L

        ArchiveService.extract(
            provider,
            provider.getFileInfo("/workspace/bundle.tar").getOrThrow(),
            "/workspace",
        ) { update ->
            if (throttle.accept(update) != null) {
                publishedCount = update.completedEntries
                publishedBytes = update.processedBytes
            }
        }.getOrThrow()

        assertEquals(10, publishedCount)
        assertEquals(archive.size.toLong(), publishedBytes)
    }

    @Test
    fun cancellationDuringCompressionFinalizationRemovesTheArchive() = runBlocking {
        val token = TransferCancellation()
        val provider = object : ArchiveTestFileProvider() {
            override suspend fun getOutputStream(path: String): Result<OutputStream> =
                super.getOutputStream(path).map { output ->
                    object : OutputStream() {
                        override fun write(value: Int) = output.write(value)
                        override fun write(buffer: ByteArray, offset: Int, length: Int) =
                            output.write(buffer, offset, length)
                        override fun close() {
                            output.close()
                            token.cancel()
                        }
                    }
                }
        }.apply {
            putDirectory("/source")
            putFile("/source/one.txt", "source")
            putDirectory("/destination")
        }

        assertCancelled {
            withContext(token.contextElement()) {
                ArchiveService.createZip(
                    provider,
                    listOf(provider.getFileInfo("/source/one.txt").getOrThrow()),
                    "/destination",
                    "bundle.zip",
                )
            }
        }

        assertFalse(provider.exists("/destination/bundle.zip"))
        assertEquals("source", provider.readFile("/source/one.txt").decodeToString())
    }

    @Test
    fun cancellingExtractionDuringOutputCloseAbortsTheStream() {
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val token = TransferCancellation()
        val provider = object : ArchiveTestFileProvider() {
            override suspend fun getOutputStream(path: String): Result<OutputStream> =
                super.getOutputStream(path).map { output ->
                    object : OutputStream(), TransferAbortable {
                        @Volatile
                        private var aborted = false
                        override fun write(value: Int) = output.write(value)
                        override fun write(buffer: ByteArray, offset: Int, length: Int) =
                            output.write(buffer, offset, length)
                        override fun close() {
                            closing.countDown()
                            check(releaseClose.await(10, TimeUnit.SECONDS)) { "Output close was not released" }
                            if (aborted) throw IOException("Transport closed")
                            output.close()
                        }
                        override fun abortTransfer() {
                            aborted = true
                            releaseClose.countDown()
                        }
                    }
                }
        }.apply {
            putDirectory("/workspace")
            putFile("/workspace/bundle.zip", zipBytes(mapOf("inside.txt" to "contents".toByteArray())))
        }
        val worker = Executors.newSingleThreadExecutor()
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        try {
            worker.submit {
                try {
                    runBlocking(token.contextElement()) {
                        ArchiveService.extract(
                            provider,
                            provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
                            "/workspace",
                        ).getOrThrow()
                    }
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    finished.countDown()
                }
            }
            assertTrue("Output close did not start", closing.await(5, TimeUnit.SECONDS))
            token.cancel()
            assertTrue("Cancel did not interrupt output close", finished.await(2, TimeUnit.SECONDS))
            assertTrue("Expected cancellation, got $failure", failure is CancellationException)
            runBlocking { assertFalse(provider.exists("/workspace/bundle_extracted")) }
        } finally {
            releaseClose.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun compressionPreparesFirstAndThenReportsOverallTotals() = runBlocking {
        val provider = ArchiveTestFileProvider().apply {
            putDirectory("/source")
            putFile("/source/one.bin", ByteArray(120_000) { 5 })
            putDirectory("/source/folder")
            putFile("/source/folder/two.bin", ByteArray(80_000) { 6 })
            putDirectory("/destination")
        }
        val progress = mutableListOf<ArchiveProgress>()

        ArchiveService.createZip(
            provider,
            listOf(
                provider.getFileInfo("/source/one.bin").getOrThrow(),
                provider.getFileInfo("/source/folder").getOrThrow(),
            ),
            "/destination",
            "bundle.zip",
            onProgress = progress::add,
        ).getOrThrow()

        val preparing = progress.takeWhile { it.phase == ArchivePhase.PREPARING }
        assertEquals(listOf(1, 2, 3), preparing.map { it.completedEntries })
        assertTrue(preparing.all { it.totalEntries == null && it.totalBytes == null })

        val writing = progress.drop(preparing.size)
        assertTrue(writing.all { it.phase == ArchivePhase.WRITING && it.totalEntries == 3 && it.totalBytes == 200_000L })
        assertMonotonic(writing)
        assertEquals(200_000L, writing.last().processedBytes)
        assertEquals(3, writing.last().completedEntries)
    }

    @Test
    fun cancellingExtractionStopsMidFileAndRemovesTheFolder() = runBlocking {
        val provider = workspaceWith(
            "bundle.zip",
            zipBytes(mapOf("a.bin" to ByteArray(500_000) { 7 }, "b.bin" to ByteArray(500_000) { 8 })),
        )
        val token = TransferCancellation()
        val progress = mutableListOf<ArchiveProgress>()

        assertCancelled {
            withContext(token.contextElement()) {
                ArchiveService.extract(
                    provider,
                    provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
                    "/workspace",
                ) { update ->
                    progress += update
                    if (update.phase == ArchivePhase.WRITING && update.processedBytes > 0) token.cancel()
                }
            }
        }

        assertFalse(provider.exists("/workspace/bundle_extracted"))
        assertTrue(progress.last().processedBytes < 1_000_000)
    }

    @Test
    fun cancellingWhileCopyingTheSourceLeavesNothingBehind() = runBlocking {
        val provider = workspaceWith("bundle.zip", zipBytes(mapOf("a.bin" to ByteArray(400_000) { it.toByte() })))
        val token = TransferCancellation()

        assertCancelled {
            withContext(token.contextElement()) {
                ArchiveService.extract(
                    provider,
                    provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
                    "/workspace",
                ) { update -> if (update.phase == ArchivePhase.READING_SOURCE) token.cancel() }
            }
        }

        assertFalse(provider.exists("/workspace/bundle_extracted"))
    }

    @Test
    fun cancellingCompressionDeletesThePartialArchive() = runBlocking {
        val provider = ArchiveTestFileProvider().apply {
            putDirectory("/source")
            putFile("/source/one.bin", ByteArray(400_000) { 9 })
            putFile("/source/two.bin", ByteArray(400_000) { 10 })
            putDirectory("/destination")
        }
        val token = TransferCancellation()

        assertCancelled {
            withContext(token.contextElement()) {
                ArchiveService.createZip(
                    provider,
                    listOf(
                        provider.getFileInfo("/source/one.bin").getOrThrow(),
                        provider.getFileInfo("/source/two.bin").getOrThrow(),
                    ),
                    "/destination",
                    "bundle.zip",
                ) { update ->
                    if (update.phase == ArchivePhase.WRITING && update.processedBytes > 0) token.cancel()
                }
            }
        }

        assertFalse(provider.exists("/destination/bundle.zip"))
    }

    @Test
    fun streamAbortedByCancellationIsReportedAsCancelledNotCorrupt() = runBlocking {
        val token = TransferCancellation()
        val archive = gzip(tarBytes(mapOf("one.bin" to ByteArray(200_000) { 11 })))
        val provider = object : ArchiveTestFileProvider() {
            override suspend fun getInputStream(path: String): Result<InputStream> = Result.success(
                object : InputStream() {
                    private var remaining = archive.inputStream()
                    override fun read(): Int = remaining.read()
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining.available() < archive.size / 2) {
                            // Mimics a transport that the abort closed underneath the reader.
                            token.cancel()
                            throw IOException("Socket closed")
                        }
                        return remaining.read(buffer, offset, minOf(length, 8_192))
                    }
                }
            )
        }.apply {
            putDirectory("/workspace")
            putFile("/workspace/bundle.tar.gz", archive)
        }

        var report: ArchiveExtractionReport? = null
        assertCancelled {
            withContext(token.contextElement()) {
                ArchiveService.extract(
                    provider,
                    provider.getFileInfo("/workspace/bundle.tar.gz").getOrThrow(),
                    "/workspace",
                    onReport = { report = it },
                )
            }
        }
        // one.bin was written before the transport closed, so it is kept for the user to decide.
        assertTrue(provider.exists("/workspace/bundle_extracted/one.bin"))
        assertFalse(report!!.complete)
    }

    private suspend fun assertCancelled(block: suspend () -> Result<*>) {
        try {
            val result = block()
            fail("Expected cancellation but got $result: ${result.exceptionOrNull()}")
        } catch (expected: CancellationException) {
            assertNull(expected.cause?.takeIf { it is ArchiveException })
        }
    }

    private fun assertMonotonic(progress: List<ArchiveProgress>) {
        progress.zipWithNext().forEach { (previous, next) ->
            assertTrue("bytes went backwards: $previous -> $next", next.processedBytes >= previous.processedBytes)
            assertTrue("entries went backwards: $previous -> $next", next.completedEntries >= previous.completedEntries)
        }
    }

    private fun workspaceWith(name: String, bytes: ByteArray) = ArchiveTestFileProvider().apply {
        putDirectory("/workspace")
        putFile("/workspace/$name", bytes)
    }

    private fun zipBytes(files: Map<String, ByteArray>, directories: List<String> = emptyList()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipArchiveOutputStream(output).use { zip ->
            directories.forEach { name ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.closeArchiveEntry()
            }
            files.forEach { (name, contents) ->
                zip.putArchiveEntry(ZipArchiveEntry(name).apply { size = contents.size.toLong() })
                zip.write(contents)
                zip.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun tarBytes(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            files.forEach { (name, contents) ->
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = contents.size.toLong() })
                tar.write(contents)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun gzip(contents: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { it.write(contents) }
        return output.toByteArray()
    }
}
