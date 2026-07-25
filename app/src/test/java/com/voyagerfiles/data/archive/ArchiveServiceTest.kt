package com.voyagerfiles.data.archive

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ArchiveServiceTest {

    @Test
    fun createsZipWithExplicitDirectoriesFilesAndProgress() = runBlocking {
        val provider = ArchiveTestFileProvider().apply {
            putDirectory("/source")
            putFile("/source/readme.txt", "hello")
            putDirectory("/source/folder")
            putFile("/source/folder/nested.txt", "nested")
            putDirectory("/destination")
        }
        val selected = listOf(
            provider.getFileInfo("/source/readme.txt").getOrThrow(),
            provider.getFileInfo("/source/folder").getOrThrow(),
        )
        val progress = mutableListOf<ArchiveProgress>()

        val archive = ArchiveService.createZip(
            provider = provider,
            selectedItems = selected,
            destinationDirectory = "/destination",
            archiveName = "bundle.zip",
            onProgress = progress::add,
        ).getOrThrow()

        assertEquals("/destination/bundle.zip", archive.path)
        val contents = readZip(provider.readFile(archive.path))
        assertEquals(setOf("readme.txt", "folder/", "folder/nested.txt"), contents.keys)
        assertEquals("hello", contents.getValue("readme.txt").decodeToString())
        assertEquals("nested", contents.getValue("folder/nested.txt").decodeToString())
        assertTrue(progress.any { it.currentEntryName == "readme.txt" })
        assertTrue(progress.any { it.currentEntryName == "folder/nested.txt" })
        assertEquals(3, progress.last().completedEntries)
    }

    @Test
    fun createZipRefusesOverwriteAndCleansPartialOutput() = runBlocking {
        val provider = ArchiveTestFileProvider().apply {
            putDirectory("/source")
            putFile("/source/file.txt", "new")
            putDirectory("/destination")
            putFile("/destination/existing.zip", "existing")
        }
        val selected = listOf(provider.getFileInfo("/source/file.txt").getOrThrow())

        val conflict = ArchiveService.createZip(
            provider,
            selected,
            "/destination",
            "existing.zip",
        )

        assertTrue(conflict.exceptionOrNull() is ArchiveConflictException)
        assertEquals("existing", provider.readFile("/destination/existing.zip").decodeToString())

        val failing = ArchiveTestFileProvider(failOutputPath = "/destination/partial.zip").apply {
            putDirectory("/source")
            putFile("/source/file.txt", "new")
            putDirectory("/destination")
        }
        val failed = ArchiveService.createZip(
            failing,
            listOf(failing.getFileInfo("/source/file.txt").getOrThrow()),
            "/destination",
            "partial.zip",
        )

        assertTrue(failed.isFailure)
        assertFalse(failing.exists("/destination/partial.zip"))
    }

    @Test
    fun extractsZipIntoNewSiblingDirectoryWithoutOverwrite() = runBlocking {
        val provider = ArchiveTestFileProvider().apply {
            putDirectory("/workspace")
            putFile(
                "/workspace/sample.zip",
                zipBytes(
                    ZipFixture("folder/", isDirectory = true),
                    ZipFixture("folder/nested.txt", contents = "nested".toByteArray()),
                    ZipFixture("root.txt", contents = "root".toByteArray()),
                ),
            )
        }
        val archive = provider.getFileInfo("/workspace/sample.zip").getOrThrow()
        val progress = mutableListOf<ArchiveProgress>()

        val extracted = ArchiveService.extract(
            provider = provider,
            archive = archive,
            destinationDirectory = "/workspace",
            onProgress = progress::add,
        ).getOrThrow()

        assertEquals("/workspace/sample_extracted", extracted.path)
        assertEquals("nested", provider.readFile("/workspace/sample_extracted/folder/nested.txt").decodeToString())
        assertEquals("root", provider.readFile("/workspace/sample_extracted/root.txt").decodeToString())
        assertEquals(3, progress.last().completedEntries)

        val secondAttempt = ArchiveService.extract(provider, archive, "/workspace")
        assertTrue(secondAttempt.exceptionOrNull() is ArchiveConflictException)
    }

    @Test
    fun rejectsZipSlipDuplicateNormalizedPathsAndSymlinksWithCleanup() = runBlocking {
        val unsafeArchives = listOf(
            zipBytes(ZipFixture("../escape.txt", contents = "escape".toByteArray())),
            zipBytes(
                ZipFixture("safe/file.txt", contents = "first".toByteArray()),
                ZipFixture("safe\\file.txt", contents = "duplicate".toByteArray()),
            ),
            zipBytes(
                ZipFixture(
                    name = "link",
                    contents = "target".toByteArray(),
                    unixMode = UnixStat.LINK_FLAG or 0b111_101_101,
                )
            ),
        )

        unsafeArchives.forEachIndexed { index, bytes ->
            val provider = ArchiveTestFileProvider().apply {
                putDirectory("/workspace")
                putFile("/workspace/unsafe-$index.zip", bytes)
            }
            val archive = provider.getFileInfo("/workspace/unsafe-$index.zip").getOrThrow()

            val result = ArchiveService.extract(provider, archive, "/workspace")

            assertTrue(
                "Unsafe archive $index returned ${result.exceptionOrNull()?.javaClass?.name}: ${result.exceptionOrNull()?.message}",
                result.exceptionOrNull() is UnsafeArchiveEntryException,
            )
            assertFalse(provider.exists("/workspace/unsafe-${index}_extracted"))
            assertFalse(provider.exists("/workspace/escape.txt"))
        }
    }

    @Test
    fun extractionUsesBoundedReadsAndCleansFailedWrites() = runBlocking {
        val archiveBytes = zipBytes(
            ZipFixture("large.bin", contents = ByteArray(200_000) { (it % 251).toByte() }),
        )
        val bounded = ArchiveTestFileProvider(maximumReadRequest = 64 * 1024).apply {
            putDirectory("/workspace")
            putFile("/workspace/large.zip", archiveBytes)
        }

        ArchiveService.extract(
            bounded,
            bounded.getFileInfo("/workspace/large.zip").getOrThrow(),
            "/workspace",
        ).getOrThrow()

        assertTrue(bounded.largestReadRequest in 1..64 * 1024)

        val failing = ArchiveTestFileProvider(
            failOutputPath = "/workspace/failure_extracted/file.txt",
        ).apply {
            putDirectory("/workspace")
            putFile(
                "/workspace/failure.zip",
                zipBytes(ZipFixture("file.txt", contents = "contents".toByteArray())),
            )
        }
        val result = ArchiveService.extract(
            failing,
            failing.getFileInfo("/workspace/failure.zip").getOrThrow(),
            "/workspace",
        )

        assertTrue(result.isFailure)
        assertFalse(failing.exists("/workspace/failure_extracted"))
    }

    @Test
    fun extractsTarGzipAndBzip2FamiliesWithExactContents() = runBlocking {
        val tar = tarBytes(
            TarFixture("folder/", isDirectory = true),
            TarFixture("folder/nested.txt", contents = "nested".toByteArray()),
            TarFixture("root.txt", contents = "root".toByteArray()),
        )
        val fixtures = listOf(
            "bundle.tar" to tar,
            "bundle.tgz" to gzip(tar),
            "bundle.tar.gz" to gzip(tar),
            "bundle.tbz2" to bzip2(tar),
            "bundle.tar.bz2" to bzip2(tar),
        )

        fixtures.forEachIndexed { index, (name, bytes) ->
            val provider = ArchiveTestFileProvider().apply {
                putDirectory("/workspace")
                putFile("/workspace/$name", bytes)
            }

            val result = ArchiveService.extract(
                provider,
                provider.getFileInfo("/workspace/$name").getOrThrow(),
                "/workspace",
            ).getOrThrow()

            assertEquals("/workspace/bundle_extracted", result.path)
            assertEquals("nested", provider.readFile("${result.path}/folder/nested.txt").decodeToString())
            assertEquals("root", provider.readFile("${result.path}/root.txt").decodeToString())
            assertTrue("fixture $index should preserve exact bytes", provider.readFile("${result.path}/root.txt").contentEquals("root".toByteArray()))
        }
    }

    @Test
    fun extractsRawGzipAndBzip2ToFilesNamedFromTheirStems() = runBlocking {
        val contents = ByteArray(130_000) { (it % 239).toByte() }
        val fixtures = listOf(
            "records.csv.gz" to gzip(contents),
            "records.csv.bz2" to bzip2(contents),
        )

        fixtures.forEach { (name, bytes) ->
            val provider = ArchiveTestFileProvider().apply {
                putDirectory("/workspace")
                putFile("/workspace/$name", bytes)
            }

            val root = ArchiveService.extract(
                provider,
                provider.getFileInfo("/workspace/$name").getOrThrow(),
                "/workspace",
            ).getOrThrow()

            assertEquals("/workspace/records.csv_extracted", root.path)
            assertTrue(provider.readFile("${root.path}/records.csv").contentEquals(contents))
        }
    }

    @Test
    fun rejectsTarLinksRarAndCorruptInputsWithCleanup() = runBlocking {
        val linkTar = tarBytes(
            TarFixture(
                name = "link",
                linkFlag = TarConstants.LF_SYMLINK,
                linkName = "target",
            )
        )
        val linkProvider = ArchiveTestFileProvider().apply {
            putDirectory("/workspace")
            putFile("/workspace/link.tar", linkTar)
        }

        val linkResult = ArchiveService.extract(
            linkProvider,
            linkProvider.getFileInfo("/workspace/link.tar").getOrThrow(),
            "/workspace",
        )

        assertTrue(linkResult.exceptionOrNull() is UnsafeArchiveEntryException)
        assertFalse(linkProvider.exists("/workspace/link_extracted"))

        val rarProvider = ArchiveTestFileProvider().apply {
            putDirectory("/workspace")
            putFile("/workspace/legacy.rar", "not read")
        }
        val rarResult = ArchiveService.extract(
            rarProvider,
            rarProvider.getFileInfo("/workspace/legacy.rar").getOrThrow(),
            "/workspace",
        )

        assertTrue(rarResult.exceptionOrNull() is UnsupportedArchiveException)
        assertTrue(rarResult.exceptionOrNull()?.message?.contains("RAR") == true)
        assertFalse(rarProvider.exists("/workspace/legacy_extracted"))

        val corruptProvider = ArchiveTestFileProvider().apply {
            putDirectory("/workspace")
            putFile("/workspace/corrupt.tar", ByteArray(1_024) { 0x41 })
        }
        val corruptResult = ArchiveService.extract(
            corruptProvider,
            corruptProvider.getFileInfo("/workspace/corrupt.tar").getOrThrow(),
            "/workspace",
        )

        assertTrue(corruptResult.exceptionOrNull() is CorruptArchiveException)
        assertFalse(corruptProvider.exists("/workspace/corrupt_extracted"))
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipArchiveInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = if (entry.isDirectory) byteArrayOf() else input.readBytes()
            }
        }
        return entries
    }

    private fun zipBytes(vararg fixtures: ZipFixture): ByteArray {
        val output = ByteArrayOutputStream()
        ZipArchiveOutputStream(output).use { zip ->
            fixtures.forEach { fixture ->
                val entry = ZipArchiveEntry(fixture.name)
                fixture.unixMode?.let(entry::setUnixMode)
                if (!fixture.isDirectory) entry.size = fixture.contents.size.toLong()
                zip.putArchiveEntry(entry)
                if (!fixture.isDirectory) zip.write(fixture.contents)
                zip.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun tarBytes(vararg fixtures: TarFixture): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setAddPaxHeadersForNonAsciiNames(true)
            fixtures.forEach { fixture ->
                val entry = TarArchiveEntry(fixture.name, fixture.linkFlag).apply {
                    size = if (fixture.isDirectory || fixture.linkFlag != TarConstants.LF_NORMAL) {
                        0
                    } else {
                        fixture.contents.size.toLong()
                    }
                    fixture.linkName?.let(::setLinkName)
                }
                tar.putArchiveEntry(entry)
                if (entry.isFile) tar.write(fixture.contents)
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

    private fun bzip2(contents: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { it.write(contents) }
        return output.toByteArray()
    }

    private data class ZipFixture(
        val name: String,
        val contents: ByteArray = byteArrayOf(),
        val isDirectory: Boolean = name.endsWith('/'),
        val unixMode: Int? = null,
    )

    private data class TarFixture(
        val name: String,
        val contents: ByteArray = byteArrayOf(),
        val isDirectory: Boolean = name.endsWith('/'),
        val linkFlag: Byte = if (isDirectory) TarConstants.LF_DIR else TarConstants.LF_NORMAL,
        val linkName: String? = null,
    )
}
