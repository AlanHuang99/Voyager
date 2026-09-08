package com.voyagerfiles.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File

/** A slow document source exercises the same-provider SAF fallback on a real resolver. */
class TransferDocumentsProvider : DocumentsProvider() {
    private val root get() = File(requireNotNull(context).cacheDir, "transfer-documents")

    @Volatile private var stallOutput = false
    @Volatile private var socketOutput = false
    @Volatile private var stalledInput: java.io.InputStream? = null
    private var releaseOutput = java.util.concurrent.CountDownLatch(1)

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "release") {
            releaseOutput.countDown()
            stalledInput?.close()
            return Bundle.EMPTY
        }
        if (method == "reset") {
            releaseOutput.countDown()
            stalledInput?.close()
            releaseOutput = java.util.concurrent.CountDownLatch(1)
            stallOutput = arg?.startsWith("stalled-") == true
            socketOutput = arg == "stalled-socket"
            root.deleteRecursively()
            root.mkdirs()
            File(root, "destination").mkdir()
            File(root, "source.bin").writeBytes(payload())
            return Bundle.EMPTY
        }
        if (method == "source") return Bundle().apply { putByteArray("digest", java.security.MessageDigest.getInstance("SHA-256").digest(File(root, "source.bin").readBytes())) }
        if (method == "partial") return Bundle().apply { putLong("bytes", File(root, "destination/source.bin").length()) }
        return super.call(method, arg, extras)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == "root" || documentId.startsWith("$parentDocumentId/")

    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf("root_id"))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection).apply { addDocument(documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        cursor(projection).apply {
            file(parentDocumentId).listFiles().orEmpty().forEach { child ->
                addDocument(if (parentDocumentId == "root") child.name else "$parentDocumentId/${child.name}")
            }
        }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val id = if (parentDocumentId == "root") displayName else "$parentDocumentId/$displayName"
        val target = file(id)
        check(!target.exists())
        check(if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile())
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = file(documentId)
        val destination = File(source.parentFile, displayName)
        check(!destination.exists() && source.renameTo(destination))
        return if ('/' in documentId) documentId.substringBeforeLast('/') + "/" + displayName else displayName
    }

    override fun deleteDocument(documentId: String) { check(file(documentId).deleteRecursively()) }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (stallOutput && documentId.startsWith("destination/") && mode.contains('w')) {
            val pipe = if (socketOutput) ParcelFileDescriptor.createSocketPair() else ParcelFileDescriptor.createPipe()
            val release = releaseOutput
            val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
            stalledInput = input
            Thread {
                runCatching {
                    input.use {
                        val buffer = ByteArray(4096)
                        val size = it.read(buffer)
                        if (size > 0) file(documentId).writeBytes(buffer.copyOf(size))
                        // Retain the read end without draining any more bytes until test cleanup.
                        release.await()
                    }
                }
            }.start()
            return pipe[1]
        }
        if (!stallOutput && documentId == "source.bin" && mode == "r") {
            val pipe = ParcelFileDescriptor.createPipe()
            Thread {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        file(documentId).inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                output.write(buffer, 0, size)
                                Thread.sleep(60)
                            }
                        }
                    }
                }
            }.start()
            return pipe[0]
        }
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    private fun file(id: String) = if (id == "root") root else File(root, id)
    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    ))
    private fun MatrixCursor.addDocument(id: String) {
        val target = file(id)
        if (!target.exists()) return
        addRow(columnNames.map { column -> when (column) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> target.name
            Document.COLUMN_MIME_TYPE -> if (target.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_SIZE -> target.length()
            Document.COLUMN_LAST_MODIFIED -> target.lastModified()
            else -> null
        } })
    }

    companion object {
        const val AUTHORITY = "com.voyagerfiles.test.transferdocs"
        fun payload() = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
    }
}
