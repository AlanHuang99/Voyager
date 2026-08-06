package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

class FileOperationCoordinatorTest {

    @Test
    fun uploadStreamsSelectedDocumentOffTheCallingThread() = runBlocking {
        val callerThread = Thread.currentThread().name
        val openThread = AtomicReference<String?>(null)
        val writeThread = AtomicReference<String?>(null)
        val destination = ThreadRecordingProvider(writeThread).apply { putDirectory("/remote") }
        val source = UploadSource("report.txt") {
            openThread.set(Thread.currentThread().name)
            ByteArrayInputStream("report".toByteArray())
        }

        FileOperationCoordinator.uploadFile(source, destination, "/remote").getOrThrow()

        assertEquals("report", destination.readFile("/remote/report.txt"))
        assertNotEquals(callerThread, openThread.get())
        assertNotEquals(callerThread, writeThread.get())
    }

    @Test
    fun uploadRefusesExistingFileWithoutOpeningOrOverwritingIt() = runBlocking {
        var sourceOpened = false
        val destination = MemoryProvider().apply {
            putDirectory("/remote")
            putFile("/remote/report.txt", "existing")
        }
        val source = UploadSource("report.txt") {
            sourceOpened = true
            ByteArrayInputStream("replacement".toByteArray())
        }

        val result = FileOperationCoordinator.uploadFile(source, destination, "/remote")

        assertTrue(result.exceptionOrNull() is DestinationConflictException)
        assertFalse(sourceOpened)
        assertEquals("existing", destination.readFile("/remote/report.txt"))
    }

    @Test
    fun failedUploadRemovesPartialTarget() = runBlocking {
        val destination = FailingWriteProvider().apply { putDirectory("/remote") }
        val source = UploadSource("report.txt") {
            ByteArrayInputStream("report".toByteArray())
        }

        val result = FileOperationCoordinator.uploadFile(source, destination, "/remote")

        assertTrue(result.isFailure)
        assertFalse(destination.exists("/remote/report.txt"))
    }

    @Test
    fun copyUsesOpaqueProviderCreatedFileIdentifier() = runBlocking {
        val source = MemoryProvider().apply { putFile("/local/report.txt", "report") }
        val destination = OpaquePathProvider()

        FileOperationCoordinator.copyPath(
            sourceProvider = source,
            destinationProvider = destination,
            sourcePath = "/local/report.txt",
            destinationDirectoryPath = destination.rootPath,
        ).getOrThrow()

        val createdPath = destination.createdPath(destination.rootPath, "report.txt")
        assertEquals("report", destination.readFile(createdPath))
    }

    @Test
    fun recursiveCopyUsesOpaqueProviderCreatedDirectoryIdentifiers() = runBlocking {
        val source = MemoryProvider().apply {
            putDirectory("/local/folder")
            putDirectory("/local/folder/nested")
            putFile("/local/folder/nested/report.txt", "report")
        }
        val destination = OpaquePathProvider()

        FileOperationCoordinator.copyPath(
            sourceProvider = source,
            destinationProvider = destination,
            sourcePath = "/local/folder",
            destinationDirectoryPath = destination.rootPath,
        ).getOrThrow()

        val folderPath = destination.createdPath(destination.rootPath, "folder")
        val nestedPath = destination.createdPath(folderPath, "nested")
        val reportPath = destination.createdPath(nestedPath, "report.txt")
        assertEquals("report", destination.readFile(reportPath))
    }

    @Test
    fun uploadUsesOpaqueProviderCreatedFileIdentifier() = runBlocking {
        val destination = OpaquePathProvider()
        val source = UploadSource("report.txt") {
            ByteArrayInputStream("report".toByteArray())
        }

        FileOperationCoordinator.uploadFile(
            source = source,
            destinationProvider = destination,
            destinationDirectoryPath = destination.rootPath,
        ).getOrThrow()

        val createdPath = destination.createdPath(destination.rootPath, "report.txt")
        assertEquals("report", destination.readFile(createdPath))
    }

    @Test
    fun failedOpaqueCopyCleansUpCreatedIdentifierAndKeepsSource() = runBlocking {
        val source = MemoryProvider().apply { putFile("/local/report.txt", "report") }
        val destination = OpaquePathProvider(failWrites = true)

        val result = FileOperationCoordinator.copyPath(
            sourceProvider = source,
            destinationProvider = destination,
            sourcePath = "/local/report.txt",
            destinationDirectoryPath = destination.rootPath,
        )

        assertTrue(result.isFailure)
        assertTrue(source.exists("/local/report.txt"))
        assertFalse(destination.exists(destination.lastCreatedPath!!))
        assertTrue(destination.listFiles(destination.rootPath).getOrThrow().isEmpty())
    }

    @Test
    fun copyStreamsFileBetweenDifferentProviders() = runBlocking {
        val source = MemoryProvider().apply { putFile("/local/report.txt", "report") }
        val destination = MemoryProvider().apply { putDirectory("/remote") }

        FileOperationCoordinator.copyPath(source, destination, "/local/report.txt", "/remote").getOrThrow()

        assertEquals("report", destination.readFile("/remote/report.txt"))
    }

    @Test
    fun moveBetweenDifferentProvidersCopiesThenDeletesSource() = runBlocking {
        val source = MemoryProvider().apply { putFile("/local/report.txt", "report") }
        val destination = MemoryProvider().apply { putDirectory("/remote") }

        FileOperationCoordinator.movePath(source, destination, "/local/report.txt", "/remote").getOrThrow()

        assertEquals("report", destination.readFile("/remote/report.txt"))
        assertFalse(source.exists("/local/report.txt"))
    }

    @Test
    fun copyStreamsDirectoryBetweenDifferentProviders() = runBlocking {
        val source = MemoryProvider().apply {
            putDirectory("/local/folder")
            putDirectory("/local/folder/nested")
            putFile("/local/folder/nested/file.txt", "nested")
        }
        val destination = MemoryProvider().apply { putDirectory("/remote") }

        FileOperationCoordinator.copyPath(source, destination, "/local/folder", "/remote").getOrThrow()

        assertEquals("nested", destination.readFile("/remote/folder/nested/file.txt"))
    }

    @Test
    fun copyRunsStreamIoOffTheCallingThread() = runBlocking {
        // In production paste() runs on viewModelScope.launch (Dispatchers.Main). If the coordinator streams on the caller's thread, the real SFTP/WebDAV socket writes run on the Android main thread and throw NetworkOnMainThreadException, leaving a 0-byte file (GitHub issues #1 and #7). The coordinator must move stream I/O to Dispatchers.IO.
        val callerThread = Thread.currentThread().name
        val writeThread = AtomicReference<String?>(null)
        val source = MemoryProvider().apply { putFile("/local/report.txt", "report") }
        val destination = ThreadRecordingProvider(writeThread).apply { putDirectory("/remote") }

        FileOperationCoordinator.copyPath(source, destination, "/local/report.txt", "/remote").getOrThrow()

        val actual = writeThread.get()
        assertNotNull("destination stream was never written", actual)
        assertNotEquals(
            "Cross-provider stream I/O ran on the caller thread; on Dispatchers.Main this is a NetworkOnMainThreadException",
            callerThread,
            actual,
        )
    }

    @Test
    fun copyRefusesExistingFileWithoutOverwritingIt() = runBlocking {
        val source = MemoryProvider().apply { putFile("/local/report.txt", "new") }
        val destination = MemoryProvider().apply {
            putDirectory("/remote")
            putFile("/remote/report.txt", "existing")
        }

        val result = FileOperationCoordinator.copyPath(source, destination, "/local/report.txt", "/remote")

        assertTrue(result.exceptionOrNull() is DestinationConflictException)
        assertEquals("existing", destination.readFile("/remote/report.txt"))
        assertTrue(source.exists("/local/report.txt"))
    }

    @Test
    fun copyRefusesExistingDirectoryWithoutMergingTrees() = runBlocking {
        val source = MemoryProvider().apply {
            putDirectory("/local/folder")
            putFile("/local/folder/new.txt", "new")
        }
        val destination = MemoryProvider().apply {
            putDirectory("/remote")
            putDirectory("/remote/folder")
            putFile("/remote/folder/existing.txt", "existing")
        }

        val result = FileOperationCoordinator.copyPath(source, destination, "/local/folder", "/remote")

        assertTrue(result.exceptionOrNull() is DestinationConflictException)
        assertFalse(destination.exists("/remote/folder/new.txt"))
        assertEquals("existing", destination.readFile("/remote/folder/existing.txt"))
    }

    @Test
    fun failedMoveRemovesPartialTargetAndKeepsSource() = runBlocking {
        val source = MemoryProvider().apply { putFile("/local/report.txt", "report") }
        val destination = FailingWriteProvider().apply { putDirectory("/remote") }

        val result = FileOperationCoordinator.movePath(source, destination, "/local/report.txt", "/remote")

        assertTrue(result.isFailure)
        assertFalse(destination.exists("/remote/report.txt"))
        assertTrue(source.exists("/local/report.txt"))
    }

    @Test
    fun copyReportsMonotonicBytesAfterSuccessfulWrites() = runBlocking {
        val payload = ByteArray(2 * 64 * 1024 + 17) { index -> (index % 251).toByte() }
        val source = MemoryProvider().apply { putFile("/local/large.bin", payload) }
        val destination = MemoryProvider().apply { putDirectory("/remote") }
        val events = mutableListOf<StreamCopyProgress>()

        FileOperationCoordinator.copyPath(
            sourceProvider = source,
            destinationProvider = destination,
            sourcePath = "/local/large.bin",
            destinationDirectoryPath = "/remote",
            onProgress = events::add,
        ).getOrThrow()

        assertTrue(events.size >= 3)
        assertTrue(events.zipWithNext().all { (first, second) -> first.bytesCopied < second.bytesCopied })
        assertTrue(events.all { it.path == "/local/large.bin" })
        assertTrue(events.all { it.totalBytes == payload.size.toLong() })
        assertEquals(payload.size.toLong(), events.last().bytesCopied)
        assertEquals(payload.toList(), destination.readFileBytes("/remote/large.bin").toList())
    }

    @Test
    fun recursiveCopyReportsEachActiveFile() = runBlocking {
        val source = MemoryProvider().apply {
            putDirectory("/local/folder")
            putFile("/local/folder/first.txt", "first")
            putDirectory("/local/folder/nested")
            putFile("/local/folder/nested/second.txt", "second")
        }
        val destination = MemoryProvider().apply { putDirectory("/remote") }
        val events = mutableListOf<StreamCopyProgress>()

        FileOperationCoordinator.copyPath(
            sourceProvider = source,
            destinationProvider = destination,
            sourcePath = "/local/folder",
            destinationDirectoryPath = "/remote",
            onProgress = events::add,
        ).getOrThrow()

        assertEquals(
            setOf("/local/folder/first.txt", "/local/folder/nested/second.txt"),
            events.mapTo(mutableSetOf()) { it.path },
        )
    }

    @Test
    fun failedWriteDoesNotReportUnwrittenBytes() = runBlocking {
        val source = MemoryProvider().apply { putFile("/local/report.txt", "report") }
        val destination = FailingWriteProvider().apply { putDirectory("/remote") }
        val events = mutableListOf<StreamCopyProgress>()

        val result = FileOperationCoordinator.copyPath(
            sourceProvider = source,
            destinationProvider = destination,
            sourcePath = "/local/report.txt",
            destinationDirectoryPath = "/remote",
            onProgress = events::add,
        )

        assertTrue(result.isFailure)
        assertTrue(events.isEmpty())
    }

    @Test
    fun zeroByteFileReportsKnownZeroTotal() = runBlocking {
        val source = MemoryProvider().apply { putFile("/local/empty.txt", byteArrayOf()) }
        val destination = MemoryProvider().apply { putDirectory("/remote") }
        val events = mutableListOf<StreamCopyProgress>()

        FileOperationCoordinator.copyPath(
            sourceProvider = source,
            destinationProvider = destination,
            sourcePath = "/local/empty.txt",
            destinationDirectoryPath = "/remote",
            onProgress = events::add,
        ).getOrThrow()

        assertEquals(
            listOf(
                StreamCopyProgress(
                    path = "/local/empty.txt",
                    bytesCopied = 0,
                    totalBytes = 0,
                )
            ),
            events,
        )
    }

    @Test
    fun negativeProviderSizeIsReportedAsUnknownRatherThanZero() = runBlocking {
        val source = UnknownSizeProvider().apply { putFile("/local/unknown.bin", "contents") }
        val destination = MemoryProvider().apply { putDirectory("/remote") }
        val events = mutableListOf<StreamCopyProgress>()

        FileOperationCoordinator.copyPath(
            sourceProvider = source,
            destinationProvider = destination,
            sourcePath = "/local/unknown.bin",
            destinationDirectoryPath = "/remote",
            onProgress = events::add,
        ).getOrThrow()

        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.totalBytes == null })
    }

    private class FailingWriteProvider : MemoryProvider() {
        override suspend fun getOutputStream(path: String): Result<OutputStream> =
            Result.success(
                object : OutputStream() {
                    override fun write(b: Int) {
                        putFile(path, "partial")
                        throw IOException("simulated write failure")
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        putFile(path, "partial")
                        throw IOException("simulated write failure")
                    }
                },
            )
    }

    private class ThreadRecordingProvider(
        private val writeThread: AtomicReference<String?>,
    ) : MemoryProvider() {
        override suspend fun getOutputStream(path: String): Result<OutputStream> {
            val delegate = super.getOutputStream(path).getOrThrow()
            return Result.success(object : OutputStream() {
                override fun write(b: Int) {
                    writeThread.compareAndSet(null, Thread.currentThread().name)
                    delegate.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    writeThread.compareAndSet(null, Thread.currentThread().name)
                    delegate.write(b, off, len)
                }

                override fun close() = delegate.close()
            })
        }
    }

    private class UnknownSizeProvider : MemoryProvider() {
        override suspend fun getFileInfo(path: String): Result<FileItem> =
            super.getFileInfo(path).map { it.copy(size = -1) }
    }

    private class OpaquePathProvider(
        private val failWrites: Boolean = false,
    ) : MemoryProvider() {
        val rootPath = "content://tree/root"
        var lastCreatedPath: String? = null
            private set

        private var nextId = 1
        private val children = mutableMapOf<String, MutableList<FileItem>>()
        private val parentByPath = mutableMapOf<String, String>()
        private val directoryPaths = mutableSetOf(rootPath)

        init {
            putDirectory(rootPath)
        }

        fun createdPath(parentPath: String, name: String): String =
            children.getValue(parentPath).single { it.name == name }.path

        override suspend fun listFiles(path: String): Result<List<FileItem>> =
            Result.success(children[path].orEmpty().toList())

        override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
            createEntry(path, name, isDirectory = true)

        override suspend fun createFile(path: String, name: String): Result<FileItem> =
            createEntry(path, name, isDirectory = false)

        override suspend fun getOutputStream(path: String): Result<OutputStream> {
            if (path !in parentByPath) {
                return Result.failure(IOException("Output path was not returned by createFile: $path"))
            }
            if (!failWrites) return super.getOutputStream(path)
            return Result.success(
                object : OutputStream() {
                    override fun write(b: Int) {
                        putFile(path, "partial")
                        throw IOException("simulated opaque write failure")
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        putFile(path, "partial")
                        throw IOException("simulated opaque write failure")
                    }
                },
            )
        }

        override suspend fun delete(path: String): Result<Unit> = runCatching {
            val descendants = mutableListOf<String>()
            fun collect(parent: String) {
                children[parent].orEmpty().forEach { child ->
                    collect(child.path)
                    descendants += child.path
                }
            }
            collect(path)
            (descendants + path).forEach { target ->
                super.delete(target).getOrThrow()
                val parent = parentByPath.remove(target)
                if (parent != null) children[parent]?.removeAll { it.path == target }
                children.remove(target)
                directoryPaths.remove(target)
            }
        }

        private fun createEntry(
            parentPath: String,
            name: String,
            isDirectory: Boolean,
        ): Result<FileItem> = runCatching {
            require(parentPath in directoryPaths) {
                "Parent path was not returned by createDirectory: $parentPath"
            }
            val createdPath = "$rootPath/document/id-${nextId++}"
            val item = FileItem(
                name = name,
                path = createdPath,
                isDirectory = isDirectory,
                source = FileSource.SAF,
            )
            if (isDirectory) {
                putDirectory(createdPath)
                directoryPaths += createdPath
            } else {
                putFile(createdPath, byteArrayOf())
            }
            parentByPath[createdPath] = parentPath
            children.getOrPut(parentPath) { mutableListOf() } += item
            lastCreatedPath = createdPath
            item
        }
    }

    private open class MemoryProvider : FileProvider {
        private val entries = mutableMapOf<String, Entry>("/" to Entry.Directory)

        fun putDirectory(path: String) {
            entries[path.normalized()] = Entry.Directory
        }

        fun putFile(path: String, contents: String) {
            putFile(path, contents.toByteArray())
        }

        fun putFile(path: String, contents: ByteArray) {
            entries[path.normalized()] = Entry.File(contents)
        }

        fun readFile(path: String): String =
            String(readFileBytes(path))

        fun readFileBytes(path: String): ByteArray =
            (entries.getValue(path.normalized()) as Entry.File).contents

        override suspend fun listFiles(path: String): Result<List<FileItem>> = runCatching {
            val parent = path.normalized()
            entries.keys
                .filter { it != parent && getParentPath(it) == parent }
                .map { childPath ->
                    val name = childPath.substringAfterLast("/")
                    FileItem(
                        name = name,
                        path = childPath,
                        isDirectory = entries.getValue(childPath) is Entry.Directory,
                        size = (entries.getValue(childPath) as? Entry.File)?.contents?.size?.toLong() ?: 0,
                        source = FileSource.LOCAL,
                    )
                }
        }

        override suspend fun createDirectory(path: String, name: String): Result<FileItem> = runCatching {
            val fullPath = joinPath(path, name)
            putDirectory(fullPath)
            FileItem(name = name, path = fullPath, isDirectory = true, source = FileSource.LOCAL)
        }

        override suspend fun createFile(path: String, name: String): Result<FileItem> = runCatching {
            val fullPath = joinPath(path, name)
            putFile(fullPath, "")
            FileItem(name = name, path = fullPath, isDirectory = false, source = FileSource.LOCAL)
        }

        override suspend fun delete(path: String): Result<Unit> = runCatching {
            val normalizedPath = path.normalized()
            entries.keys
                .filter { it == normalizedPath || it.startsWith("$normalizedPath/") }
                .toList()
                .forEach { entries.remove(it) }
        }

        override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
            error("Not needed by tests")

        override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
            error("Cross-provider tests must use streams, not provider-local copy")

        override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
            error("Cross-provider tests must use streams, not provider-local move")

        override suspend fun getInputStream(path: String): Result<InputStream> = runCatching {
            ByteArrayInputStream((entries.getValue(path.normalized()) as Entry.File).contents)
        }

        override suspend fun getOutputStream(path: String): Result<OutputStream> = runCatching {
            val normalizedPath = path.normalized()
            object : ByteArrayOutputStream() {
                override fun close() {
                    entries[normalizedPath] = Entry.File(toByteArray())
                    super.close()
                }
            }
        }

        override suspend fun exists(path: String): Boolean = entries.containsKey(path.normalized())

        open override suspend fun getFileInfo(path: String): Result<FileItem> = runCatching {
            val normalizedPath = path.normalized()
            FileItem(
                name = normalizedPath.substringAfterLast("/").ifEmpty { "/" },
                path = normalizedPath,
                isDirectory = entries.getValue(normalizedPath) is Entry.Directory,
                size = (entries.getValue(normalizedPath) as? Entry.File)?.contents?.size?.toLong() ?: 0,
                source = FileSource.LOCAL,
            )
        }

        override fun getParentPath(path: String): String? {
            val normalizedPath = path.normalized()
            if (normalizedPath == "/") return null
            return normalizedPath.substringBeforeLast("/").ifEmpty { "/" }
        }

        private fun joinPath(path: String, name: String): String =
            if (path == "/") "/$name" else "${path.normalized()}/$name"

        private fun String.normalized(): String =
            if (this == "/") "/" else trimEnd('/')
    }

    private sealed interface Entry {
        data object Directory : Entry
        data class File(val contents: ByteArray) : Entry
    }
}
