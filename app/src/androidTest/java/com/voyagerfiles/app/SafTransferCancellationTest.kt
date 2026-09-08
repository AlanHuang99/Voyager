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
import org.junit.Assert.*
import org.junit.Test

class SafTransferCancellationTest {
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
