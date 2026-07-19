package com.voyagerfiles.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.voyagerfiles.viewmodel.UploadSource
import java.io.IOException

object UploadSourceFactory {
    fun fromUri(contentResolver: ContentResolver, uri: Uri): UploadSource {
        val displayName = queryDisplayName(contentResolver, uri)
            ?: uri.lastPathSegment?.takeIf(String::isNotBlank)
            ?: throw IOException("Could not determine the selected file name")
        return UploadSource(displayName) {
            contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open $displayName")
        }
    }

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? =
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameColumn)?.takeIf(String::isNotBlank)
            } else {
                null
            }
        }
}
