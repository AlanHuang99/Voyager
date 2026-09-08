package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileItem
import java.io.InputStream
import java.io.OutputStream

interface FileProvider {
    suspend fun listFiles(path: String): Result<List<FileItem>>
    suspend fun createDirectory(path: String, name: String): Result<FileItem>
    suspend fun createFile(path: String, name: String): Result<FileItem>
    suspend fun delete(path: String): Result<Unit>
    suspend fun rename(oldPath: String, newName: String): Result<FileItem>
    suspend fun copy(sourcePath: String, destPath: String): Result<Unit>
    suspend fun move(sourcePath: String, destPath: String): Result<Unit>
    suspend fun getInputStream(path: String): Result<InputStream>
    suspend fun getOutputStream(path: String): Result<OutputStream>
    suspend fun writeStream(
        path: String,
        input: InputStream,
        sourcePath: String,
        totalBytes: Long?,
        onProgress: (StreamTransferProgress) -> Unit = {},
    ): Result<Unit> = runCatching {
        getOutputStream(path).getOrThrow().use { output ->
            StreamTransfer.copy(
                input = input,
                output = output,
                path = sourcePath,
                totalBytes = totalBytes,
                onProgress = onProgress,
            )
        }
    }
    /** Copies a file into an already-created target. Protocols may serialize reads and writes. */
    suspend fun copyFileTo(
        sourcePath: String,
        targetPath: String,
        totalBytes: Long?,
        onProgress: (StreamTransferProgress) -> Unit = {},
    ): Result<Unit> = runCatching {
        getInputStream(sourcePath).getOrThrow().use { input ->
            writeStream(targetPath, input, sourcePath, totalBytes, onProgress).getOrThrow()
        }
    }
    fun isSameStorage(other: FileProvider): Boolean = this === other
    fun isSamePath(first: String, second: String): Boolean = first.trimEnd('/') == second.trimEnd('/')
    suspend fun isDescendantPath(ancestor: String, path: String): Boolean {
        var parent: String? = path
        val seen = mutableSetOf<String>()
        while (parent != null && seen.add(parent)) {
            if (isSamePath(ancestor, parent)) return true
            parent = getParentPath(parent)
        }
        return false
    }
    suspend fun exists(path: String): Boolean
    suspend fun getFileInfo(path: String): Result<FileItem>
    fun getParentPath(path: String): String?
    suspend fun disconnect() {}
}
