package com.voyagerfiles.data.remote.saf

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

class SafFileProvider(
    context: Context,
    private val treeUri: Uri,
) : FileProvider {

    private val contentResolver = context.contentResolver
    private val parentPaths = mutableMapOf<String, String>()
    val rootPath: String = rootDocumentUri(treeUri).toString()

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching { listFilesInternal(path) }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            createDocument(path, name, Document.MIME_TYPE_DIR)
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            createDocument(
                path = path,
                name = name,
                mimeType = FileItem(name = name, path = "", isDirectory = false).mimeType,
            )
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(DocumentsContract.deleteDocument(contentResolver, documentUri(path))) {
                    "Unable to delete document"
                }
                parentPaths.remove(path)
                Unit
            }
        }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val oldParent = parentPaths[oldPath]
                val renamedUri = DocumentsContract.renameDocument(
                    contentResolver,
                    documentUri(oldPath),
                    newName,
                ) ?: throw IllegalStateException("Unable to rename document")
                val item = queryDocument(renamedUri)
                parentPaths.remove(oldPath)
                if (oldParent != null) parentPaths[item.path] = oldParent
                item
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                copyPath(sourcePath, destPath)
            }
        }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                copyPath(sourcePath, destPath)
                check(DocumentsContract.deleteDocument(contentResolver, documentUri(sourcePath))) {
                    "Unable to delete document"
                }
                parentPaths.remove(sourcePath)
                Unit
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(documentUri(path))
                    ?: throw IllegalArgumentException("Unable to open input stream")
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(documentUri(path), "wt")
                    ?: throw IllegalArgumentException("Unable to open output stream")
            }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.query(documentUri(path), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
                    .use { cursor -> cursor?.moveToFirst() == true }
            }.getOrElse { false }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching { queryDocument(documentUri(path)) }
        }

    override fun getParentPath(path: String): String? {
        if (path == rootPath) return null
        return parentPaths[path]
    }

    private fun createDocument(path: String, name: String, mimeType: String): Result<FileItem> =
        runCatching {
            val parentUri = documentUri(path)
            val createdUri = DocumentsContract.createDocument(contentResolver, parentUri, mimeType, name)
                ?: throw IllegalStateException("Unable to create document")
            val item = queryDocument(createdUri)
            parentPaths[item.path] = path
            item
        }

    private fun copyPath(sourcePath: String, destPath: String) {
        val source = queryDocument(documentUri(sourcePath))
        if (source.isDirectory) {
            val destinationDirectory = createDocument(destPath, source.name, Document.MIME_TYPE_DIR).getOrThrow()
            listFilesInternal(sourcePath).forEach { child ->
                copyPath(child.path, destinationDirectory.path)
            }
            return
        }

        val destinationFile = createDocument(
            path = destPath,
            name = source.name,
            mimeType = FileItem(name = source.name, path = "", isDirectory = false).mimeType,
        ).getOrThrow()
        contentResolver.openInputStream(documentUri(sourcePath)).use { input ->
            requireNotNull(input) { "Unable to open input stream" }
            contentResolver.openOutputStream(documentUri(destinationFile.path), "wt").use { output ->
                requireNotNull(output) { "Unable to open output stream" }
                input.copyTo(output)
            }
        }
    }

    private fun listFilesInternal(path: String): List<FileItem> {
        val parentUri = documentUri(path)
        val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )

        return contentResolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null).use { cursor ->
            buildList {
                if (cursor == null) return@buildList
                while (cursor.moveToNext()) {
                    val item = cursor.toFileItem()
                    parentPaths[item.path] = path
                    add(item)
                }
            }
        }
    }

    private fun queryDocument(uri: Uri): FileItem {
        contentResolver.query(uri, DOCUMENT_PROJECTION, null, null, null).use { cursor ->
            require(cursor != null && cursor.moveToFirst()) { "Document not found" }
            return cursor.toFileItem()
        }
    }

    private fun Cursor.toFileItem(): FileItem {
        val documentId = getString(0)
        val name = getString(1) ?: documentId.substringAfterLast("/")
        val mimeType = getString(2)
        val isDirectory = mimeType == Document.MIME_TYPE_DIR
        val size = if (isNull(3) || isDirectory) 0L else getLong(3)
        val lastModifiedMillis = if (isNull(4)) 0L else getLong(4)
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

        return FileItem(
            name = name,
            path = uri.toString(),
            isDirectory = isDirectory,
            size = size,
            lastModified = Date(lastModifiedMillis.takeIf { it > 0L } ?: System.currentTimeMillis()),
            isHidden = name.startsWith("."),
            source = FileSource.SAF,
        )
    }

    private fun documentUri(path: String): Uri =
        Uri.parse(path)

    companion object {
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        fun rootDocumentUri(treeUri: Uri): Uri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )

        fun titleForTreeUri(treeUri: Uri): String {
            val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            return treeDocumentId
                ?.substringAfterLast(":")
                ?.substringAfterLast("/")
                ?.takeIf { it.isNotBlank() }
                ?: "Document Tree"
        }
    }
}
