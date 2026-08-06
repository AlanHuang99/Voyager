package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.StreamTransfer
import com.voyagerfiles.data.repository.StreamTransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DestinationConflictException(identifierOrName: String) :
    IllegalStateException(
        "An item named ${identifierOrName.substringAfterLast('/')} already exists in this folder",
    )

object FileOperationCoordinator {
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun uploadFile(
        source: UploadSource,
        destinationProvider: FileProvider,
        destinationDirectoryPath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireNameAvailable(destinationProvider, destinationDirectoryPath, source.name)

            var createdTargetPath: String? = null
            try {
                createdTargetPath = destinationProvider
                    .createFile(destinationDirectoryPath, source.name)
                    .getOrThrow()
                    .path
                source.openInputStream().use { input ->
                    destinationProvider.getOutputStream(createdTargetPath).getOrThrow().use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                    }
                }
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
        onProgress: (StreamTransferProgress) -> Unit = {},
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
        onProgress: (StreamTransferProgress) -> Unit,
    ) {
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
                return
            }

            createdTargetPath = destinationProvider
                .createFile(destinationDirectoryPath, item.name)
                .getOrThrow()
                .path
            sourceProvider.getInputStream(sourcePath).getOrThrow().use { input ->
                destinationProvider.getOutputStream(createdTargetPath).getOrThrow().use { output ->
                    StreamTransfer.copy(
                        input = input,
                        output = output,
                        path = sourcePath,
                        totalBytes = item.size.takeIf { it >= 0 },
                        onProgress = onProgress,
                    )
                }
            }
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
