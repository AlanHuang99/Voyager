package com.voyagerfiles.data.archive

import com.voyagerfiles.data.repository.TransferCancellation
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

class ArchiveParallelExtractionTest {

    @Test
    fun parallelWritesExtractTheSameFilesNamesAndReportAsSequentialOnes() = runBlocking {
        val entries = buildMap {
            repeat(300) { index -> put("folder-${index % 3}/file-$index.txt", "contents $index".encodeToByteArray()) }
            // Names that differ only in case, which the report lists as renamed where case is ignored.
            put("folder-0/Clash.txt", "first".encodeToByteArray())
            put("folder-0/clash.txt", "second".encodeToByteArray())
            // Larger than what is buffered for a parallel write, so it is written while reading.
            put("big.bin", ByteArray(1_500_000) { (it % 251).toByte() })
        }
        val archive = zipBytes(entries)

        val sequential = extract(ArchiveTestFileProvider(ignoresNameCase = true), archive)
        val parallelProvider = ArchiveTestFileProvider(ignoresNameCase = true, parallelWrites = 4, writeDelayMillis = 2)
        val parallel = extract(parallelProvider, archive)

        assertTrue(
            "expected overlapping writes, saw ${parallelProvider.mostConcurrentWrites}",
            parallelProvider.mostConcurrentWrites > 1,
        )
        assertEquals(sequential.files, parallel.files)
        assertEquals(entries.size, parallel.files.size)
        assertEquals(sequential.report.extractedFiles, parallel.report.extractedFiles)
        assertEquals(sequential.report.extractedEntries, parallel.report.extractedEntries)
        assertEquals(sequential.report.renamed, parallel.report.renamed)
        assertEquals(1, parallel.report.renamedCount)
    }

    @Test
    fun aFailedParallelWriteIsLeftOutWhileTheOtherFilesAreWritten() = runBlocking {
        val entries = (0 until 40).associate { index -> "notes/note-$index.txt" to "note $index".encodeToByteArray() }
        val provider = ArchiveTestFileProvider(
            failOutputPath = "/workspace/bundle_extracted/notes/note-17.txt",
            parallelWrites = 4,
            writeDelayMillis = 1,
        )

        val result = extract(provider, zipBytes(entries))

        assertEquals(39, result.report.extractedFiles)
        assertEquals(1, result.report.notExtractedCount)
        assertEquals("notes/note-17.txt", result.report.notExtracted.single().entryPath)
        assertEquals(entries.keys - "notes/note-17.txt", result.files.keys)
        result.files.forEach { (path, contents) -> assertEquals(entries.getValue(path).decodeToString(), contents) }
    }

    @Test
    fun anEntryBelowAFileStillBeingWrittenIsRejectedAndEverythingIsRemoved() = runBlocking {
        val archive = zipBytes(
            mapOf(
                "c.txt" to "written first".encodeToByteArray(),
                "a" to "a file".encodeToByteArray(),
                "a/b.txt" to "below a file".encodeToByteArray(),
            ),
        )
        val provider = workspace(ArchiveTestFileProvider(parallelWrites = 4, writeDelayMillis = 20), archive)
        var report: ArchiveExtractionReport? = null

        val error = ArchiveService.extract(
            provider,
            provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
            "/workspace",
            onReport = { report = it },
        ).exceptionOrNull()

        assertTrue(error is UnsafeArchiveEntryException)
        assertNull(report)
        assertFalse(provider.exists("/workspace/bundle_extracted"))
    }

    @Test
    fun aParallelWriteThatCannotBeCleanedUpStopsTheExtractionAndStaysListed() = runBlocking {
        val entries = (0 until 40).associate { index -> "notes/note-$index.txt" to "note $index".encodeToByteArray() }
        val broken = "/workspace/bundle_extracted/notes/note-17.txt"
        val provider = workspace(
            ArchiveTestFileProvider(
                failOutputPath = broken,
                failDeletePath = broken,
                parallelWrites = 4,
                writeDelayMillis = 1,
            ),
            zipBytes(entries),
        )
        var report: ArchiveExtractionReport? = null

        val error = ArchiveService.extract(
            provider,
            provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
            "/workspace",
            onReport = { report = it },
        ).exceptionOrNull()

        assertTrue(error is PartialExtractionException)
        assertTrue(error!!.cause is ArchiveCleanupException)
        val kept = checkNotNull(report)
        assertFalse(kept.complete)
        assertEquals(listOf("notes/note-17.txt"), kept.notExtracted.map { it.entryPath })
        // Writes that were already under way when the failure was seen finish and are counted.
        val files = filesBelow(provider, kept.root.path)
        assertTrue("notes/note-17.txt" in files)
        assertEquals(files.size - 1, kept.extractedFiles)
        assertTrue(kept.extractedFiles >= 17)
    }

    @Test
    fun cancellingRemovesEveryFileIncludingThoseStillBeingWritten() = runBlocking {
        val entries = (0 until 200).associate { index ->
            "files/file-$index.txt" to "file number $index".encodeToByteArray()
        }
        val provider = workspace(ArchiveTestFileProvider(parallelWrites = 4, writeDelayMillis = 2), zipBytes(entries))
        val token = TransferCancellation()
        var report: ArchiveExtractionReport? = null

        try {
            withContext(token.contextElement()) {
                ArchiveService.extract(
                    provider,
                    provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
                    "/workspace",
                    onReport = { report = it },
                ) { update -> if (update.completedEntries >= 50) token.cancel() }
            }
            fail("extraction should have been cancelled")
        } catch (expected: CancellationException) {
        }

        assertNull(report)
        assertFalse(provider.exists("/workspace/bundle_extracted"))
        assertEquals(0, provider.openOutputCount)
    }

    @Test
    fun aLargeTarEntryWrittenWhileReadingLeavesTheStreamOpenForTheNextEntries() = runBlocking {
        val big = ByteArray(1_500_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            listOf("first.txt" to "first".encodeToByteArray(), "big.bin" to big, "after.txt" to "after".encodeToByteArray())
                .forEach { (name, contents) ->
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = contents.size.toLong() })
                    tar.write(contents)
                    tar.closeArchiveEntry()
                }
        }
        val gzipped = ByteArrayOutputStream().also { GzipCompressorOutputStream(it).use { gzip -> gzip.write(output.toByteArray()) } }
        val provider = ArchiveTestFileProvider(parallelWrites = 4).apply {
            putDirectory("/workspace")
            putFile("/workspace/bundle.tar.gz", gzipped.toByteArray())
        }

        val root = ArchiveService.extract(
            provider,
            provider.getFileInfo("/workspace/bundle.tar.gz").getOrThrow(),
            "/workspace",
        ).getOrThrow()

        assertEquals("first", provider.readFile("${root.path}/first.txt").decodeToString())
        assertTrue(big.contentEquals(provider.readFile("${root.path}/big.bin")))
        assertEquals("after", provider.readFile("${root.path}/after.txt").decodeToString())
    }

    private class Extraction(val files: Map<String, String>, val report: ArchiveExtractionReport)

    private suspend fun extract(provider: ArchiveTestFileProvider, archive: ByteArray): Extraction {
        workspace(provider, archive)
        var report: ArchiveExtractionReport? = null
        val root = ArchiveService.extract(
            provider,
            provider.getFileInfo("/workspace/bundle.zip").getOrThrow(),
            "/workspace",
            onReport = { report = it },
        ).getOrThrow()
        return Extraction(filesBelow(provider, root.path), checkNotNull(report))
    }

    private fun workspace(provider: ArchiveTestFileProvider, archive: ByteArray) = provider.apply {
        putDirectory("/workspace")
        putFile("/workspace/bundle.zip", archive)
    }

    /** Files below [root] by path relative to it; large binary contents are summarised by size. */
    private suspend fun filesBelow(provider: ArchiveTestFileProvider, root: String): Map<String, String> {
        val files = mutableMapOf<String, String>()
        suspend fun visit(path: String) {
            provider.listFiles(path).getOrThrow().forEach { item ->
                if (item.isDirectory) {
                    visit(item.path)
                } else {
                    val contents = provider.readFile(item.path)
                    files[item.path.removePrefix("$root/")] =
                        if (contents.size > 1000) "${contents.size} bytes, sum ${contents.sum()}" else contents.decodeToString()
                }
            }
        }
        visit(root)
        return files
    }

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipArchiveOutputStream(output).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putArchiveEntry(ZipArchiveEntry(name).apply { size = contents.size.toLong() })
                zip.write(contents)
                zip.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }
}
