package com.voyagerfiles.data.preview

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.isNetwork
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.TransferAbortable
import com.voyagerfiles.data.repository.TransferCancellation
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class RemoteThumbnailSource(
    private val requestTimeoutMillis: Long = 15_000,
    private val providerFactory: () -> FileProvider,
) : Closeable {
    private class Reader(var provider: FileProvider? = null)
    private val readers = List(2) { Reader() }
    private val available = Channel<Reader>(readers.size).apply { readers.forEach { trySend(it) } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val cancellations = ConcurrentHashMap.newKeySet<TransferCancellation>()

    suspend fun <T> load(file: FileItem, transform: (ByteArray) -> T): T {
        check(!closed.get()) { "Thumbnail session is closed" }
        require(file.source.isNetwork && !file.isDirectory && file.isImage) { "Remote previews require an image" }
        require(file.size <= MAX_SOURCE_BYTES) { "Image exceeds the thumbnail transfer limit" }
        val cancellation = TransferCancellation()
        cancellations += cancellation
        val task = scope.async(cancellation.contextElement()) {
            permits.withPermit {
                val reader = available.receive()
                var healthy = false
                try {
                    currentCoroutineContext().ensureActive()
                    val provider = reader.provider ?: providerFactory().also { reader.provider = it }
                    val bytes = readBounded(provider.getInputStream(file.path).getOrThrow())
                    currentCoroutineContext().ensureActive()
                    transform(bytes).also {
                        currentCoroutineContext().ensureActive()
                        healthy = true
                    }
                } finally {
                    if (!healthy) withContext(NonCancellable) {
                        runCatching { reader.provider?.disconnect() }
                        reader.provider = null
                    }
                    available.trySend(reader)
                }
            }
        }
        return try {
            withTimeout(requestTimeoutMillis) { task.await() }
                .also { check(!closed.get()) { "Thumbnail session is closed" } }
        } finally {
            cancellation.cancel()
            task.cancel()
            cancellations -= cancellation
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        cancellations.forEach(TransferCancellation::cancel)
        CoroutineScope(Dispatchers.IO).launch {
            scope.coroutineContext[Job]?.join()
            readers.forEach { reader ->
                runCatching { reader.provider?.disconnect() }
                reader.provider = null
            }
        }
    }

    private suspend fun readBounded(input: InputStream): ByteArray {
        return TransferCancellation.registerAbort { input.abort() }.use {
            input.use {
                try {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        TransferCancellation.check()
                        val remaining = MAX_SOURCE_BYTES - output.size() + 1
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        if (count == 0) continue
                        require(output.size() + count <= MAX_SOURCE_BYTES) { "Image exceeds the thumbnail transfer limit" }
                        output.write(buffer, 0, count)
                    }
                    currentCoroutineContext().ensureActive()
                    output.toByteArray()
                } catch (error: Throwable) {
                    runCatching { input.abort() }
                    throw error
                }
            }
        }
    }

    private fun InputStream.abort() {
        if (this is TransferAbortable) abortTransfer() else close()
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        val permits = Semaphore(2)
    }
}
