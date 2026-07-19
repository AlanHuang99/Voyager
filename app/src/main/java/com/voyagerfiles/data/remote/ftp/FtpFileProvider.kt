package com.voyagerfiles.data.remote.ftp

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

class FtpFileProvider(
    private val connection: RemoteConnection,
    private val temporaryDirectory: File,
) : FileProvider {

    private var ftpClient: FTPClient? = null

    private suspend fun ensureConnected() = withContext(Dispatchers.IO) {
        val existingClient = ftpClient
        if (existingClient != null) {
            val connectionIsHealthy = existingClient.isConnected &&
                runCatching { existingClient.sendNoOp() }.getOrDefault(false)
            if (connectionIsHealthy) return@withContext
            runCatching { existingClient.disconnect() }
            ftpClient = null
        }

        val ftp = FTPClient().apply {
            connectTimeout = 30000
            defaultTimeout = 30000
        }
        try {
            ftp.connect(connection.host, connection.port)

            val reply = ftp.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                throw IllegalStateException("FTP server refused connection: $reply")
            }

            if (!ftp.login(connection.username, connection.password)) {
                throw IllegalStateException("FTP login failed")
            }

            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftpClient = ftp
        } catch (error: Throwable) {
            runCatching { if (ftp.isConnected) ftp.disconnect() }
            throw error
        }
    }

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val ftp = ftpClient!!
                ftp.listFiles(path).filter { it.name != "." && it.name != ".." }.map { file ->
                    FileItem(
                        name = file.name,
                        path = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}",
                        isDirectory = file.isDirectory,
                        size = file.size,
                        lastModified = Date(file.timestamp.timeInMillis),
                        isHidden = file.name.startsWith("."),
                        owner = file.user,
                        source = FileSource.FTP,
                    )
                }
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                if (!ftpClient!!.makeDirectory(fullPath)) {
                    throw IllegalStateException("Failed to create directory")
                }
                FileItem(name = name, path = fullPath, isDirectory = true, source = FileSource.FTP)
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                if (!ftpClient!!.storeFile(fullPath, ByteArrayInputStream(ByteArray(0)))) {
                    throw IllegalStateException("Failed to create file")
                }
                FileItem(name = name, path = fullPath, isDirectory = false, source = FileSource.FTP)
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val ftp = ftpClient!!
                if (ftp.isDirectory(path)) {
                    deleteDirectoryRecursive(ftp, path)
                } else {
                    if (!ftp.deleteFile(path)) {
                        throw IllegalStateException("Failed to delete: $path")
                    }
                }
            }
        }

    private fun deleteDirectoryRecursive(ftp: FTPClient, path: String) {
        for (file in ftp.listFiles(path)) {
            if (file.name == "." || file.name == "..") continue
            val filePath = "$path/${file.name}"
            if (file.isDirectory) {
                deleteDirectoryRecursive(ftp, filePath)
            } else {
                ftp.deleteFile(filePath)
            }
        }
        ftp.removeDirectory(path)
    }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val parent = oldPath.substringBeforeLast("/")
                val newPath = "$parent/$newName"
                if (!ftpClient!!.rename(oldPath, newPath)) {
                    throw IllegalStateException("Failed to rename")
                }
                FileItem(name = newName, path = newPath, isDirectory = false, source = FileSource.FTP)
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val ftp = ftpClient!!
                val name = sourcePath.substringAfterLast("/")
                val targetPath = joinPath(destPath, name)
                if (ftp.isDirectory(sourcePath)) {
                    copyDirectoryRecursive(ftp, sourcePath, targetPath)
                    return@runCatching
                }

                copyFileThroughTemporaryStorage(ftp, sourcePath, targetPath)
                Unit
            }
        }

    private fun copyDirectoryRecursive(ftp: FTPClient, sourcePath: String, targetPath: String) {
        if (!ftp.makeDirectory(targetPath) && !ftp.isDirectory(targetPath)) {
            throw IllegalStateException("Failed to create directory: $targetPath")
        }

        for (file in ftp.listFiles(sourcePath)) {
            if (file.name == "." || file.name == "..") continue
            val sourceChild = joinPath(sourcePath, file.name)
            val targetChild = joinPath(targetPath, file.name)
            if (file.isDirectory) {
                copyDirectoryRecursive(ftp, sourceChild, targetChild)
            } else {
                copyFileThroughTemporaryStorage(ftp, sourceChild, targetChild)
            }
        }
    }

    private fun copyFileThroughTemporaryStorage(
        ftp: FTPClient,
        sourcePath: String,
        targetPath: String,
    ) {
        check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
            "Could not prepare temporary storage for the copy"
        }
        val temporaryFile = File.createTempFile("voyager-ftp-", ".copy", temporaryDirectory)
        try {
            FileOutputStream(temporaryFile).use { output ->
                if (!ftp.retrieveFile(sourcePath, output)) {
                    throw IllegalStateException("Failed to read: $sourcePath")
                }
            }
            FileInputStream(temporaryFile).use { input ->
                if (!ftp.storeFile(targetPath, input)) {
                    throw IllegalStateException("Failed to write: $targetPath")
                }
            }
        } finally {
            temporaryFile.delete()
        }
    }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                if (!ftpClient!!.rename(sourcePath, "$destPath/$name")) {
                    throw IllegalStateException("Failed to move")
                }
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val ftp = ftpClient!!
                val input = ftp.retrieveFileStream(path)
                    ?: throw IllegalStateException("Failed to read: $path")
                PendingCommandInputStream(ftp, input, path) as InputStream
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val ftp = ftpClient!!
                val output = ftp.storeFileStream(path)
                    ?: throw IllegalStateException("Failed to write: $path")
                PendingCommandOutputStream(ftp, output, path) as OutputStream
            }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                ftpClient!!.listFiles(path).isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val files = ftpClient!!.listFiles(path)
                if (files.isEmpty()) throw IllegalArgumentException("Not found: $path")
                val file = files[0]
                FileItem(
                    name = file.name,
                    path = path,
                    isDirectory = file.isDirectory,
                    size = file.size,
                    lastModified = Date(file.timestamp.timeInMillis),
                    source = FileSource.FTP,
                )
            }
        }

    override fun getParentPath(path: String): String? {
        if (path == "/") return null
        val parent = path.substringBeforeLast("/")
        return parent.ifEmpty { "/" }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            runCatching {
                ftpClient?.logout()
                ftpClient?.disconnect()
            }
            ftpClient = null
        }
    }

    private fun FTPClient.isDirectory(path: String): Boolean {
        val currentPath = printWorkingDirectory()
        val changed = changeWorkingDirectory(path)
        if (changed && currentPath != null) {
            changeWorkingDirectory(currentPath)
        }
        return changed
    }

    private fun joinPath(parent: String, child: String): String = when {
        parent.isBlank() || parent == "/" -> "/$child"
        parent.endsWith("/") -> "$parent$child"
        else -> "$parent/$child"
    }

    private class PendingCommandInputStream(
        private val ftp: FTPClient,
        input: InputStream,
        private val path: String,
    ) : FilterInputStream(input) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                if (!ftp.completePendingCommand()) {
                    throw IllegalStateException("Failed to finish reading: $path")
                }
            }
        }
    }

    private class PendingCommandOutputStream(
        private val ftp: FTPClient,
        output: OutputStream,
        private val path: String,
    ) : FilterOutputStream(output) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                if (!ftp.completePendingCommand()) {
                    throw IllegalStateException("Failed to finish writing: $path")
                }
            }
        }
    }
}
