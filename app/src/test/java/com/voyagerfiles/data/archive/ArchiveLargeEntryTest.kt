package com.voyagerfiles.data.archive

import com.voyagerfiles.data.model.FileItem
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.Zip64ExtendedInformationExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Entries of 4 GB and more need ZIP64. The large files are streams of zeros that are never held
 * in memory, and they deflate to a few megabytes, so the archives stay small.
 */
class ArchiveLargeEntryTest {

    @Test
    fun fileListedAt4GbOrMoreIsStoredWithZip64() = runBlocking {
        val provider = LargeFileProvider().apply {
            putDirectory("/source")
            putFile("/source/small.txt", "small")
            putLargeFile("/source/large.bin", listedSize = LARGE_SIZE, actualSize = LARGE_SIZE)
            putDirectory("/destination")
        }

        val archive = createZip(provider).getOrThrow()

        val bytes = provider.readFile(archive.path)
        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).get().use { zip ->
            val large = zip.getEntry("source/large.bin")
            assertEquals(LARGE_SIZE, large.size)
            assertEquals(LARGE_SIZE, zip.getInputStream(large).use(::countBytes))
            val small = zip.getEntry("source/small.txt")
            assertTrue(small.extraFields.none { it is Zip64ExtendedInformationExtraField })
            assertEquals("small", zip.getInputStream(small).use { it.readBytes().decodeToString() })
        }
        assertEquals(EXPECTED_ENTRIES, readWithCommonsStream(bytes))
        assertEquals(EXPECTED_ENTRIES, readWithJdkStream(bytes))
    }

    @Test
    fun fileListedAt4GbButSmallerStillMakesAValidZip() = runBlocking {
        val provider = LargeFileProvider().apply {
            putDirectory("/source")
            putFile("/source/small.txt", "small")
            putLargeFile("/source/large.bin", listedSize = LARGE_SIZE, actualSize = SHRUNK_SIZE)
            putDirectory("/destination")
        }

        val archive = createZip(provider).getOrThrow()

        val bytes = provider.readFile(archive.path)
        val expected = EXPECTED_ENTRIES + ("source/large.bin" to SHRUNK_SIZE)
        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).get().use { zip ->
            assertEquals(SHRUNK_SIZE, zip.getEntry("source/large.bin").size)
        }
        // The entry keeps its ZIP64 field, so its data descriptor holds 8-byte sizes, as APPNOTE
        // 4.3.9.2 requires. java.util.zip.ZipInputStream picks the width from the actual size
        // instead and rejects it; 7-Zip, unzip, libarchive and Python read it.
        assertEquals(expected, readWithCommonsStream(bytes))
    }

    @Test
    fun fileListedSmallerThan4GbThatTurnsOutLargerFailsWithoutLeavingAnArchive() = runBlocking {
        val provider = LargeFileProvider().apply {
            putDirectory("/source")
            putLargeFile("/source/large.bin", listedSize = SHRUNK_SIZE, actualSize = LARGE_SIZE)
            putDirectory("/destination")
        }

        val error = createZip(provider).exceptionOrNull()

        assertNotNull(error)
        val message = error!!.message.orEmpty()
        assertTrue(message, "source/large.bin" in message)
        assertTrue(message, "4 GB" in message)
        assertFalse(provider.exists("/destination/bundle.zip"))
    }

    private suspend fun createZip(provider: LargeFileProvider) = ArchiveService.createZip(
        provider = provider,
        selectedItems = listOf(provider.getFileInfo("/source").getOrThrow()),
        destinationDirectory = "/destination",
        archiveName = "bundle.zip",
    )

    private fun readWithCommonsStream(bytes: ByteArray): Map<String, Long> =
        ZipArchiveInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.associate { entry -> entry.name to countBytes(zip) }
        }

    /** The JDK reader checks every entry's size and CRC against its data descriptor. */
    private fun readWithJdkStream(bytes: ByteArray): Map<String, Long> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.associate { entry -> entry.name to countBytes(zip) }
        }

    private fun countBytes(input: InputStream): Long {
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
        }
    }

    private class LargeFileProvider : ArchiveTestFileProvider() {
        private val largeFiles = mutableMapOf<String, Pair<Long, Long>>()

        fun putLargeFile(path: String, listedSize: Long, actualSize: Long) {
            putFile(path, byteArrayOf())
            largeFiles[path] = listedSize to actualSize
        }

        override suspend fun listFiles(path: String): Result<List<FileItem>> =
            super.listFiles(path).map { items -> items.map(::withListedSize) }

        override suspend fun getFileInfo(path: String): Result<FileItem> =
            super.getFileInfo(path).map(::withListedSize)

        override suspend fun getInputStream(path: String): Result<InputStream> =
            largeFiles[path]?.let { (_, actualSize) -> Result.success(ZeroInputStream(actualSize)) }
                ?: super.getInputStream(path)

        private fun withListedSize(item: FileItem): FileItem =
            largeFiles[item.path]?.let { (listedSize, _) -> item.copy(size = listedSize) } ?: item
    }

    private class ZeroInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private companion object {
        /** Just past the largest size a plain ZIP entry can record. */
        const val LARGE_SIZE = 0xFFFF_FFFFL + 1024 * 1024
        const val SHRUNK_SIZE = 1024L * 1024
        val EXPECTED_ENTRIES = mapOf(
            "source/" to 0L,
            "source/large.bin" to LARGE_SIZE,
            "source/small.txt" to 5L,
        )
    }
}
