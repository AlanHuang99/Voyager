package com.voyagerfiles.data.remote.sftp

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.concurrent.TimeUnit

class SftpFileProvider(private val connection: RemoteConnection) : FileProvider {

    private var sshClient: SshClient? = null
    private var session: ClientSession? = null
    private var sftpClient: SftpClient? = null

    private suspend fun ensureConnected() {
        if (sftpClient != null) return
        withContext(Dispatchers.IO) {
            val client = SshClient.setUpDefaultClient()
            client.start()

            val sess = client.connect(
                connection.username,
                connection.host,
                connection.port,
            ).verify(30, TimeUnit.SECONDS).session

            if (connection.password.isNotEmpty()) {
                sess.addPasswordIdentity(connection.password)
            }

            sess.auth().verify(30, TimeUnit.SECONDS)

            val sftp = SftpClientFactory.instance().createSftpClient(sess)

            sshClient = client
            session = sess
            sftpClient = sftp
        }
    }

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val sftp = sftpClient!!
                val handle = sftp.openDir(path)
                val entries = mutableListOf<FileItem>()

                for (dirEntry in sftp.listDir(handle)) {
                    val name = dirEntry.filename
                    if (name == "." || name == "..") continue
                    val attrs = dirEntry.attributes
                    entries.add(
                        FileItem(
                            name = name,
                            path = if (path.endsWith("/")) "$path$name" else "$path/$name",
                            isDirectory = attrs.isDirectory,
                            size = attrs.size,
                            lastModified = Date(attrs.modifyTime.toMillis()),
                            isHidden = name.startsWith("."),
                            permissions = attrs.permissions?.toString(),
                            owner = attrs.owner,
                            source = FileSource.SFTP,
                        )
                    )
                }
                sftp.close(handle)
                entries
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                sftpClient!!.mkdir(fullPath)
                FileItem(
                    name = name, path = fullPath, isDirectory = true,
                    source = FileSource.SFTP,
                )
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                val handle = sftpClient!!.open(
                    fullPath,
                    setOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create),
                )
                sftpClient!!.close(handle)
                FileItem(
                    name = name, path = fullPath, isDirectory = false,
                    source = FileSource.SFTP,
                )
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val sftp = sftpClient!!
                val attrs = sftp.stat(path)
                if (attrs.isDirectory) {
                    deleteDirectoryRecursive(sftp, path)
                } else {
                    sftp.remove(path)
                }
            }
        }

    private fun deleteDirectoryRecursive(sftp: SftpClient, path: String) {
        val handle = sftp.openDir(path)
        for (entry in sftp.listDir(handle)) {
            if (entry.filename == "." || entry.filename == "..") continue
            val entryPath = "$path/${entry.filename}"
            if (entry.attributes.isDirectory) {
                deleteDirectoryRecursive(sftp, entryPath)
            } else {
                sftp.remove(entryPath)
            }
        }
        sftp.close(handle)
        sftp.rmdir(path)
    }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val parent = oldPath.substringBeforeLast("/")
                val newPath = "$parent/$newName"
                sftpClient!!.rename(oldPath, newPath)
                FileItem(
                    name = newName, path = newPath,
                    isDirectory = sftpClient!!.stat(newPath).isDirectory,
                    source = FileSource.SFTP,
                )
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val sftp = sftpClient!!
                val name = sourcePath.substringAfterLast("/")
                val targetPath = "$destPath/$name"
                // SFTP has no native copy; read and write
                val input = ByteArrayOutputStream()
                val handle = sftp.open(sourcePath, setOf(SftpClient.OpenMode.Read))
                val buffer = ByteArray(32768)
                var offset = 0L
                while (true) {
                    val read = sftp.read(handle, offset, buffer, 0, buffer.size)
                    if (read <= 0) break
                    input.write(buffer, 0, read)
                    offset += read
                }
                sftp.close(handle)

                val outHandle = sftp.open(
                    targetPath,
                    setOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create),
                )
                val data = input.toByteArray()
                sftp.write(outHandle, 0, data, 0, data.size)
                sftp.close(outHandle)
            }
        }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                sftpClient!!.rename(sourcePath, "$destPath/$name")
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val sftp = sftpClient!!
                val handle = sftp.open(path, setOf(SftpClient.OpenMode.Read))
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32768)
                var offset = 0L
                while (true) {
                    val read = sftp.read(handle, offset, buffer, 0, buffer.size)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    offset += read
                }
                sftp.close(handle)
                ByteArrayInputStream(output.toByteArray()) as InputStream
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Return a buffered output stream that writes on close
                object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        val sftp = sftpClient ?: return
                        val handle = sftp.open(
                            path,
                            setOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate),
                        )
                        val data = toByteArray()
                        sftp.write(handle, 0, data, 0, data.size)
                        sftp.close(handle)
                    }
                } as OutputStream
            }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                sftpClient!!.stat(path)
                true
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val attrs = sftpClient!!.stat(path)
                FileItem(
                    name = path.substringAfterLast("/"),
                    path = path,
                    isDirectory = attrs.isDirectory,
                    size = attrs.size,
                    lastModified = Date(attrs.modifyTime.toMillis()),
                    source = FileSource.SFTP,
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
                sftpClient?.close()
                session?.close()
                sshClient?.stop()
            }
            sftpClient = null
            session = null
            sshClient = null
        }
    }
}
