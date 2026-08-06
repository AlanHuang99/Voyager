package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileDownloader {

    suspend fun download(
        provider: FileProvider,
        items: List<FileItem>,
        destinationDirectory: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<DownloadResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
                    throw IllegalStateException("Could not create Downloads folder")
                }
                if (!destinationDirectory.isDirectory) {
                    throw IllegalStateException("Downloads path is not a folder")
                }

                val counts = DownloadCounts()
                items.forEachIndexed { index, item ->
                    var latestStreamProgress: StreamTransferProgress? = null
                    onProgress(
                        DownloadProgress(
                            completedRequestedItems = index,
                            totalRequestedItems = items.size,
                            stream = null,
                        ),
                    )
                    downloadItem(
                        provider = provider,
                        item = item,
                        destinationDirectory = destinationDirectory,
                        counts = counts,
                        onStreamProgress = { stream ->
                            latestStreamProgress = stream
                            onProgress(
                                DownloadProgress(
                                    completedRequestedItems = index,
                                    totalRequestedItems = items.size,
                                    stream = stream,
                                ),
                            )
                        },
                    )
                    onProgress(
                        DownloadProgress(
                            completedRequestedItems = index + 1,
                            totalRequestedItems = items.size,
                            stream = latestStreamProgress,
                        ),
                    )
                }
                DownloadResult(
                    requestedItems = items.size,
                    downloadedFiles = counts.files,
                    downloadedDirectories = counts.directories,
                    destinationPath = destinationDirectory.absolutePath,
                )
            }
        }

    private suspend fun downloadItem(
        provider: FileProvider,
        item: FileItem,
        destinationDirectory: File,
        counts: DownloadCounts,
        onStreamProgress: (StreamTransferProgress) -> Unit,
    ) {
        if (item.isDirectory) {
            val targetDirectory = uniqueChild(destinationDirectory, item.safeName(), isDirectory = true)
            if (!targetDirectory.mkdirs()) {
                throw IllegalStateException("Could not create folder: ${targetDirectory.name}")
            }
            try {
                counts.directories++
                val children = provider.listFiles(item.path).getOrThrow()
                children.forEach { child ->
                    downloadItem(provider, child, targetDirectory, counts, onStreamProgress)
                }
            } catch (error: Throwable) {
                runCatching {
                    check(targetDirectory.deleteRecursively() || !targetDirectory.exists()) {
                        "Could not remove partial folder: ${targetDirectory.name}"
                    }
                }.onFailure(error::addSuppressed)
                throw error
            }
            return
        }

        val targetFile = uniqueChild(destinationDirectory, item.safeName(), isDirectory = false)
        try {
            provider.getInputStream(item.path).getOrThrow().use { input ->
                targetFile.outputStream().use { output ->
                    StreamTransfer.copy(
                        input = input,
                        output = output,
                        path = item.path,
                        totalBytes = item.size.takeIf { it >= 0 },
                        onProgress = onStreamProgress,
                    )
                }
            }
        } catch (error: Throwable) {
            runCatching {
                check(targetFile.delete() || !targetFile.exists()) {
                    "Could not remove partial file: ${targetFile.name}"
                }
            }.onFailure(error::addSuppressed)
            throw error
        }
        counts.files++
    }

    private fun uniqueChild(parent: File, name: String, isDirectory: Boolean): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate

        val extensionStart = name.lastIndexOf('.').takeIf { !isDirectory && it > 0 && it < name.lastIndex }
        val baseName = extensionStart?.let { name.substring(0, it) } ?: name
        val extension = extensionStart?.let { name.substring(it) } ?: ""

        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, "$baseName ($index)$extension")
            index++
        }
        return candidate
    }

    private fun FileItem.safeName(): String =
        name.ifBlank { path.substringAfterLast("/").ifBlank { "download" } }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")

    private class DownloadCounts {
        var files: Int = 0
        var directories: Int = 0
    }
}

data class DownloadResult(
    val requestedItems: Int,
    val downloadedFiles: Int,
    val downloadedDirectories: Int,
    val destinationPath: String,
)

data class DownloadProgress(
    val completedRequestedItems: Int,
    val totalRequestedItems: Int,
    val stream: StreamTransferProgress?,
) {
    init {
        require(completedRequestedItems >= 0) { "Completed item count must not be negative" }
        require(totalRequestedItems >= 0) { "Total item count must not be negative" }
        require(completedRequestedItems <= totalRequestedItems) {
            "Completed item count must not exceed total item count"
        }
    }
}
