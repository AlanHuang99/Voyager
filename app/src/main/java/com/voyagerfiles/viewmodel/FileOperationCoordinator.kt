package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.TransferCancellation
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.StreamTransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DestinationConflictException(identifierOrName: String) :
    IllegalStateException(
        "An item named ${identifierOrName.substringAfterLast('/')} already exists in this folder",
    )

object FileOperationCoordinator {
    suspend fun uploadFile(
        source: UploadSource,
        destinationProvider: FileProvider,
        destinationDirectoryPath: String,
        onProgress: (StreamTransferProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            TransferCancellation.check()
            requireNameAvailable(destinationProvider, destinationDirectoryPath, source.name)

            var createdTargetPath: String? = null
            try {
                createdTargetPath = destinationProvider
                    .createFile(destinationDirectoryPath, source.name)
                    .getOrThrow()
                    .path
                source.openInputStream().use { input ->
                    destinationProvider.writeStream(
                        path = createdTargetPath,
                        input = input,
                        sourcePath = source.name,
                        totalBytes = source.size,
                        onProgress = onProgress,
                    ).getOrThrow()
                }
                TransferCancellation.check()
            } catch (error: Throwable) {
                if (createdTargetPath != null) {
                    runCatching {
                        if (destinationProvider.exists(createdTargetPath)) {
                            destinationProvider.delete(createdTargetPath).getOrThrow()
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
        onProgress: (StreamTransferProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            copyPathInternal(sourceProvider, destinationProvider, sourcePath, destinationDirectoryPath, onProgress)
            Unit
        }
    }

    suspend fun movePath(
        sourceProvider: FileProvider,
        destinationProvider: FileProvider,
        sourcePath: String,
        destinationDirectoryPath: String,
        onProgress: (StreamTransferProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = copyPathInternal(sourceProvider, destinationProvider, sourcePath, destinationDirectoryPath, onProgress)
            try {
                TransferCancellation.check()
            } catch (error: Throwable) {
                runCatching { destinationProvider.delete(target).getOrThrow() }.onFailure(error::addSuppressed)
                throw error
            }
            sourceProvider.delete(sourcePath).getOrThrow()
        }
    }

    private suspend fun copyPathInternal(
        sourceProvider: FileProvider,
        destinationProvider: FileProvider,
        sourcePath: String,
        destinationDirectoryPath: String,
        onProgress: (StreamTransferProgress) -> Unit,
    ): String {
        TransferCancellation.check()
        val item = sourceProvider.getFileInfo(sourcePath).getOrThrow()
        requireNameAvailable(destinationProvider, destinationDirectoryPath, item.name)

        var createdTargetPath: String? = null
        try {
            if (item.isDirectory) {
                createdTargetPath = destinationProvider
                    .createDirectory(destinationDirectoryPath, item.name)
                    .getOrThrow()
                    .path
                sourceProvider.listFiles(sourcePath).getOrThrow().forEach { child ->
                    copyPathInternal(
                        sourceProvider,
                        destinationProvider,
                        child.path,
                        createdTargetPath,
                        onProgress,
                    )
                }
                TransferCancellation.check()
                return createdTargetPath
            }

            createdTargetPath = destinationProvider
                .createFile(destinationDirectoryPath, item.name)
                .getOrThrow()
                .path
            sourceProvider.getInputStream(sourcePath).getOrThrow().use { input ->
                destinationProvider.writeStream(
                    path = createdTargetPath,
                    input = input,
                    sourcePath = sourcePath,
                    totalBytes = item.size.takeIf { it >= 0 },
                    onProgress = onProgress,
                ).getOrThrow()
            }
            TransferCancellation.check()
            return createdTargetPath
        } catch (error: Throwable) {
            if (createdTargetPath != null) {
                runCatching {
                    if (destinationProvider.exists(createdTargetPath)) {
                        destinationProvider.delete(createdTargetPath).getOrThrow()
                    }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private suspend fun requireNameAvailable(
        provider: FileProvider,
        directoryPath: String,
        name: String,
    ) {
        if (provider.listFiles(directoryPath).getOrThrow().any { it.name == name }) {
            throw DestinationConflictException(name)
        }
    }
}
