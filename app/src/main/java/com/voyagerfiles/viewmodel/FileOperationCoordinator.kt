package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileOperationCoordinator {
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun copyPath(
        sourceProvider: FileProvider,
        destinationProvider: FileProvider,
        sourcePath: String,
        destinationDirectoryPath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            copyPathInternal(sourceProvider, destinationProvider, sourcePath, destinationDirectoryPath)
        }
    }

    suspend fun movePath(
        sourceProvider: FileProvider,
        destinationProvider: FileProvider,
        sourcePath: String,
        destinationDirectoryPath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            copyPathInternal(sourceProvider, destinationProvider, sourcePath, destinationDirectoryPath)
            sourceProvider.delete(sourcePath).getOrThrow()
        }
    }

    private suspend fun copyPathInternal(
        sourceProvider: FileProvider,
        destinationProvider: FileProvider,
        sourcePath: String,
        destinationDirectoryPath: String,
    ) {
        val item = sourceProvider.getFileInfo(sourcePath).getOrThrow()
        val targetPath = joinPath(destinationDirectoryPath, item.name)
        if (item.isDirectory) {
            if (!destinationProvider.exists(targetPath)) {
                destinationProvider.createDirectory(destinationDirectoryPath, item.name).getOrThrow()
            }
            sourceProvider.listFiles(sourcePath).getOrThrow().forEach { child ->
                copyPathInternal(sourceProvider, destinationProvider, child.path, targetPath)
            }
            return
        }

        sourceProvider.getInputStream(sourcePath).getOrThrow().use { input ->
            destinationProvider.getOutputStream(targetPath).getOrThrow().use { output ->
                input.copyTo(output, BUFFER_SIZE)
            }
        }
    }

    private fun joinPath(path: String, name: String): String =
        if (path == "/") "/$name" else "${path.trimEnd('/')}/$name"
}
