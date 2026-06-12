package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

class FileOperationCoordinatorTest {

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
        // In production paste() runs on viewModelScope.launch (Dispatchers.Main). If the
        // coordinator streams on the caller's thread, the real SFTP/WebDAV socket writes run
        // on the Android main thread and throw NetworkOnMainThreadException, leaving a 0-byte
        // file (GitHub issues #1 and #7). The coordinator must move stream I/O to Dispatchers.IO.
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

    private open class MemoryProvider : FileProvider {
        private val entries = mutableMapOf<String, Entry>("/" to Entry.Directory)

        fun putDirectory(path: String) {
            entries[path.normalized()] = Entry.Directory
        }

        fun putFile(path: String, contents: String) {
            entries[path.normalized()] = Entry.File(contents.toByteArray())
        }

        fun readFile(path: String): String =
            String((entries.getValue(path.normalized()) as Entry.File).contents)

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

        override suspend fun getFileInfo(path: String): Result<FileItem> = runCatching {
            val normalizedPath = path.normalized()
            FileItem(
                name = normalizedPath.substringAfterLast("/").ifEmpty { "/" },
                path = normalizedPath,
                isDirectory = entries.getValue(normalizedPath) is Entry.Directory,
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
