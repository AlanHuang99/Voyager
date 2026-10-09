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
import java.util.Random

class ArchiveExtractionReportTest {

    @Test
    fun filesDifferingOnlyInCaseGetNumberedNamesAndFoldersMerge() = runBlocking {
        val provider = workspaceWith(
            "clash.zip",
            zipBytes(
                "Ms.png" to "first",
                "mS.png" to "second",
                "docs/a.txt" to "a",
                "Docs/b.txt" to "b",
                "Docs/A.txt" to "A",
            ),
            ignoresNameCase = true,
        )
        var report: ArchiveExtractionReport? = null

        val root = extract(provider, "clash.zip") { report = it }.getOrThrow()

        assertEquals("second", provider.readText("${root.path}/mS (1).png"))
        assertEquals(setOf("Ms.png", "docs", "mS (1).png"), provider.childNames(root.path).toSet())
        assertEquals(listOf("A (1).txt", "a.txt", "b.txt"), provider.childNames("${root.path}/docs"))
        assertEquals(
            listOf(RenamedEntry("mS.png", "mS (1).png"), RenamedEntry("Docs/A.txt", "A (1).txt")),
            report!!.renamed,
        )
        assertTrue(report!!.complete)
        assertEquals(5, report!!.extractedFiles)
    }

    @Test
    fun aFolderCannotMergeIntoAFileSoItIsRenamed() = runBlocking {
        val provider = workspaceWith(
            "clash.zip",
            zipBytes("Readme" to "file", "README/notes.txt" to "notes"),
            ignoresNameCase = true,
        )
        var report: ArchiveExtractionReport? = null

        val root = extract(provider, "clash.zip") { report = it }.getOrThrow()

        assertEquals("file", provider.readText("${root.path}/Readme"))
        assertEquals("notes", provider.readText("${root.path}/README (1)/notes.txt"))
        assertEquals(listOf(RenamedEntry("README", "README (1)")), report!!.renamed)
    }

    @Test
    fun aCaseSensitiveDestinationKeepsEveryNameAsItIs() = runBlocking {
        val provider = workspaceWith(
            "clash.zip",
            zipBytes("Ms.png" to "first", "mS.png" to "second", "Docs/a.txt" to "a", "docs/b.txt" to "b"),
        )
        var report: ArchiveExtractionReport? = null

        val root = extract(provider, "clash.zip") { report = it }.getOrThrow()

        assertEquals(listOf("Docs", "Ms.png", "docs", "mS.png"), provider.childNames(root.path))
        assertEquals("second", provider.readText("${root.path}/mS.png"))
        assertEquals("a", provider.readText("${root.path}/Docs/a.txt"))
        assertEquals("b", provider.readText("${root.path}/docs/b.txt"))
        assertEquals(emptyList<RenamedEntry>(), report!!.renamed)
    }

    @Test
    fun aDestinationThatFindsTheRootInAnyCaseGetsNumberedNames() = runBlocking {
        val provider = workspaceWith(
            "clash.zip",
            zipBytes("Ms.png" to "first", "mS.png" to "second", "docs/a.txt" to "a", "Docs/b.txt" to "b"),
            ignoresNameCase = null,
            lookupIgnoresCase = true,
        )
        var report: ArchiveExtractionReport? = null

        val root = extract(provider, "clash.zip") { report = it }.getOrThrow()

        assertEquals("first", provider.readText("${root.path}/Ms.png"))
        assertEquals("second", provider.readText("${root.path}/mS (1).png"))
        assertEquals(listOf("Ms.png", "docs", "mS (1).png"), provider.childNames(root.path))
        assertEquals(listOf("a.txt", "b.txt"), provider.childNames("${root.path}/docs"))
        assertEquals(listOf(RenamedEntry("mS.png", "mS (1).png")), report!!.renamed)
    }

    @Test
    fun aDestinationThatFindsTheRootOnlyByItsExactNameKeepsEveryName() = runBlocking {
        val provider = workspaceWith(
            "clash.zip",
            zipBytes("Ms.png" to "first", "mS.png" to "second"),
            ignoresNameCase = null,
        )
        var report: ArchiveExtractionReport? = null

        val root = extract(provider, "clash.zip") { report = it }.getOrThrow()

        assertEquals(listOf("Ms.png", "mS.png"), provider.childNames(root.path))
        assertEquals(emptyList<RenamedEntry>(), report!!.renamed)
    }

    @Test
    fun aRootThatCannotBeFoundAfterItsCreationStopsTheExtraction() = runBlocking {
        val provider = workspaceWith(
            "clash.zip",
            zipBytes("Ms.png" to "first", "mS.png" to "second"),
            ignoresNameCase = null,
            failLookupPath = "/workspace/clash_extracted",
        )
        var report: ArchiveExtractionReport? = null

        val error = extract(provider, "clash.zip") { report = it }.exceptionOrNull()

        assertEquals("The new folder clash_extracted cannot be found on the destination", error!!.message)
        assertNull(report)
        assertFalse(provider.exists("/workspace/clash_extracted"))
    }

    @Test
    fun theReportListsAtMostTwoHundredEntriesButCountsThemAll() = runBlocking {
        val names = (1..250).map { "bad-$it.txt" } + "good.txt"
        val provider = object : ArchiveTestFileProvider() {
            override suspend fun getOutputStream(path: String): Result<java.io.OutputStream> =
                if (path.substringAfterLast('/').startsWith("bad-")) Result.failure(java.io.IOException("Disk refused $path"))
                else super.getOutputStream(path)
        }.apply {
            putDirectory("/workspace")
            putFile("/workspace/many.zip", zipBytes(*names.map { it to it }.toTypedArray()))
        }
        var report: ArchiveExtractionReport? = null

        extract(provider, "many.zip") { report = it }.getOrThrow()

        assertEquals(250, report!!.notExtractedCount)
        assertEquals(ArchiveExtractionReport.MAX_LISTED, report!!.notExtracted.size)
        assertEquals("bad-1.txt", report!!.notExtracted.first().entryPath)
        assertEquals(1, report!!.extractedFiles)
    }

    @Test
    fun aBrokenZipEntryIsLeftOutAndTheRestIsExtracted() = runBlocking {
        val provider = workspaceWith(
            "bundle.zip",
            zipBytes("a.txt" to "a", "b.txt" to "b", "c.txt" to "c"),
            failOutputPath = "/workspace/bundle_extracted/b.txt",
        )
        var report: ArchiveExtractionReport? = null

        val root = extract(provider, "bundle.zip") { report = it }.getOrThrow()

        assertEquals(listOf("a.txt", "c.txt"), provider.childNames(root.path))
        assertEquals(listOf("b.txt"), report!!.notExtracted.map { it.entryPath })
        assertTrue(report!!.notExtracted.single().error.message!!.contains("Injected output failure"))
        assertTrue(report!!.complete)
    }

    @Test
    fun aTarEntryThatCannotBeWrittenIsLeftOut() = runBlocking {
        val provider = workspaceWith(
            "bundle.tar",
            tarBytes("a.txt" to "a".repeat(700), "b.txt" to "b".repeat(700), "c.txt" to "c".repeat(700)),
            failOutputPath = "/workspace/bundle_extracted/b.txt",
        )
        var report: ArchiveExtractionReport? = null

        val root = extract(provider, "bundle.tar") { report = it }.getOrThrow()

        assertEquals(listOf("a.txt", "c.txt"), provider.childNames(root.path))
        assertEquals("c".repeat(700), provider.readText("${root.path}/c.txt"))
        assertEquals(listOf("b.txt"), report!!.notExtracted.map { it.entryPath })
    }

    @Test
    fun aBrokenStreamKeepsWhatWasExtractedAndSaysItEndsEarly() = runBlocking {
        val incompressible = ByteArray(200_000).also { Random(1).nextBytes(it) }
        val tarGz = gzip(tarBytes("a.txt" to "a", "b.bin" to incompressible))
        val provider = workspaceWith("broken.tar.gz", tarGz.copyOf(tarGz.size * 3 / 5))
        var report: ArchiveExtractionReport? = null

        val error = extract(provider, "broken.tar.gz") { report = it }.exceptionOrNull()

        assertTrue(error is PartialExtractionException)
        assertEquals("the archive ends early and may be incomplete", error!!.message)
        assertEquals(listOf("a.txt"), provider.childNames("/workspace/broken_extracted"))
        assertFalse(report!!.complete)
        assertEquals(1, report!!.extractedFiles)
    }

    @Test
    fun aFileThatCannotBeCleanedUpStopsTheExtractionAndStaysListed() = runBlocking {
        val broken = "/workspace/bundle_extracted/b.txt"
        val provider = workspaceWith(
            "bundle.zip",
            zipBytes("a.txt" to "a", "b.txt" to "b", "c.txt" to "c"),
            failOutputPath = broken,
            failDeletePath = broken,
        )
        var report: ArchiveExtractionReport? = null

        val error = extract(provider, "bundle.zip") { report = it }.exceptionOrNull()

        assertTrue(error is PartialExtractionException)
        assertTrue(error!!.cause is ArchiveCleanupException)
        assertFalse(report!!.complete)
        assertEquals(listOf("b.txt"), report!!.notExtracted.map { it.entryPath })
        assertEquals(listOf("a.txt", "b.txt"), provider.childNames("/workspace/bundle_extracted"))
        assertEquals(1, report!!.extractedFiles)
    }

    @Test
    fun anEntryBelowAFileIsRejectedEvenWhenThatFileFailed() = runBlocking {
        val provider = workspaceWith(
            "conflict.zip",
            zipBytes("good.txt" to "good", "node" to "a file", "node/child.txt" to "child"),
            failOutputPath = "/workspace/conflict_extracted/node",
        )
        var report: ArchiveExtractionReport? = null

        val error = extract(provider, "conflict.zip") { report = it }.exceptionOrNull()

        assertTrue(error is UnsafeArchiveEntryException)
        assertNull(report)
        assertFalse(provider.exists("/workspace/conflict_extracted"))
    }

    @Test
    fun cancellingRemovesWhatWasExtracted() = runBlocking {
        val provider = workspaceWith("bundle.zip", zipBytes("a.txt" to "a", "b.txt" to "b", "c.txt" to "c"))
        val token = TransferCancellation()
        var report: ArchiveExtractionReport? = null

        try {
            withContext(token.contextElement()) {
                extract(
                    provider,
                    "bundle.zip",
                    onProgress = { update ->
                        if (update.phase == ArchivePhase.WRITING && update.completedEntries == 1) token.cancel()
                    },
                ) { report = it }
            }
            fail("Expected the extraction to be cancelled")
        } catch (_: CancellationException) {
        }

        assertFalse(provider.exists("/workspace/bundle_extracted"))
        assertNull(report)
    }

    @Test
    fun nothingIsKeptWhenNothingWasExtracted() = runBlocking {
        val incompressible = ByteArray(200_000).also { Random(2).nextBytes(it) }
        val tarGz = gzip(tarBytes("big.bin" to incompressible))
        val provider = workspaceWith("broken.tar.gz", tarGz.copyOf(tarGz.size / 2))
        var report: ArchiveExtractionReport? = null

        val error = extract(provider, "broken.tar.gz") { report = it }.exceptionOrNull()

        assertTrue(error is CorruptArchiveException)
        assertNull(report)
        assertFalse(provider.exists("/workspace/broken_extracted"))
    }

    @Test
    fun anUnsafeEntryMidStreamRemovesEverything() = runBlocking {
        val provider = workspaceWith("unsafe.tar", tarBytes("a.txt" to "a", "b.txt" to "b", "./a.txt" to "again"))
        var report: ArchiveExtractionReport? = null

        val error = extract(provider, "unsafe.tar") { report = it }.exceptionOrNull()

        assertTrue(error is UnsafeArchiveEntryException)
        assertNull(report)
        assertFalse(provider.exists("/workspace/unsafe_extracted"))
    }

    private suspend fun extract(
        provider: ArchiveTestFileProvider,
        name: String,
        onProgress: (ArchiveProgress) -> Unit = {},
        onReport: (ArchiveExtractionReport) -> Unit = {},
    ) = ArchiveService.extract(
        provider = provider,
        archive = provider.getFileInfo("/workspace/$name").getOrThrow(),
        destinationDirectory = "/workspace",
        onProgress = onProgress,
        onReport = onReport,
    )

    private fun workspaceWith(
        name: String,
        bytes: ByteArray,
        failOutputPath: String? = null,
        failDeletePath: String? = null,
        ignoresNameCase: Boolean? = false,
        lookupIgnoresCase: Boolean = false,
        failLookupPath: String? = null,
    ) =
        ArchiveTestFileProvider(
            failOutputPath = failOutputPath,
            failDeletePath = failDeletePath,
            ignoresNameCase = ignoresNameCase,
            lookupIgnoresCase = lookupIgnoresCase,
            failLookupPath = failLookupPath,
        ).apply {
            putDirectory("/workspace")
            putFile("/workspace/$name", bytes)
        }

    private fun ArchiveTestFileProvider.readText(path: String) = readFile(path).decodeToString()

    private suspend fun ArchiveTestFileProvider.childNames(path: String) =
        listFiles(path).getOrThrow().map { it.name }.sorted()

    private fun zipBytes(vararg files: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipArchiveOutputStream(output).use { zip ->
            files.forEach { (name, contents) ->
                val bytes = contents.toByteArray()
                zip.putArchiveEntry(ZipArchiveEntry(name).apply { size = bytes.size.toLong() })
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun tarBytes(vararg files: Pair<String, Any>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            files.forEach { (name, contents) ->
                val bytes = contents as? ByteArray ?: contents.toString().toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
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
