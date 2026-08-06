package com.voyagerfiles.data.repository

import java.io.InputStream
import java.io.OutputStream

data class StreamTransferProgress(
    val path: String,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val elapsedNanos: Long,
) {
    init {
        require(path.isNotBlank()) { "Progress path must not be blank" }
        require(bytesTransferred >= 0) { "Transferred byte count must not be negative" }
        require(totalBytes == null || totalBytes >= 0) { "Total byte count must not be negative" }
        require(elapsedNanos >= 0) { "Elapsed duration must not be negative" }
    }
}

object StreamTransfer {
    private const val BUFFER_SIZE = 64 * 1024

    fun copy(
        input: InputStream,
        output: OutputStream,
        path: String,
        totalBytes: Long?,
        nanoTime: () -> Long = System::nanoTime,
        onProgress: (StreamTransferProgress) -> Unit = {},
    ) {
        val startedAt = nanoTime()
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesTransferred = 0L
        var reported = false
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            bytesTransferred += read
            reported = true
            onProgress(
                StreamTransferProgress(
                    path = path,
                    bytesTransferred = bytesTransferred,
                    totalBytes = totalBytes,
                    elapsedNanos = (nanoTime() - startedAt).coerceAtLeast(0),
                ),
            )
        }
        if (!reported) {
            onProgress(
                StreamTransferProgress(
                    path = path,
                    bytesTransferred = 0,
                    totalBytes = totalBytes,
                    elapsedNanos = (nanoTime() - startedAt).coerceAtLeast(0),
                ),
            )
        }
    }
}
