package com.voyagerfiles.data.archive

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.FileProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

internal open class ArchiveTestFileProvider(
    private val failOutputPath: String? = null,
    private val maximumReadRequest: Int? = null,
) : FileProvider {
    private val entries = mutableMapOf<String, Entry>("/" to Entry.Directory)
    var largestReadRequest: Int = 0
        private set

    fun putDirectory(path: String) {
        val normalized = path.normalized()
        require(normalized == "/" || entries[parent(normalized)] is Entry.Directory)
        entries[normalized] = Entry.Directory
    }

    fun putFile(path: String, contents: String) {
        putFile(path, contents.toByteArray())
    }

    fun putFile(path: String, contents: ByteArray) {
        val normalized = path.normalized()
        require(entries[parent(normalized)] is Entry.Directory)
        entries[normalized] = Entry.File(contents.copyOf())
    }

    fun readFile(path: String): ByteArray =
        (entries.getValue(path.normalized()) as Entry.File).contents.copyOf()

    override suspend fun listFiles(path: String): Result<List<FileItem>> = runCatching {
        val normalized = path.normalized()
        require(entries[normalized] is Entry.Directory) { "Not a directory: $path" }
        entries.keys
            .asSequence()
            .filter { it != normalized && parent(it) == normalized }
            .sorted()
            .map(::toFileItem)
            .toList()
    }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> = runCatching {
        val fullPath = join(path, name)
        require(entries[path.normalized()] is Entry.Directory) { "Not a directory: $path" }
        check(fullPath !in entries) { "Already exists: $fullPath" }
        entries[fullPath] = Entry.Directory
        toFileItem(fullPath)
    }

    override suspend fun createFile(path: String, name: String): Result<FileItem> = runCatching {
        val fullPath = join(path, name)
        require(entries[path.normalized()] is Entry.Directory) { "Not a directory: $path" }
        check(fullPath !in entries) { "Already exists: $fullPath" }
        entries[fullPath] = Entry.File(byteArrayOf())
        toFileItem(fullPath)
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val normalized = path.normalized()
        check(normalized != "/") { "Cannot delete root" }
        check(normalized in entries) { "Does not exist: $path" }
        entries.keys
            .filter { it == normalized || it.startsWith("$normalized/") }
            .toList()
            .forEach(entries::remove)
    }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        Result.failure(UnsupportedOperationException("Not needed by archive tests"))

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Not needed by archive tests"))

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Not needed by archive tests"))

    override suspend fun getInputStream(path: String): Result<InputStream> = runCatching {
        val bytes = (entries.getValue(path.normalized()) as Entry.File).contents
        object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestReadRequest = maxOf(largestReadRequest, length)
                maximumReadRequest?.let { maximum ->
                    check(length <= maximum) {
                        "Requested $length bytes from a stream limited to $maximum"
                    }
                }
                return super.read(buffer, offset, length)
            }
        }
    }

    override suspend fun getOutputStream(path: String): Result<OutputStream> = runCatching {
        val normalized = path.normalized()
        require(entries[normalized] is Entry.File) { "Not a file: $path" }
        object : ByteArrayOutputStream() {
            private var failed = false

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (normalized == failOutputPath?.normalized() && !failed) {
                    failed = true
                    val partialLength = minOf(length, 8)
                    if (partialLength > 0) super.write(buffer, offset, partialLength)
                    entries[normalized] = Entry.File(toByteArray())
                    throw IOException("Injected output failure for $normalized")
                }
                super.write(buffer, offset, length)
            }

            override fun write(value: Int) {
                if (normalized == failOutputPath?.normalized() && !failed) {
                    failed = true
                    entries[normalized] = Entry.File(toByteArray())
                    throw IOException("Injected output failure for $normalized")
                }
                super.write(value)
            }

            override fun close() {
                if (!failed) entries[normalized] = Entry.File(toByteArray())
                super.close()
            }
        }
    }

    override suspend fun exists(path: String): Boolean = path.normalized() in entries

    override suspend fun getFileInfo(path: String): Result<FileItem> = runCatching {
        toFileItem(path.normalized())
    }

    override fun getParentPath(path: String): String? =
        path.normalized().takeUnless { it == "/" }?.let(::parent)

    private fun toFileItem(path: String): FileItem {
        val entry = entries.getValue(path)
        return FileItem(
            name = path.substringAfterLast('/').ifEmpty { "/" },
            path = path,
            isDirectory = entry is Entry.Directory,
            size = (entry as? Entry.File)?.contents?.size?.toLong() ?: 0,
            lastModified = Date(1_700_000_000_000L),
            source = FileSource.LOCAL,
        )
    }

    private fun join(path: String, name: String): String =
        if (path.normalized() == "/") "/$name" else "${path.normalized()}/$name"

    private fun parent(path: String): String =
        path.substringBeforeLast('/').ifEmpty { "/" }

    private fun String.normalized(): String =
        if (this == "/") "/" else trimEnd('/')

    private sealed interface Entry {
        data object Directory : Entry
        data class File(val contents: ByteArray) : Entry
    }
}
