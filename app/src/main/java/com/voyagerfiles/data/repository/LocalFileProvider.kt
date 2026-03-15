package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

class LocalFileProvider : FileProvider {

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(path)
                if (!dir.exists()) throw IllegalArgumentException("Path does not exist: $path")
                if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: $path")
                dir.listFiles()?.map { it.toFileItem() } ?: emptyList()
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(path, name)
                if (!dir.mkdirs()) throw IllegalStateException("Failed to create directory: ${dir.absolutePath}")
                dir.toFileItem()
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path, name)
                if (!file.createNewFile()) throw IllegalStateException("Failed to create file: ${file.absolutePath}")
                file.toFileItem()
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (file.isDirectory) {
                    if (!file.deleteRecursively()) throw IllegalStateException("Failed to delete: $path")
                } else {
                    if (!file.delete()) throw IllegalStateException("Failed to delete: $path")
                }
            }
        }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val oldFile = File(oldPath)
                val newFile = File(oldFile.parent, newName)
                if (!oldFile.renameTo(newFile)) throw IllegalStateException("Failed to rename")
                newFile.toFileItem()
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(sourcePath)
                val dest = File(destPath, source.name)
                if (source.isDirectory) {
                    source.copyRecursively(dest, overwrite = false)
                } else {
                    source.copyTo(dest, overwrite = false)
                }
                Unit
            }
        }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(sourcePath)
                val dest = File(destPath, source.name)
                if (!source.renameTo(dest)) {
                    // Fallback: copy then delete
                    if (source.isDirectory) {
                        source.copyRecursively(dest, overwrite = false)
                    } else {
                        source.copyTo(dest, overwrite = false)
                    }
                    source.deleteRecursively()
                }
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching { FileInputStream(File(path)) }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching { FileOutputStream(File(path)) }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) { File(path).exists() }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching { File(path).toFileItem() }
        }

    override fun getParentPath(path: String): String? = File(path).parent

    private fun File.toFileItem(): FileItem = FileItem(
        name = name,
        path = absolutePath,
        isDirectory = isDirectory,
        size = if (isDirectory) 0 else length(),
        lastModified = Date(lastModified()),
        isHidden = isHidden,
        permissions = buildPermissionString(),
        source = FileSource.LOCAL,
    )

    private fun File.buildPermissionString(): String = buildString {
        append(if (canRead()) "r" else "-")
        append(if (canWrite()) "w" else "-")
        append(if (canExecute()) "x" else "-")
    }
}
