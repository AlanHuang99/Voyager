package com.voyagerfiles.data.shizuku

import android.os.Binder
import android.os.ParcelFileDescriptor
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.RootFileProvider
import com.voyagerfiles.data.repository.RootShell
import com.voyagerfiles.data.repository.TransferAbortable
import com.voyagerfiles.data.repository.TransferCancellation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * File operations run in the Shizuku UserService ([ShizukuFileOperations]) and data moves through pipes.
 * Deletion, copies, moves, and renames retain the supervised shell; they run once per operation, while
 * listings and canonical paths run for every file of a transfer.
 */
class ShizukuFileProvider(private val connection: ShizukuConnection) : RootFileProvider(
    RootShell(startProcess = connection::start, requireRoot = false, accessName = "Shizuku", externalInputPipe = true, temporaryDirectory = "/data/local/tmp"),
    FileSource.SHIZUKU,
) {
    override fun listing(directory: String): ByteArray = connection.files { it.list(directory) }.let { read ->
        ShizukuInputStream(connection, read).use { it.readBytes() }
    }
    override fun canonicalAt(path: String): String = connection.files { it.canonical(path) }
    override fun statEntry(path: String): ByteArray = connection.files { it.stat(path) }
    override fun existsAt(path: String): Boolean = connection.files { it.exists(path) }
    override fun mkdirAt(path: String) { val pinned = primitivePath(path); connection.files { it.mkdir(pinned) } }
    override fun createFileAt(path: String) { val pinned = primitivePath(path); connection.files { it.createFile(pinned) } }
    override fun fingerprint(path: String): String { val pinned = primitivePath(path); return connection.files { it.fingerprint(pinned) } }
    override fun openRead(path: String): InputStream {
        val pinned = primitivePath(path)
        return ShizukuInputStream(connection, connection.files { it.openRead(pinned) })
    }
    override fun readBytes(path: String, maxBytes: Int): ByteArray = openRead(path).use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > maxBytes) throw IOException("Root command output exceeds the allowed limit")
            bytes.write(buffer, 0, count)
        }
        bytes.toByteArray()
    }
    override fun openStaged(path: String, expected: String?): OutputStream {
        // Identifies this process to the service, which discards the write if the process dies.
        val pinned = primitivePath(path)
        return ShizukuOutputStream(connection, connection.files { it.openWrite(pinned, expected.orEmpty(), Binder()) })
    }

    private fun primitivePath(path: String): String {
        val parent = canonicalAt(checkNotNull(getParentPath(path)))
        return "${parent.trimEnd('/')}/${path.substringAfterLast('/')}"
    }

    override fun close() {
        try { super.close() } finally { connection.close() }
    }
}

/** Reads to EOF, then asks the service how many bytes it sent, so a failed read never looks like a short file. */
private class ShizukuInputStream(private val connection: ShizukuConnection, private val read: IShizukuRead) : InputStream(), TransferAbortable {
    private val input = ParcelFileDescriptor.AutoCloseInputStream(connection.remote { read.output() })
    private var count = 0L
    private var finished = false

    override fun read(): Int = ByteArray(1).let { if (read(it) < 0) -1 else it[0].toInt() and 255 }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val result = input.read(bytes, offset, length)
        if (result < 0) finish() else count += result
        return result
    }
    override fun available(): Int = input.available()
    override fun close() = input.close()
    override fun abortTransfer() = input.close()

    private fun finish() {
        if (finished) return
        finished = true
        val sent = connection.remote { read.finish() }
        if (sent != count) throw IOException("Shizuku read ended early")
    }
}

/** Closing commits the staged replacement; [abortTransfer] discards it. */
private class ShizukuOutputStream(
    private val connection: ShizukuConnection,
    private val write: IShizukuWrite,
) : OutputStream(), TransferAbortable {
    private val output = try {
        ParcelFileDescriptor.AutoCloseOutputStream(connection.remote { write.input() })
    } catch (error: Throwable) {
        runCatching { write.abort() }
        throw error
    }
    private var closed = false

    override fun write(byte: Int) = output.write(byte)
    override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
    override fun close() {
        if (closed) return
        closed = true
        try {
            TransferCancellation.check()
            output.close()
            connection.files { write.commit() }
        } catch (error: Throwable) {
            discard()
            throw error
        }
    }
    override fun abortTransfer() {
        closed = true
        discard()
    }
    private fun discard() {
        runCatching { output.close() }
        runCatching { write.abort() }
    }
}
