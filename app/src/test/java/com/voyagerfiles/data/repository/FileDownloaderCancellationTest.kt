package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileItem
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDownloaderCancellationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun cancellationDuringEmptyDirectoryListingRemovesOwnedDirectoryTree() = runBlocking {
        val source = temp.newFolder("source")
        source.resolve("first/empty").mkdirs()
        source.resolve("second/empty").mkdirs()
        val destination = temp.newFolder("destination")
        destination.resolve("existing.txt").writeText("keep")
        val token = TransferCancellation()
        val local = LocalFileProvider()
        val listed = mutableListOf<String>()
        val provider = object : FileProvider by local {
            override suspend fun listFiles(path: String): Result<List<FileItem>> {
                listed += path
                val result = local.listFiles(path)
                if (path != source.path) token.cancel()
                return result
            }
        }
        val result = withContext(token.contextElement()) {
            FileDownloader.download(provider, listOf(local.getFileInfo(source.path).getOrThrow()), destination)
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(2, listed.size)
        assertEquals(listOf("existing.txt"), destination.listFiles()!!.map { it.name })
        assertEquals("keep", destination.resolve("existing.txt").readText())
        assertTrue(source.resolve("first/empty").isDirectory)
        assertTrue(source.resolve("second/empty").isDirectory)
    }

    @Test
    fun cancelledDownloadDoesNotCreateDestination() = runBlocking {
        val destination = File(temp.root, "new-destination")
        val token = TransferCancellation().apply { cancel() }
        val result = withContext(token.contextElement()) {
            FileDownloader.download(LocalFileProvider(), emptyList(), destination)
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(destination.exists())
    }
}
