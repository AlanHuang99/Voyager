package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun countsRequestedItemsFromZeroThroughFinalIncludingNestedDirectory() = runBlocking {
        val source = temp.newFolder("sources")
        repeat(4) { source.resolve("$it.txt").writeText("file $it") }
        source.resolve("nested").apply { mkdirs(); resolve("child.txt").writeText("child") }
        val provider = LocalFileProvider()
        val items = provider.listFiles(source.path).getOrThrow().sortedBy { it.name }
        val progress = mutableListOf<DownloadProgress>()
        val destination = temp.newFolder("downloads")
        FileDownloader.download(provider, items, destination, progress::add).getOrThrow()
        assertEquals(0, progress.first().completedRequestedItems)
        assertEquals(5, progress.last().completedRequestedItems)
        assertEquals((0..5).toSet(), progress.map { it.completedRequestedItems }.toSet())
        assertTrue(progress.all { it.totalRequestedItems == 5 })
        assertEquals("child", destination.resolve("nested/child.txt").readText())
    }

    @Test
    fun failedSecondItemLeavesCompletedCountAtOne() = runBlocking {
        val source = temp.newFolder("sources")
        val first = source.resolve("first.txt").apply { writeText("first") }
        val second = source.resolve("missing.txt").apply { writeText("second") }
        val provider = LocalFileProvider()
        val items = listOf(first, second).map { provider.getFileInfo(it.path).getOrThrow() }
        second.delete()
        val progress = mutableListOf<DownloadProgress>()
        val destination = temp.newFolder("downloads")
        assertTrue(FileDownloader.download(provider, items, destination, progress::add).isFailure)
        assertEquals(1, progress.last().completedRequestedItems)
        assertEquals(2, progress.last().totalRequestedItems)
        assertEquals("first", destination.resolve("first.txt").readText())
        assertTrue(!destination.resolve("missing.txt").exists())
    }

    @Test
    fun unknownSizeDownloadPreservesExactTerminalByteProgress() = runBlocking {
        val payload = ByteArray(64 * 1024 + 17) { index -> (index % 251).toByte() }
        val source = temp.newFile("unknown.bin").apply { writeBytes(payload) }
        val destination = temp.newFolder("downloads")
        val item = FileItem(
            name = source.name,
            path = source.absolutePath,
            isDirectory = false,
            size = -1,
            source = FileSource.LOCAL,
        )
        val progress = mutableListOf<DownloadProgress>()

        FileDownloader.download(
            provider = LocalFileProvider(),
            items = listOf(item),
            destinationDirectory = destination,
            onProgress = progress::add,
        ).getOrThrow()

        val completion = progress.last()
        val terminalStream = checkNotNull(completion.stream)
        assertNotNull(terminalStream)
        assertEquals(1, completion.completedRequestedItems)
        assertEquals(payload.size.toLong(), terminalStream.bytesTransferred)
        assertNull(terminalStream.totalBytes)
        assertTrue(destination.resolve(source.name).readBytes().contentEquals(payload))
    }
}
