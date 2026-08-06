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
