package com.voyagerfiles.data.archive

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveStreamOpenCleanupTest {
    @Test
    fun aStreamOpenFailureThatCannotBeCleanedUpStopsSequentialExtraction() = runBlocking {
        val provider = workspace(parallelWrites = 1, failCleanup = true)
        var report: ArchiveExtractionReport? = null

        val error = extract(provider) { report = it }.exceptionOrNull()

        assertTrue("Expected partial extraction, got $error", error is PartialExtractionException)
        val cleanup = error!!.cause as ArchiveCleanupException
        assertEquals(BROKEN_PATH, cleanup.path)
        assertTrue(generateSequence(cleanup as Throwable) { it.cause }.any { it.message == "Stream open failed" })
        assertEquals("Injected delete failure for $BROKEN_PATH", cleanup.cause!!.suppressed.single().message)
        assertNotNull(report)
        assertFalse(report!!.complete)
        assertEquals(1, report!!.extractedFiles)
        assertEquals(listOf("b.txt"), report!!.notExtracted.map { it.entryPath })
        assertEquals(listOf("a.txt", "b.txt"), childNames(provider))
    }

    @Test
    fun aStreamOpenFailureThatCannotBeCleanedUpStopsParallelExtraction() = runBlocking {
        val provider = workspace(parallelWrites = 4, failCleanup = true)
        var report: ArchiveExtractionReport? = null

        val error = extract(provider) { report = it }.exceptionOrNull()

        assertTrue("Expected partial extraction, got $error", error is PartialExtractionException)
        assertEquals(BROKEN_PATH, (error!!.cause as ArchiveCleanupException).path)
        assertNotNull(report)
        assertFalse(report!!.complete)
        assertEquals(listOf("b.txt"), report!!.notExtracted.map { it.entryPath })
        val files = childNames(provider)
        assertTrue("b.txt" in files)
        assertEquals(files.size - 1, report!!.extractedFiles)
        assertEquals(0, provider.openOutputCount)
    }

    @Test
    fun aStreamOpenFailureWithSuccessfulCleanupLeavesOnlyThatEntryOut() = runBlocking {
        for (parallelWrites in listOf(1, 4)) {
            val provider = workspace(parallelWrites, failCleanup = false)
            var report: ArchiveExtractionReport? = null

            extract(provider) { report = it }.getOrThrow()

            assertTrue(report!!.complete)
            assertEquals(2, report!!.extractedFiles)
            assertEquals(listOf("b.txt"), report!!.notExtracted.map { it.entryPath })
            assertEquals(listOf("a.txt", "c.txt"), childNames(provider))
            assertEquals(0, provider.openOutputCount)
        }
    }

    private fun workspace(parallelWrites: Int, failCleanup: Boolean): ArchiveTestFileProvider =
        object : ArchiveTestFileProvider(
            failDeletePath = BROKEN_PATH.takeIf { failCleanup },
            parallelWrites = parallelWrites,
            writeDelayMillis = 1,
        ) {
            override suspend fun getOutputStream(path: String): Result<OutputStream> =
                if (path == BROKEN_PATH) Result.failure(IOException("Stream open failed"))
                else super.getOutputStream(path)
        }.apply {
            putDirectory("/workspace")
            val archive = ByteArrayOutputStream()
            ZipOutputStream(archive).use { zip ->
                for (name in listOf("a.txt", "b.txt", "c.txt")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(name.toByteArray())
                    zip.closeEntry()
                }
            }
            putFile("/workspace/bundle.zip", archive.toByteArray())
        }

    private suspend fun extract(
        provider: ArchiveTestFileProvider,
        onReport: (ArchiveExtractionReport) -> Unit,
    ) = ArchiveService.extract(
        provider = provider,
        archive = provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
        destinationDirectory = "/workspace",
        onReport = onReport,
    )

    private suspend fun childNames(provider: ArchiveTestFileProvider): List<String> =
        provider.listFiles("/workspace/bundle_extracted").getOrThrow().map { it.name }

    private companion object {
        const val BROKEN_PATH = "/workspace/bundle_extracted/b.txt"
    }
}
