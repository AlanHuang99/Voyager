package com.voyagerfiles.app

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.remote.saf.SafFileProvider
import com.voyagerfiles.data.repository.TransferCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.voyagerfiles.viewmodel.FileOperationCoordinator
import com.voyagerfiles.data.repository.LocalFileProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SafTransferCancellationTest {
    @Test
    fun sameProviderMoveCancelsWhenDestinationStopsDraining() = stalledDestinationMove(sameProvider = true)

    @Test
    fun coordinatorMoveCancelsWhenDestinationStopsDraining() = stalledDestinationMove(sameProvider = false)

    @Test
    fun legacyPipeWriterCancelsWhenDestinationStopsDraining() = stalledDestinationMove(sameProvider = false, legacyPipe = true)

    @Test
    fun coordinatorMoveCancelsWhenSocketDestinationStopsDraining() = stalledDestinationMove(sameProvider = false, socketDestination = true)

    @Test
    fun sameProviderMoveToRegularDescriptorPreservesAllBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<VoyagerApp>()
        val endpoint = Uri.parse("content://${TransferDocumentsProvider.AUTHORITY}")
        context.contentResolver.call(endpoint, "reset", null, null)
        val tree = DocumentsContract.buildTreeDocumentUri(TransferDocumentsProvider.AUTHORITY, "root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "source.bin").toString()
        val destination = DocumentsContract.buildDocumentUriUsingTree(tree, "destination").toString()
        val provider = SafFileProvider(context, tree)
        provider.move(source, destination).getOrThrow()
        assertFalse(provider.exists(source))
        val target = provider.listFiles(destination).getOrThrow().single()
        assertArrayEquals(TransferDocumentsProvider.payload(), provider.getInputStream(target.path).getOrThrow().use { it.readBytes() })
    }

    private fun stalledDestinationMove(sameProvider: Boolean, legacyPipe: Boolean = false, socketDestination: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<VoyagerApp>()
        val endpoint = Uri.parse("content://${TransferDocumentsProvider.AUTHORITY}")
        context.contentResolver.call(endpoint, "reset", if (socketDestination) "stalled-socket" else "stalled-output", null)
        val tree = DocumentsContract.buildTreeDocumentUri(TransferDocumentsProvider.AUTHORITY, "root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "source.bin").toString()
        val destination = DocumentsContract.buildDocumentUriUsingTree(tree, "destination").toString()
        val provider = SafFileProvider(context, tree)
        val localSource = File(context.cacheDir, "stalled-source/source.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(TransferDocumentsProvider.payload())
        }
        val destinationProvider = if (legacyPipe) object : com.voyagerfiles.data.repository.FileProvider by provider {
            override suspend fun getOutputStream(path: String): Result<java.io.OutputStream> = runCatching {
                val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(path), "wt")!!
                com.voyagerfiles.data.remote.saf.SafOutputStream(descriptor, useNonBlockingFlag = false)
            }
            override suspend fun writeStream(path: String, input: java.io.InputStream, sourcePath: String, totalBytes: Long?, onProgress: (com.voyagerfiles.data.repository.StreamTransferProgress) -> Unit): Result<Unit> = runCatching {
                getOutputStream(path).getOrThrow().use { output ->
                    com.voyagerfiles.data.repository.StreamTransfer.copy(input, output, sourcePath, totalBytes, onProgress = onProgress)
                }
            }
        } else provider
        val token = TransferCancellation()
        val task = async(Dispatchers.IO + token.contextElement()) {
            if (sameProvider) provider.move(source, destination)
            else FileOperationCoordinator.movePath(LocalFileProvider(), destinationProvider, localSource.path, destination)
        }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (context.contentResolver.call(endpoint, "partial", null, null)!!.getLong("bytes") == 0L && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("Provider must consume some destination bytes before stalling", context.contentResolver.call(endpoint, "partial", null, null)!!.getLong("bytes") > 0L)
            Thread.sleep(200)
            assertFalse("Transfer must still be waiting for the destination", task.isCompleted)
            val started = System.nanoTime()
            token.cancel()
            assertTrue("Cancel must not block the caller", System.nanoTime() - started < 500_000_000L)
            val result = withTimeoutOrNull(2_000) { task.await() }
            assertNotNull("Cancellation must complete while the provider still holds its unread pipe open", result)
            assertTrue(result!!.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(provider.listFiles(destination).getOrThrow().isEmpty())
            val originalDigest = java.security.MessageDigest.getInstance("SHA-256").digest(TransferDocumentsProvider.payload())
            if (sameProvider) {
                assertArrayEquals(originalDigest, context.contentResolver.call(endpoint, "source", null, null)!!.getByteArray("digest"))
            } else {
                assertArrayEquals(originalDigest, java.security.MessageDigest.getInstance("SHA-256").digest(localSource.readBytes()))
            }
        } finally {
            token.cancel()
            context.contentResolver.call(endpoint, "release", null, null)
            task.await()
            localSource.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun sameProviderMoveCancellationPreservesExactSourceAndRemovesPartialTarget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<VoyagerApp>()
        val authority = TransferDocumentsProvider.AUTHORITY
        val endpoint = Uri.parse("content://$authority")
        context.contentResolver.call(endpoint, "reset", null, null)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "source.bin").toString()
        val destination = DocumentsContract.buildDocumentUriUsingTree(tree, "destination").toString()
        val provider = SafFileProvider(context, tree)
        val token = TransferCancellation()
        val task = async(Dispatchers.IO + token.contextElement()) { provider.move(source, destination) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (context.contentResolver.call(endpoint, "partial", null, null)!!.getLong("bytes") == 0L && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(context.contentResolver.call(endpoint, "partial", null, null)!!.getLong("bytes") > 0L)
        token.cancel()
        assertTrue(withTimeout(5_000) { task.await() }.isFailure)
        assertTrue(provider.listFiles(destination).getOrThrow().isEmpty())
        assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(TransferDocumentsProvider.payload()), context.contentResolver.call(endpoint, "source", null, null)!!.getByteArray("digest"))
    }
}
