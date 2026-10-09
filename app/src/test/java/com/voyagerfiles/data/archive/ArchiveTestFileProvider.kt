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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Declares [ignoresNameCase], where null leaves it to the extraction's own lookup. With
 * [lookupIgnoresCase], names that differ only in case find the same item and creating a file over one
 * empties it, as an FTP upload to a Windows server does. Deleting [failDeletePath], or a folder above it,
 * fails as a stuck file would, and the first lookup of [failLookupPath] fails as a dropped connection
 * would. [writeDelayMillis] holds each output stream open that long, so parallel writes overlap.
 */
internal open class ArchiveTestFileProvider(
    private val failOutputPath: String? = null,
    private val maximumReadRequest: Int? = null,
    private val failDeletePath: String? = null,
    private val ignoresNameCase: Boolean? = false,
    private val lookupIgnoresCase: Boolean = false,
    private var failLookupPath: String? = null,
    override val parallelWrites: Int = 1,
    private val writeDelayMillis: Long = 0,
) : FileProvider {
    private val entries: MutableMap<String, Entry> = ConcurrentHashMap<String, Entry>().apply { put("/", Entry.Directory) }
    private val openOutputs = AtomicInteger()
    var largestReadRequest: Int = 0
        private set

    /** The most output streams that were open at the same time. */
    @Volatile
    var mostConcurrentWrites: Int = 0
        private set

    /** Output streams that are open now. */
    val openOutputCount: Int
        get() = openOutputs.get()

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
        (entries.getValue(path.stored()) as Entry.File).contents.copyOf()

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
        val fullPath = join(path.stored(), name)
        require(entries[path.stored()] is Entry.Directory) { "Not a directory: $path" }
        check(fullPath.stored() !in entries) { "Already exists: $fullPath" }
        entries[fullPath] = Entry.Directory
        toFileItem(fullPath)
    }

    override suspend fun createFile(path: String, name: String): Result<FileItem> = runCatching {
        val fullPath = join(path.stored(), name)
        require(entries[path.stored()] is Entry.Directory) { "Not a directory: $path" }
        synchronized(entries) {
            val existing = fullPath.stored()
            check(entries[existing] == null || (lookupIgnoresCase && entries[existing] is Entry.File)) {
                "Already exists: $fullPath"
            }
            entries[existing] = Entry.File(byteArrayOf())
        }
        toFileItem(fullPath)
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val normalized = path.stored()
        check(normalized != "/") { "Cannot delete root" }
        check(normalized in entries) { "Does not exist: $path" }
        failDeletePath?.normalized()?.let { stuck ->
            if (stuck in entries && (stuck == normalized || stuck.startsWith("$normalized/"))) {
                throw IOException("Injected delete failure for $stuck")
            }
        }
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
        val bytes = (entries.getValue(path.stored()) as Entry.File).contents
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
        val normalized = path.stored()
        require(entries[normalized] is Entry.File) { "Not a file: $path" }
        val open = openOutputs.incrementAndGet()
        synchronized(openOutputs) { mostConcurrentWrites = maxOf(mostConcurrentWrites, open) }
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

            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                if (writeDelayMillis > 0) Thread.sleep(writeDelayMillis)
                if (!failed) entries[normalized] = Entry.File(toByteArray())
                openOutputs.decrementAndGet()
                super.close()
            }
        }
    }

    override fun ignoresNameCase(path: String): Boolean? = ignoresNameCase

    override suspend fun exists(path: String): Boolean {
        if (path.normalized() == failLookupPath?.normalized()) {
            failLookupPath = null
            return false
        }
        return path.stored() in entries
    }

    override suspend fun getFileInfo(path: String): Result<FileItem> = runCatching {
        toFileItem(path.normalized())
    }

    override fun getParentPath(path: String): String? =
        path.normalized().takeUnless { it == "/" }?.let(::parent)

    /** Names the item as [path] does; with [lookupIgnoresCase] the item may be stored in another case. */
    private fun toFileItem(path: String): FileItem {
        val entry = entries.getValue(path.stored())
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

    /** The key this path is stored under. */
    private fun String.stored(): String {
        val normalized = normalized()
        if (!lookupIgnoresCase || normalized in entries) return normalized
        return entries.keys.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
    }

    private sealed interface Entry {
        data object Directory : Entry
        data class File(val contents: ByteArray) : Entry
    }
}
