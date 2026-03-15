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
    suspend fun exists(path: String): Boolean
    suspend fun getFileInfo(path: String): Result<FileItem>
    fun getParentPath(path: String): String?
    suspend fun disconnect() {}
}
