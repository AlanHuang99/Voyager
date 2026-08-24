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
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SmbVirtualRootException(
    message: String = "The SMB server and share roots are read-only browser entries",
) : IllegalStateException(message)

internal interface SmbSessionHandle {
    fun discover(discovery: SmbShareDiscovery): List<SmbDiscoveredShare>
    fun connectShare(name: String): DiskShare
    fun close()
}

internal fun interface SmbSessionHandleFactory {
    fun connect(connection: RemoteConnection): SmbSessionHandle
}

private object DefaultSmbSessionHandleFactory : SmbSessionHandleFactory {
    override fun connect(connection: RemoteConnection): SmbSessionHandle {
        var client: SMBClient? = null
        var smbConnection: Connection? = null
        var session: Session? = null
        try {
            client = SMBClient()
            smbConnection = client.connect(connection.host, connection.port)
            session = smbConnection.authenticate(
                AuthenticationContext(
                    connection.username,
                    connection.password.toCharArray(),
                    connection.domain ?: "",
                ),
            )
            return RealSmbSessionHandle(client, smbConnection, session)
        } catch (error: Throwable) {
            runCatching { session?.close() }
            runCatching { smbConnection?.close() }
            runCatching { client?.close() }
            throw error
        }
    }
}

private class RealSmbSessionHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
) : SmbSessionHandle {
    override fun discover(discovery: SmbShareDiscovery): List<SmbDiscoveredShare> = discovery.discover(session)

    override fun connectShare(name: String): DiskShare = session.connectShare(name) as DiskShare

    override fun close() {
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

private data class ResolvedSharePath(
    val shareName: String,
    val relativePath: String,
)

class SmbFileProvider internal constructor(
    private val connection: RemoteConnection,
    private val shareDiscovery: SmbShareDiscovery = DceRpcSmbShareDiscovery,
    private val sessionFactory: SmbSessionHandleFactory = DefaultSmbSessionHandleFactory,
) : FileProvider {
    private val configuredShare = connection.shareName?.trim().orEmpty()
    private val isDiscoveryMode = configuredShare.isEmpty()
    private var sessionHandle: SmbSessionHandle? = null
    private var activeShareName: String? = null
    private var share: DiskShare? = null
    private var discoveredShares: List<SmbDiscoveredShare>? = null
    private var virtualRootItemsCache: List<FileItem>? = null

    private fun ensureSession(): SmbSessionHandle = sessionHandle ?: sessionFactory.connect(connection).also {
        sessionHandle = it
    }

    private fun ensureShare(name: String): DiskShare {
        if (activeShareName == name && share != null) return checkNotNull(share)
        runCatching { share?.close() }
        share = null
        activeShareName = null
        return ensureSession().connectShare(name).also {
            share = it
            activeShareName = name
        }
    }

    private fun discoverShares(): List<SmbDiscoveredShare> = discoveredShares ?: ensureSession()
        .discover(shareDiscovery)
        .also { discoveredShares = it }

    private fun findDiscoveredShare(name: String): SmbDiscoveredShare? = discoverShares()
        .firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun resolve(path: String): ResolvedSharePath {
        if (!isDiscoveryMode) return ResolvedSharePath(configuredShare, toSmbPath(path))
        return when (val parsed = SmbBrowsePath.parse(path)) {
            SmbBrowsePath.VirtualRoot -> throw SmbVirtualRootException()
            is SmbBrowsePath.Share -> {
                val shareName = findDiscoveredShare(parsed.name)?.name
                    ?: throw IllegalArgumentException("SMB share is not available: ${parsed.name}")
                ResolvedSharePath(shareName, parsed.relativePath)
            }
        }
    }

    private fun resolveFile(path: String): ResolvedSharePath = resolve(path).also {
        if (isDiscoveryMode && it.relativePath.isEmpty()) throw SmbVirtualRootException()
    }

    private fun toSmbPath(path: String): String = path.removePrefix("/").replace("/", "\\")

    override suspend fun listFiles(path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            if (isDiscoveryMode && path == "/") return@runCatching virtualRootItems()
            val resolved = resolve(path)
            ensureShare(resolved.shareName).list(resolved.relativePath)
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info ->
                    val isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    FileItem(
                        name = info.fileName,
                        path = appendPath(path, info.fileName),
                        isDirectory = isDirectory,
                        size = info.endOfFile,
                        lastModified = Date(info.lastWriteTime.toEpochMillis()),
                        isHidden = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L,
                        source = FileSource.SMB,
                    )
                }
        }
    }

    private fun virtualRootItems(): List<FileItem> = virtualRootItemsCache ?: discoverShares()
        .map { discovered ->
            FileItem(
                name = discovered.name,
                path = "/${discovered.name}",
                isDirectory = true,
                size = 0,
                source = FileSource.SMB,
            )
        }
        .also { virtualRootItemsCache = it }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = resolve(path)
            val fullPath = appendPath(path, name)
            ensureShare(parent.shareName).mkdir(joinSmbPath(parent.relativePath, name))
            FileItem(name = name, path = fullPath, isDirectory = true, source = FileSource.SMB)
        }
    }

    override suspend fun createFile(path: String, name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = resolve(path)
            val fullPath = appendPath(path, name)
            ensureShare(parent.shareName).openFile(
                joinSmbPath(parent.relativePath, name),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                null,
            ).close()
            FileItem(name = name, path = fullPath, isDirectory = false, source = FileSource.SMB)
        }
    }

    override suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveFile(path)
            val diskShare = ensureShare(resolved.shareName)
            if (diskShare.folderExists(resolved.relativePath)) {
                deleteDirectoryRecursive(diskShare, resolved.relativePath)
            } else {
                diskShare.rm(resolved.relativePath)
            }
        }
    }

    private fun deleteDirectoryRecursive(diskShare: DiskShare, smbPath: String) {
        for (info in diskShare.list(smbPath)) {
            if (info.fileName == "." || info.fileName == "..") continue
            val entryPath = joinSmbPath(smbPath, info.fileName)
            if (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L) {
                deleteDirectoryRecursive(diskShare, entryPath)
            } else {
                diskShare.rm(entryPath)
            }
        }
        diskShare.rmdir(smbPath, false)
    }

    private fun DiskShare.openForRename(path: String, isDirectory: Boolean): DiskEntry = if (isDirectory) {
        openDirectory(
            path,
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    } else {
        openFile(
            path,
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveFile(oldPath)
            val diskShare = ensureShare(resolved.shareName)
            val parent = resolved.relativePath.substringBeforeLast("\\", "")
            val newSmb = joinSmbPath(parent, newName)
            val wasDirectory = diskShare.folderExists(resolved.relativePath)
            val entry = diskShare.openForRename(resolved.relativePath, wasDirectory)
            try {
                entry.rename(newSmb)
            } finally {
                entry.close()
            }
            val parentPath = oldPath.substringBeforeLast('/').ifEmpty { "/" }
            FileItem(
                name = newName,
                path = appendPath(parentPath, newName),
                isDirectory = wasDirectory,
                source = FileSource.SMB,
            )
        }
    }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val source = resolveFile(sourcePath)
            val destination = resolve(destPath)
            requireSameShare(source, destination, "copy")
            val name = sourcePath.substringAfterLast('/')
            copyPath(
                diskShare = ensureShare(source.shareName),
                sourceSmb = source.relativePath,
                destSmb = joinSmbPath(destination.relativePath, name),
            )
        }
    }

    private fun copyPath(diskShare: DiskShare, sourceSmb: String, destSmb: String) {
        if (diskShare.folderExists(sourceSmb)) {
            diskShare.mkdir(destSmb)
            for (info in diskShare.list(sourceSmb)) {
                if (info.fileName == "." || info.fileName == "..") continue
                copyPath(
                    diskShare,
                    joinSmbPath(sourceSmb, info.fileName),
                    joinSmbPath(destSmb, info.fileName),
                )
            }
            return
        }

        val sourceFile = diskShare.openFile(
            sourceSmb,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val destinationFile = diskShare.openFile(
            destSmb,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            null,
        )
        try {
            sourceFile.remoteCopyTo(destinationFile)
        } finally {
            runCatching { sourceFile.close() }
            destinationFile.close()
        }
    }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val source = resolveFile(sourcePath)
            val destination = resolve(destPath)
            requireSameShare(source, destination, "move")
            val diskShare = ensureShare(source.shareName)
            val name = sourcePath.substringAfterLast('/')
            val destinationSmb = joinSmbPath(destination.relativePath, name)
            val isDirectory = diskShare.folderExists(source.relativePath)
            val entry = diskShare.openForRename(source.relativePath, isDirectory)
            try {
                entry.rename(destinationSmb)
            } finally {
                entry.close()
            }
        }
    }

    private fun requireSameShare(source: ResolvedSharePath, destination: ResolvedSharePath, operation: String) {
        require(source.shareName.equals(destination.shareName, ignoreCase = true)) {
            "Cross-share SMB $operation is not supported"
        }
    }

    override suspend fun getInputStream(path: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveFile(path)
            val file = ensureShare(resolved.shareName).openFile(
                resolved.relativePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            try {
                object : FilterInputStream(file.inputStream) {
                    private var closed = false

                    override fun close() {
                        if (closed) return
                        closed = true
                        try {
                            super.close()
                        } finally {
                            file.close()
                        }
                    }
                } as InputStream
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }
    }

    override suspend fun getOutputStream(path: String): Result<OutputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveFile(path)
            val file = ensureShare(resolved.shareName).openFile(
                resolved.relativePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            )
            try {
                object : FilterOutputStream(file.outputStream) {
                    private var closed = false

                    override fun close() {
                        if (closed) return
                        closed = true
                        try {
                            super.close()
                        } finally {
                            file.close()
                        }
                    }
                } as OutputStream
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isDiscoveryMode && path == "/") return@withContext true
            if (isDiscoveryMode) {
                val parsed = SmbBrowsePath.parse(path)
                if (parsed is SmbBrowsePath.Share && parsed.relativePath.isEmpty()) {
                    return@withContext findDiscoveredShare(parsed.name) != null
                }
            }
            val resolved = resolve(path)
            val diskShare = ensureShare(resolved.shareName)
            diskShare.fileExists(resolved.relativePath) || diskShare.folderExists(resolved.relativePath)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun getFileInfo(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            virtualFileInfo(path)?.let { return@runCatching it }
            val resolved = resolve(path)
            val info = ensureShare(resolved.shareName).getFileInformation(resolved.relativePath)
            FileItem(
                name = path.substringAfterLast('/'),
                path = path,
                isDirectory = info.standardInformation.isDirectory,
                size = info.standardInformation.endOfFile,
                lastModified = Date(info.basicInformation.lastWriteTime.toEpochMillis()),
                source = FileSource.SMB,
            )
        }
    }

    private fun virtualFileInfo(path: String): FileItem? {
        if (!isDiscoveryMode) return null
        if (path == "/") {
            return FileItem(
                name = connection.host,
                path = "/",
                isDirectory = true,
                source = FileSource.SMB,
            )
        }
        val parsed = SmbBrowsePath.parse(path)
        if (parsed !is SmbBrowsePath.Share || parsed.relativePath.isNotEmpty()) return null
        val discovered = findDiscoveredShare(parsed.name)
            ?: throw IllegalArgumentException("SMB share is not available: ${parsed.name}")
        return FileItem(
            name = discovered.name,
            path = "/${discovered.name}",
            isDirectory = true,
            source = FileSource.SMB,
        )
    }

    override fun getParentPath(path: String): String? = if (isDiscoveryMode) {
        SmbBrowsePath.parentOf(path)
    } else {
        if (path == "/" || path.isEmpty()) null else path.substringBeforeLast('/').ifEmpty { "/" }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            val currentShare = share
            val currentSession = sessionHandle
            share = null
            activeShareName = null
            sessionHandle = null
            discoveredShares = null
            virtualRootItemsCache = null
            runCatching { currentShare?.close() }
            runCatching { currentSession?.close() }
        }
    }

    private fun appendPath(parent: String, name: String): String = if (parent == "/") {
        "/$name"
    } else {
        "${parent.trimEnd('/')}/$name"
    }

    private fun joinSmbPath(parent: String, name: String): String = if (parent.isEmpty()) {
        name
    } else {
        "$parent\\$name"
    }
}
