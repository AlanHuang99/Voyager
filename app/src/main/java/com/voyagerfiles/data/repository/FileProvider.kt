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
    /**
     * Creates [name] in [path] and opens it for writing, failing instead of replacing an existing item.
     * A provider that picks a free name reports it in [NewFile.name]; callers needing [name] check it.
     */
    suspend fun openNewFile(path: String, name: String): Result<NewFile> = runCatching {
        val created = createFile(path, name).getOrThrow()
        val output = getOutputStream(created.path).getOrElse { error ->
            runCatching { delete(created.path).getOrThrow() }.onFailure(error::addSuppressed)
            throw error
        }
        NewFile(created.name, created.path, output)
    }
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
    /** Compares paths expressed in each provider's own namespace. */
    fun isSamePath(path: String, other: FileProvider, otherPath: String): Boolean =
        isSameStorage(other) && isSamePath(path, otherPath)
    suspend fun isDescendantPath(ancestor: String, other: FileProvider, path: String): Boolean =
        isSameStorage(other) && isDescendantPath(ancestor, path)
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
    /**
     * True where names that differ just in letter case are known to be one item and creating the second
     * one fails rather than overwriting, false where they are known to be distinct or a clash is caught
     * otherwise, and null where only the destination itself can tell, such as a server's file system.
     */
    fun ignoresNameCase(path: String): Boolean? = null
    suspend fun getFileInfo(path: String): Result<FileItem>
    fun getParentPath(path: String): String?
    suspend fun disconnect() {}
}

/** A file created by [FileProvider.openNewFile]; the caller closes [output]. */
class NewFile(val name: String, val path: String, val output: OutputStream)
