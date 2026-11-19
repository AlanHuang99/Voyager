package com.voyagerfiles.data.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.luben.zstd.ZstdOutputStream
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.data.repository.TransferCancellation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.*
import org.junit.Test

class ArchiveCodecsAndroidTest {
    private fun fixture(block: suspend (File, LocalFileProvider) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "archive-codecs-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root, LocalFileProvider()) } finally { root.deleteRecursively() }
    }

    private fun compress(kind: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val codec: OutputStream = when (kind) {
            "xz" -> XZCompressorOutputStream(output)
            "lz4" -> FramedLZ4CompressorOutputStream(output)
            else -> ZstdOutputStream(output).setChecksum(true)
        }
        codec.use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun tar(name: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        TarArchiveOutputStream(output).use { archive ->
            archive.putArchiveEntry(TarArchiveEntry(name).apply { size = payload.size.toLong() })
            archive.write(payload)
            archive.closeArchiveEntry()
        }
    }.toByteArray()

    @Test fun rawAndTarCodecsPreserveExactBytes() = fixture { root, provider ->
        val payload = ByteArray(131072) { (it * 37).toByte() }
        for (kind in listOf("xz", "lz4", "zst")) {
            for (isTar in listOf(false, true)) {
                val archive = root.resolve(if (isTar) "bundle-$kind.tar.$kind" else "data-$kind.bin.$kind")
                archive.writeBytes(compress(kind, if (isTar) tar("folder/data.bin", payload) else payload))
                val result = ArchiveService.extract(provider, provider.getFileInfo(archive.path).getOrThrow(), root.path).getOrThrow()
                assertArrayEquals(payload, File(result.path, if (isTar) "folder/data.bin" else "data-$kind.bin").readBytes())
            }
        }
    }

    @Test fun truncatedTarFootersAreNotReportedComplete() = fixture { root, provider ->
        val payload = tar("data.txt", "retained".toByteArray()) + ByteArray(32768)
        for (kind in listOf("xz", "lz4", "zst")) {
            val bytes = compress(kind, payload)
            val archive = root.resolve("broken-$kind.tar.$kind").apply { writeBytes(bytes.copyOf(bytes.size - 4)) }
            val reports = mutableListOf<ArchiveExtractionReport>()
            assertTrue(kind, ArchiveService.extract(provider, provider.getFileInfo(archive.path).getOrThrow(), root.path, onReport = reports::add).isFailure)
            assertTrue(reports.none { it.complete })
        }
    }

    @Test fun compressedTarTraversalIsRejected() = fixture { root, provider ->
        for (kind in listOf("xz", "lz4", "zst")) {
            val archive = root.resolve("unsafe-$kind.tar.$kind").apply { writeBytes(compress(kind, tar("../escape.txt", "unsafe".toByteArray()))) }
            val result = ArchiveService.extract(provider, provider.getFileInfo(archive.path).getOrThrow(), root.path)
            assertTrue(result.exceptionOrNull() is UnsafeArchiveEntryException)
            assertFalse(root.resolve("unsafe-${kind}_extracted").exists())
            assertFalse(root.resolve("escape.txt").exists())
        }
    }

    @Test fun cancellingEachCodecRemovesItsOutput() = fixture { root, provider ->
        for (kind in listOf("xz", "lz4", "zst")) {
            val archive = root.resolve("cancel-$kind.bin.$kind").apply { writeBytes(compress(kind, ByteArray(1048576) { (it * 19).toByte() })) }
            val token = TransferCancellation()
            var cancelled = false
            try {
                withContext(token.contextElement()) {
                    ArchiveService.extract(provider, provider.getFileInfo(archive.path).getOrThrow(), root.path, onProgress = { if (it.processedBytes > 0) token.cancel() })
                }
            } catch (_: CancellationException) { cancelled = true }
            assertTrue("$kind cancellation", cancelled)
            assertFalse(root.resolve("cancel-$kind.bin_extracted").exists())
        }
    }
}
