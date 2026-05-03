package com.voyagerfiles.data.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.EnumSet

class SmbFileProvider(private val connection: RemoteConnection) : FileProvider {

    private var smbClient: SMBClient? = null
    private var smbConnection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private suspend fun ensureConnected() {
        if (share != null) return
        withContext(Dispatchers.IO) {
            val shareName = connection.shareName ?: throw IllegalStateException("SMB share name is required")
            var client: SMBClient? = null
            var conn: Connection? = null
            var sess: Session? = null
            var diskShare: DiskShare? = null

            try {
                client = SMBClient()
                conn = client.connect(connection.host, connection.port)
                val authContext = AuthenticationContext(
                    connection.username,
                    connection.password.toCharArray(),
                    connection.domain ?: "",
                )
                sess = conn.authenticate(authContext)
                diskShare = sess.connectShare(shareName) as DiskShare

                smbClient = client
                smbConnection = conn
                session = sess
                share = diskShare
            } catch (error: Throwable) {
                runCatching { diskShare?.close() }
                runCatching { sess?.close() }
                runCatching { conn?.close() }
                runCatching { client?.close() }
                throw error
            }
        }
    }

    private fun toSmbPath(path: String): String {
        // Convert Unix-style path to SMB path (backslashes, no leading slash)
        val smbPath = path.removePrefix("/").replace("/", "\\")
        return smbPath.ifEmpty { "" }
    }

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val smbPath = toSmbPath(path)
                val listPath = if (smbPath.isEmpty()) "" else smbPath
                share!!.list(listPath).filter { it.fileName != "." && it.fileName != ".." }.map { info ->
                    val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    val filePath = if (path.endsWith("/")) "$path${info.fileName}" else "$path/${info.fileName}"
                    FileItem(
                        name = info.fileName,
                        path = filePath,
                        isDirectory = isDir,
                        size = info.endOfFile,
                        lastModified = Date(info.lastWriteTime.toEpochMillis()),
                        isHidden = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L,
                        source = FileSource.SMB,
                    )
                }
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                share!!.mkdir(toSmbPath(fullPath))
                FileItem(name = name, path = fullPath, isDirectory = true, source = FileSource.SMB)
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                val file = share!!.openFile(
                    toSmbPath(fullPath),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_CREATE,
                    null,
                )
                file.close()
                FileItem(name = name, path = fullPath, isDirectory = false, source = FileSource.SMB)
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val smbPath = toSmbPath(path)
                if (share!!.folderExists(smbPath)) {
                    deleteDirectoryRecursive(smbPath)
                } else {
                    share!!.rm(smbPath)
                }
            }
        }

    private fun deleteDirectoryRecursive(smbPath: String) {
        val diskShare = share!!
        for (info in diskShare.list(smbPath)) {
            if (info.fileName == "." || info.fileName == "..") continue
            val entryPath = "$smbPath\\${info.fileName}"
            if (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L) {
                deleteDirectoryRecursive(entryPath)
            } else {
                diskShare.rm(entryPath)
            }
        }
        diskShare.rmdir(smbPath, false)
    }

    private fun DiskShare.openForRename(path: String, isDirectory: Boolean): DiskEntry =
        if (isDirectory) {
            openDirectory(
                path,
                EnumSet.of(
                    AccessMask.DELETE,
                    AccessMask.FILE_READ_ATTRIBUTES,
                    AccessMask.FILE_WRITE_ATTRIBUTES,
                ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } else {
            openFile(
                path,
                EnumSet.of(
                    AccessMask.DELETE,
                    AccessMask.FILE_READ_ATTRIBUTES,
                    AccessMask.FILE_WRITE_ATTRIBUTES,
                ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val oldSmb = toSmbPath(oldPath)
                val parent = oldSmb.substringBeforeLast("\\", "")
                val newSmb = if (parent.isEmpty()) newName else "$parent\\$newName"
                val parentPath = oldPath.substringBeforeLast("/")
                val newPath = "$parentPath/$newName"
                val wasDirectory = share!!.folderExists(oldSmb)

                val file = share!!.openForRename(oldSmb, wasDirectory)
                try {
                    file.rename(newSmb)
                } finally {
                    file.close()
                }
                FileItem(name = newName, path = newPath, isDirectory = wasDirectory, source = FileSource.SMB)
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                val sourceSmb = toSmbPath(sourcePath)
                val destSmb = toSmbPath("$destPath/$name")
                copyPath(sourceSmb, destSmb)
            }
        }

    private fun copyPath(sourceSmb: String, destSmb: String) {
        val diskShare = share!!
        if (diskShare.folderExists(sourceSmb)) {
            diskShare.mkdir(destSmb)
            for (info in diskShare.list(sourceSmb)) {
                if (info.fileName == "." || info.fileName == "..") continue
                val sourceChild = if (sourceSmb.isEmpty()) info.fileName else "$sourceSmb\\${info.fileName}"
                val destChild = if (destSmb.isEmpty()) info.fileName else "$destSmb\\${info.fileName}"
                copyPath(sourceChild, destChild)
            }
            return
        }

        val srcFile = diskShare.openFile(
            sourceSmb,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val destFile = diskShare.openFile(
            destSmb,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            null,
        )

        try {
            srcFile.remoteCopyTo(destFile)
        } finally {
            runCatching { srcFile.close() }
            destFile.close()
        }
    }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                val sourceSmb = toSmbPath(sourcePath)
                val destSmb = toSmbPath("$destPath/$name")
                val isDirectory = share!!.folderExists(sourceSmb)

                val file = share!!.openForRename(sourceSmb, isDirectory)
                try {
                    file.rename(destSmb)
                } finally {
                    file.close()
                }
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val file = share!!.openFile(
                    toSmbPath(path),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                val output = ByteArrayOutputStream()
                try {
                    file.inputStream.use { it.copyTo(output) }
                } finally {
                    file.close()
                }
                ByteArrayInputStream(output.toByteArray()) as InputStream
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val diskShare = share!!
                object : ByteArrayOutputStream() {
                    private var closed = false

                    override fun close() {
                        if (closed) return
                        closed = true
                        super.close()
                        val file = diskShare.openFile(
                            toSmbPath(path),
                            EnumSet.of(AccessMask.GENERIC_WRITE),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            null,
                        )
                        try {
                            file.outputStream.use { it.write(toByteArray()) }
                        } finally {
                            file.close()
                        }
                    }
                } as OutputStream
            }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                val smbPath = toSmbPath(path)
                share!!.fileExists(smbPath) || share!!.folderExists(smbPath)
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val smbPath = toSmbPath(path)
                val info = share!!.getFileInformation(smbPath)
                val basicInfo = info.basicInformation
                val standardInfo = info.standardInformation
                FileItem(
                    name = path.substringAfterLast("/"),
                    path = path,
                    isDirectory = standardInfo.isDirectory,
                    size = standardInfo.endOfFile,
                    lastModified = Date(basicInfo.lastWriteTime.toEpochMillis()),
                    source = FileSource.SMB,
                )
            }
        }

    override fun getParentPath(path: String): String? {
        if (path == "/" || path.isEmpty()) return null
        val parent = path.substringBeforeLast("/")
        return parent.ifEmpty { "/" }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            runCatching {
                share?.close()
                session?.close()
                smbConnection?.close()
                smbClient?.close()
            }
            share = null
            session = null
            smbConnection = null
            smbClient = null
        }
    }
}
