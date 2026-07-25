package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DestinationConflictException(val path: String) :
    IllegalStateException("An item named ${path.substringAfterLast('/')} already exists in this folder")

object FileOperationCoordinator {
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun uploadFile(
        source: UploadSource,
        destinationProvider: FileProvider,
        destinationDirectoryPath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetPath = joinPath(destinationDirectoryPath, source.name)
            if (destinationProvider.exists(targetPath)) throw DestinationConflictException(targetPath)

            var targetCreated = false
            try {
                source.openInputStream().use { input ->
                    destinationProvider.getOutputStream(targetPath).getOrThrow().use { output ->
                        targetCreated = true
                        input.copyTo(output, BUFFER_SIZE)
                    }
                }
            } catch (error: Throwable) {
                if (targetCreated) {
                    runCatching {
                        if (destinationProvider.exists(targetPath)) {
                            destinationProvider.delete(targetPath).getOrThrow()
                        }
                    }.onFailure(error::addSuppressed)
                }
                throw error
            }
            Unit
        }
    }

    suspend fun copyPath(
        sourceProvider: FileProvider,
        destinationProvider: FileProvider,
        sourcePath: String,
        destinationDirectoryPath: String,
        onProgress: (StreamCopyProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            copyPathInternal(
                sourceProvider,
                destinationProvider,
                sourcePath,
                destinationDirectoryPath,
                onProgress,
            )
        }
    }

    suspend fun movePath(
        sourceProvider: FileProvider,
        destinationProvider: FileProvider,
        sourcePath: String,
        destinationDirectoryPath: String,
        onProgress: (StreamCopyProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            copyPathInternal(
                sourceProvider,
                destinationProvider,
                sourcePath,
                destinationDirectoryPath,
                onProgress,
            )
            sourceProvider.delete(sourcePath).getOrThrow()
        }
    }

    private suspend fun copyPathInternal(
        sourceProvider: FileProvider,
        destinationProvider: FileProvider,
        sourcePath: String,
        destinationDirectoryPath: String,
        onProgress: (StreamCopyProgress) -> Unit,
    ) {
        val item = sourceProvider.getFileInfo(sourcePath).getOrThrow()
        val targetPath = joinPath(destinationDirectoryPath, item.name)
        if (destinationProvider.exists(targetPath)) throw DestinationConflictException(targetPath)

        var targetCreated = false
        try {
            if (item.isDirectory) {
                destinationProvider.createDirectory(destinationDirectoryPath, item.name).getOrThrow()
                targetCreated = true
                sourceProvider.listFiles(sourcePath).getOrThrow().forEach { child ->
                    copyPathInternal(
                        sourceProvider,
                        destinationProvider,
                        child.path,
                        targetPath,
                        onProgress,
                    )
                }
                return
            }

            sourceProvider.getInputStream(sourcePath).getOrThrow().use { input ->
                destinationProvider.getOutputStream(targetPath).getOrThrow().use { output ->
                    targetCreated = true
                    copyStream(
                        input = input,
                        output = output,
                        path = sourcePath,
                        totalBytes = item.size.takeIf { it >= 0 },
                        onProgress = onProgress,
                    )
                }
            }
        } catch (error: Throwable) {
            if (targetCreated) {
                runCatching {
                    if (destinationProvider.exists(targetPath)) {
                        destinationProvider.delete(targetPath).getOrThrow()
                    }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        path: String,
        totalBytes: Long?,
        onProgress: (StreamCopyProgress) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesCopied = 0L
        var reported = false
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            bytesCopied += read
            reported = true
            onProgress(
                StreamCopyProgress(
                    path = path,
                    bytesCopied = bytesCopied,
                    totalBytes = totalBytes,
                )
            )
        }
        if (!reported) {
            onProgress(
                StreamCopyProgress(
                    path = path,
                    bytesCopied = 0,
                    totalBytes = totalBytes,
                )
            )
        }
    }

    private fun joinPath(path: String, name: String): String =
        if (path == "/") "/$name" else "${path.trimEnd('/')}/$name"
}
