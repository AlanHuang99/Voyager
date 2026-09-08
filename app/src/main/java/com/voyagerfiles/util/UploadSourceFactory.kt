package com.voyagerfiles.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.voyagerfiles.viewmodel.UploadSource
import java.io.IOException

object UploadSourceFactory {
    fun fromUri(contentResolver: ContentResolver, uri: Uri): UploadSource {
        val metadata = queryMetadata(contentResolver, uri)
        val displayName = metadata.displayName
            ?: uri.lastPathSegment?.takeIf(String::isNotBlank)
            ?: throw IOException("Could not determine the selected file name")
        return UploadSource(
            name = displayName,
            size = metadata.size,
            identifier = uri.toString(),
            lastModified = queryLastModified(contentResolver, uri),
            openInputStream = {
                contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open $displayName")
            },
        )
    }

    private fun queryMetadata(contentResolver: ContentResolver, uri: Uri): DocumentMetadata =
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (!cursor.moveToFirst()) return@use DocumentMetadata()
            DocumentMetadata(
                displayName = nameColumn
                    .takeIf { it >= 0 }
                    ?.let(cursor::getString)
                    ?.takeIf(String::isNotBlank),
                size = sizeColumn
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                    ?.takeIf { it >= 0 },
            )
        } ?: DocumentMetadata()

    private fun queryLastModified(resolver: ContentResolver, uri: Uri): java.util.Date? = runCatching {
        val column = android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index).takeIf { it > 0 }?.let { java.util.Date(it) }
            } else null
        }
    }.getOrNull()

    private data class DocumentMetadata(
        val displayName: String? = null,
        val size: Long? = null,
    )
}
