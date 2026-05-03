package com.voyagerfiles.data.remote.sftp

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.auth.password.PasswordIdentityProvider
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.concurrent.TimeUnit

class SftpFileProvider(private val connection: RemoteConnection) : FileProvider {

    private val connectionLock = Mutex()
    private var sshClient: SshClient? = null
    private var session: ClientSession? = null
    private var sftpClient: SftpClient? = null

    private suspend fun ensureConnected(): SftpClient = connectionLock.withLock {
        val existing = sftpClient
        if (existing != null && session?.isOpen == true) return@withLock existing

        closeConnection()

        var client: SshClient? = null
        var sess: ClientSession? = null
        var sftp: SftpClient? = null

        try {
            client = SshClient.setUpDefaultClient().apply {
                userAuthFactories = listOf(
                    UserAuthPasswordFactory.INSTANCE,
                    UserAuthKeyboardInteractiveFactory.INSTANCE,
                )
                keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER

                if (connection.password.isNotEmpty()) {
                    passwordIdentityProvider = PasswordIdentityProvider.wrapPasswords(connection.password)
                    userInteraction = PasswordUserInteraction(connection.password)
                }
            }
            client.start()

            sess = client.connect(
                connection.username,
                connection.host,
                connection.port,
            ).verify(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).session

            if (connection.password.isNotEmpty()) {
                sess.addPasswordIdentity(connection.password)
            }

            sess.auth().verify(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sftp = SftpClientFactory.instance().createSftpClient(sess)

            sshClient = client
            session = sess
            sftpClient = sftp
            return@withLock sftp
        } catch (error: Throwable) {
            runCatching { sftp?.close() }
            runCatching { sess?.close() }
            runCatching { client?.stop() }
            throw error
        }
    }

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
                val handle = sftp.openDir(path)
                try {
                    val entries = mutableListOf<FileItem>()

                    for (dirEntry in sftp.listDir(handle)) {
                        val name = dirEntry.filename
                        if (name == "." || name == "..") continue
                        val attrs = dirEntry.attributes
                        entries.add(
                            FileItem(
                                name = name,
                                path = joinPath(path, name),
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
                    entries
                } finally {
                    sftp.close(handle)
                }
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
                val fullPath = joinPath(path, name)
                sftp.mkdir(fullPath)
                FileItem(
                    name = name, path = fullPath, isDirectory = true,
                    source = FileSource.SFTP,
                )
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
                val fullPath = joinPath(path, name)
                val handle = sftp.open(
                    fullPath,
                    setOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create),
                )
                sftp.close(handle)
                FileItem(
                    name = name, path = fullPath, isDirectory = false,
                    source = FileSource.SFTP,
                )
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
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
        try {
            for (entry in sftp.listDir(handle)) {
                if (entry.filename == "." || entry.filename == "..") continue
                val entryPath = joinPath(path, entry.filename)
                if (entry.attributes.isDirectory) {
                    deleteDirectoryRecursive(sftp, entryPath)
                } else {
                    sftp.remove(entryPath)
                }
            }
        } finally {
            sftp.close(handle)
        }
        sftp.rmdir(path)
    }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
                val parent = getParentPath(oldPath) ?: "/"
                val newPath = joinPath(parent, newName)
                sftp.rename(oldPath, newPath)
                val attrs = sftp.stat(newPath)
                FileItem(
                    name = newName, path = newPath,
                    isDirectory = attrs.isDirectory,
                    size = attrs.size,
                    lastModified = Date(attrs.modifyTime.toMillis()),
                    source = FileSource.SFTP,
                )
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                copyPath(sftp, sourcePath, joinPath(destPath, name))
            }
        }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                sftp.rename(sourcePath, joinPath(destPath, name))
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
                val handle = sftp.open(path, setOf(SftpClient.OpenMode.Read))
                SftpInputStream(sftp, handle) as InputStream
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = ensureConnected()
                val handle = sftp.open(
                    path,
                    setOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate),
                )
                SftpOutputStream(sftp, handle) as OutputStream
            }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureConnected().stat(path)
                true
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val attrs = ensureConnected().stat(path)
                FileItem(
                    name = path.removeSuffix("/").substringAfterLast("/").ifEmpty { "/" },
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
            connectionLock.withLock {
                closeConnection()
            }
        }
    }

    private fun copyPath(sftp: SftpClient, sourcePath: String, targetPath: String) {
        val attrs = sftp.stat(sourcePath)
        if (attrs.isDirectory) {
            sftp.mkdir(targetPath)
            val handle = sftp.openDir(sourcePath)
            try {
                for (entry in sftp.listDir(handle)) {
                    if (entry.filename == "." || entry.filename == "..") continue
                    copyPath(
                        sftp,
                        joinPath(sourcePath, entry.filename),
                        joinPath(targetPath, entry.filename),
                    )
                }
            } finally {
                sftp.close(handle)
            }
            return
        }

        val inputHandle = sftp.open(sourcePath, setOf(SftpClient.OpenMode.Read))
        try {
            val outputHandle = sftp.open(
                targetPath,
                setOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate),
            )
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                var readOffset = 0L
                var writeOffset = 0L
                while (true) {
                    val read = sftp.read(inputHandle, readOffset, buffer, 0, buffer.size)
                    if (read <= 0) break
                    sftp.write(outputHandle, writeOffset, buffer, 0, read)
                    readOffset += read
                    writeOffset += read
                }
            } finally {
                sftp.close(outputHandle)
            }
        } finally {
            sftp.close(inputHandle)
        }
    }

    private fun closeConnection() {
        runCatching { sftpClient?.close() }
        runCatching { session?.close() }
        runCatching { sshClient?.stop() }
        sftpClient = null
        session = null
        sshClient = null
    }

    private class PasswordUserInteraction(private val password: String) : UserInteraction {
        override fun isInteractionAllowed(session: ClientSession): Boolean = password.isNotEmpty()

        override fun interactive(
            session: ClientSession,
            name: String,
            instruction: String,
            lang: String,
            prompt: Array<String>,
            echo: BooleanArray,
        ): Array<String> = Array(prompt.size) { password }

        override fun getUpdatedPassword(
            session: ClientSession,
            prompt: String,
            lang: String,
        ): String = password
    }

    private class SftpInputStream(
        private val sftp: SftpClient,
        private val handle: SftpClient.Handle,
    ) : InputStream() {
        private val singleByte = ByteArray(1)
        private var offset = 0L
        private var closed = false

        override fun read(): Int {
            val read = read(singleByte, 0, 1)
            return if (read == -1) -1 else singleByte[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            check(!closed) { "Stream is closed" }
            if (len == 0) return 0
            val read = sftp.read(handle, offset, buffer, off, len)
            if (read <= 0) return -1
            offset += read
            return read
        }

        override fun close() {
            if (closed) return
            closed = true
            sftp.close(handle)
        }
    }

    private class SftpOutputStream(
        private val sftp: SftpClient,
        private val handle: SftpClient.Handle,
    ) : OutputStream() {
        private val singleByte = ByteArray(1)
        private var offset = 0L
        private var closed = false

        override fun write(value: Int) {
            singleByte[0] = value.toByte()
            write(singleByte, 0, 1)
        }

        override fun write(buffer: ByteArray, off: Int, len: Int) {
            check(!closed) { "Stream is closed" }
            if (len == 0) return
            sftp.write(handle, offset, buffer, off, len)
            offset += len
        }

        override fun close() {
            if (closed) return
            closed = true
            sftp.close(handle)
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 30L
        const val BUFFER_SIZE = 32 * 1024

        fun joinPath(parent: String, child: String): String = when {
            parent.isBlank() || parent == "/" -> "/$child"
            parent.endsWith("/") -> "$parent$child"
            else -> "$parent/$child"
        }
    }
}
