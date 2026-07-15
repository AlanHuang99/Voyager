package com.voyagerfiles.data.remote.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.Properties

class SftpFileProvider(
    private val connection: RemoteConnection,
    private val knownHostsFile: File,
) : FileProvider {

    private val connectionLock = Mutex()
    private var session: Session? = null

    private fun ensureSessionLocked(): Session {
        session?.takeIf { it.isConnected }?.let { return it }

        closeConnection()

        val jsch = JSch()
        prepareKnownHostsFile()
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        connection.privateKeyPath?.takeIf { it.isNotBlank() }?.let { privateKeyPath ->
            jsch.addIdentity(privateKeyPath)
        }
        val nextSession = jsch.getSession(
            connection.username,
            connection.host,
            connection.port,
        )

        try {
            if (connection.password.isNotEmpty()) {
                nextSession.setPassword(connection.password.toByteArray())
            }
            nextSession.userInfo = PasswordUserInfo(connection.password)
            nextSession.setConfig(
                Properties().apply {
                    put("StrictHostKeyChecking", "ask")
                    put("PreferredAuthentications", "publickey,password,keyboard-interactive")
                }
            )
            nextSession.setTimeout(CONNECTION_TIMEOUT_MILLIS)
            nextSession.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MILLIS)
            nextSession.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
            nextSession.connect(CONNECTION_TIMEOUT_MILLIS)
            session = nextSession
            return nextSession
        } catch (error: JSchChangedHostKeyException) {
            runCatching { nextSession.disconnect() }
            throw IllegalStateException(
                "SFTP host key changed. Verify the server before reconnecting.",
                error,
            )
        } catch (error: Throwable) {
            runCatching { nextSession.disconnect() }
            throw error
        }
    }

    private fun prepareKnownHostsFile() {
        val parent = knownHostsFile.parentFile
        check(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Could not create the SFTP security directory"
        }
        check(knownHostsFile.isFile || knownHostsFile.createNewFile()) {
            "Could not create the SFTP known-hosts file"
        }
    }

    private fun openSftpChannelLocked(): ChannelSftp {
        val channel = ensureSessionLocked().openChannel("sftp") as ChannelSftp
        try {
            channel.connect(CONNECTION_TIMEOUT_MILLIS)
            return channel
        } catch (error: Throwable) {
            runCatching { channel.disconnect() }
            throw error
        }
    }

    private suspend fun <T> withSftpChannel(block: (ChannelSftp) -> T): T =
        connectionLock.withLock {
            val channel = openSftpChannelLocked()
            try {
                block(channel)
            } finally {
                channel.disconnect()
            }
        }

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    listEntries(sftp, path)
                        .filterNot { it.filename == "." || it.filename == ".." }
                        .map { entry ->
                            toFileItem(
                                name = entry.filename,
                                path = joinPath(path, entry.filename),
                                attrs = entry.attrs,
                            )
                        }
                }
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    val fullPath = joinPath(path, name)
                    sftp.mkdir(fullPath)
                    FileItem(
                        name = name,
                        path = fullPath,
                        isDirectory = true,
                        source = FileSource.SFTP,
                    )
                }
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    val fullPath = joinPath(path, name)
                    sftp.put(fullPath, ChannelSftp.OVERWRITE).use { }
                    FileItem(
                        name = name,
                        path = fullPath,
                        isDirectory = false,
                        source = FileSource.SFTP,
                    )
                }
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    val attrs = sftp.lstat(path)
                    if (attrs.isDir) {
                        deleteDirectoryRecursive(sftp, path)
                    } else {
                        sftp.rm(path)
                    }
                }
            }
        }

    private fun deleteDirectoryRecursive(sftp: ChannelSftp, path: String) {
        for (entry in listEntries(sftp, path)) {
            if (entry.filename == "." || entry.filename == "..") continue
            val entryPath = joinPath(path, entry.filename)
            if (entry.attrs.isDir) {
                deleteDirectoryRecursive(sftp, entryPath)
            } else {
                sftp.rm(entryPath)
            }
        }
        sftp.rmdir(path)
    }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    val parent = getParentPath(oldPath) ?: "/"
                    val newPath = joinPath(parent, newName)
                    sftp.rename(oldPath, newPath)
                    toFileItem(
                        name = newName,
                        path = newPath,
                        attrs = sftp.lstat(newPath),
                    )
                }
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    val name = sourcePath.substringAfterLast("/")
                    copyPath(sftp, sourcePath, joinPath(destPath, name))
                }
            }
        }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    val name = sourcePath.substringAfterLast("/")
                    sftp.rename(sourcePath, joinPath(destPath, name))
                }
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val channel = connectionLock.withLock { openSftpChannelLocked() }
                try {
                    SftpChannelInputStream(channel, channel.get(path)) as InputStream
                } catch (error: Throwable) {
                    runCatching { channel.disconnect() }
                    throw error
                }
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val channel = connectionLock.withLock { openSftpChannelLocked() }
                try {
                    SftpChannelOutputStream(
                        channel = channel,
                        output = channel.put(path, ChannelSftp.OVERWRITE),
                    ) as OutputStream
                } catch (error: Throwable) {
                    runCatching { channel.disconnect() }
                    throw error
                }
            }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                withSftpChannel { it.lstat(path) }
                true
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                withSftpChannel { sftp ->
                    toFileItem(
                        name = path.removeSuffix("/").substringAfterLast("/").ifEmpty { "/" },
                        path = path,
                        attrs = sftp.lstat(path),
                    )
                }
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

    private fun copyPath(sftp: ChannelSftp, sourcePath: String, targetPath: String) {
        val attrs = sftp.lstat(sourcePath)
        if (attrs.isDir) {
            sftp.mkdir(targetPath)
            for (entry in listEntries(sftp, sourcePath)) {
                if (entry.filename == "." || entry.filename == "..") continue
                copyPath(
                    sftp,
                    joinPath(sourcePath, entry.filename),
                    joinPath(targetPath, entry.filename),
                )
            }
            return
        }

        val outputChannel = openSftpChannelLocked()
        try {
            sftp.get(sourcePath).use { input ->
                outputChannel.put(targetPath, ChannelSftp.OVERWRITE).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
        } finally {
            outputChannel.disconnect()
        }
    }

    private fun closeConnection() {
        runCatching { session?.disconnect() }
        session = null
    }

    private fun listEntries(sftp: ChannelSftp, path: String): List<ChannelSftp.LsEntry> =
        sftp.ls(path).filterIsInstance<ChannelSftp.LsEntry>()

    private fun toFileItem(
        name: String,
        path: String,
        attrs: com.jcraft.jsch.SftpATTRS,
    ): FileItem =
        FileItem(
            name = name,
            path = path,
            isDirectory = attrs.isDir,
            size = attrs.size,
            lastModified = Date(attrs.mTime.toLong() * 1000L),
            isHidden = name.startsWith("."),
            permissions = attrs.permissionsString,
            owner = attrs.uId.toString(),
            source = FileSource.SFTP,
        )

    private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassword(): String? = password

        override fun promptPassword(message: String?): Boolean = password.isNotEmpty()

        override fun getPassphrase(): String? = null

        override fun promptPassphrase(message: String?): Boolean = false

        override fun promptYesNo(message: String?): Boolean =
            message?.startsWith("The authenticity of host '") == true

        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String> = Array(prompt?.size ?: 0) { password }
    }

    private class SftpChannelInputStream(
        private val channel: ChannelSftp,
        input: InputStream,
    ) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                channel.disconnect()
            }
        }
    }

    private class SftpChannelOutputStream(
        private val channel: ChannelSftp,
        output: OutputStream,
    ) : FilterOutputStream(output) {
        override fun close() {
            try {
                super.close()
            } finally {
                channel.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 30_000
        const val SERVER_ALIVE_INTERVAL_MILLIS = 15_000
        const val SERVER_ALIVE_COUNT_MAX = 2
        const val BUFFER_SIZE = 32 * 1024

        fun joinPath(parent: String, child: String): String = when {
            parent.isBlank() || parent == "/" -> "/$child"
            parent.endsWith("/") -> "$parent$child"
            else -> "$parent/$child"
        }
    }
}
