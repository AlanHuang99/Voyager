package com.voyagerfiles.data.remote.ftp

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.StreamTransfer
import com.voyagerfiles.data.repository.StreamTransferProgress
import com.voyagerfiles.data.repository.TransferCancellation
import com.voyagerfiles.data.repository.TransferAbortable
import com.voyagerfiles.data.repository.ForwardingOutputStream
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
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

class FtpFileProvider(
    private val connection: RemoteConnection,
    private val temporaryDirectory: File,
) : FileProvider {
    override fun isSameStorage(other: FileProvider): Boolean = other is FtpFileProvider &&
        connection.host.equals(other.connection.host, ignoreCase = true) && connection.port == other.connection.port &&
        connection.username == other.connection.username && connection.shareName == other.connection.shareName

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
            setDataTimeout(java.time.Duration.ofSeconds(30))
            bufferSize = TRANSFER_BUFFER_SIZE
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
                TransferCancellation.check()
                check(ftp.listFiles(destPath).none { it.name == name }) {
                    "An item named $name already exists in this folder"
                }
                var ownsTarget = false
                try {
                    if (ftp.isDirectory(sourcePath)) {
                        check(ftp.makeDirectory(targetPath)) { "Failed to create directory: $targetPath" }
                        ownsTarget = true
                        copyDirectoryRecursive(ftp, sourcePath, targetPath)
                    } else {
                        copyFileThroughTemporaryStorage(ftp, sourcePath, targetPath) { ownsTarget = true }
                    }
                    TransferCancellation.check()
                } catch (error: Throwable) {
                    if (ownsTarget) runCatching { delete(targetPath).getOrThrow() }.onFailure(error::addSuppressed)
                    throw error
                }
                Unit
            }
        }

    private fun copyDirectoryRecursive(ftp: FTPClient, sourcePath: String, targetPath: String) {
        TransferCancellation.check()
        for (file in ftp.listFiles(sourcePath)) {
            if (file.name == "." || file.name == "..") continue
            val sourceChild = joinPath(sourcePath, file.name)
            val targetChild = joinPath(targetPath, file.name)
            if (file.isDirectory) {
                TransferCancellation.check()
                check(ftp.makeDirectory(targetChild)) { "Failed to create directory: $targetChild" }
                copyDirectoryRecursive(ftp, sourceChild, targetChild)
            } else {
                copyFileThroughTemporaryStorage(ftp, sourceChild, targetChild)
            }
        }
    }

    override suspend fun copyFileTo(
        sourcePath: String,
        targetPath: String,
        totalBytes: Long?,
        onProgress: (StreamTransferProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureConnected()
            copyFileThroughTemporaryStorage(ftpClient!!, sourcePath, targetPath, onProgress = onProgress)
        }
    }

    private fun copyFileThroughTemporaryStorage(
        ftp: FTPClient,
        sourcePath: String,
        targetPath: String,
        onProgress: (StreamTransferProgress) -> Unit = {},
        onTargetCreated: () -> Unit = {},
    ) {
        TransferCancellation.check()
        check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
            "Could not prepare temporary storage for the copy"
        }
        val temporaryFile = File.createTempFile("voyager-ftp-", ".copy", temporaryDirectory)
        try {
            val source = ftp.retrieveFileStream(sourcePath)
                ?: throw IllegalStateException("Failed to read: $sourcePath")
            PendingCommandInputStream(ftp, source, sourcePath).use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    StreamTransfer.copy(input, output, sourcePath, null)
                }
            }
            TransferCancellation.check()
            onTargetCreated()
            val target = ftp.storeFileStream(targetPath)
                ?: throw IllegalStateException("Failed to write: $targetPath")
            PendingCommandOutputStream(ftp, target, targetPath).use { output ->
                FileInputStream(temporaryFile).use { input ->
                    StreamTransfer.copy(input, output, sourcePath, temporaryFile.length(), onProgress = onProgress)
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
    ) : FilterInputStream(input), TransferAbortable {
        override fun abortTransfer() {
            runCatching { `in`.close() }
            runCatching { ftp.disconnect() }
        }
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
        private val output: OutputStream,
        private val path: String,
    ) : ForwardingOutputStream(output), TransferAbortable {
        override fun abortTransfer() {
            runCatching { output.close() }
            runCatching { ftp.disconnect() }
        }
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

    private companion object {
        // Matches the StreamTransfer chunk size. Commons Net otherwise buffers data connections
        // in 8 KiB and copies whole files, as used by provider-local copy, in 1 KiB steps.
        const val TRANSFER_BUFFER_SIZE = 64 * 1024
    }
}
