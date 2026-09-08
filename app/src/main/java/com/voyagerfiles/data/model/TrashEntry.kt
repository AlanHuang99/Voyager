package com.voyagerfiles.data.model

import java.io.File
import java.util.Date

data class TrashEntry(
    val id: String,
    val originalPath: String,
    val displayName: String,
    val isDirectory: Boolean,
    val deletedAt: Long,
    val entryDirectory: File,
    val payload: File,
) {
    fun previewFile(): FileItem = FileItem(
        name = displayName,
        path = payload.absolutePath,
        isDirectory = isDirectory,
        size = payload.length(),
        lastModified = Date(payload.lastModified()),
        source = FileSource.LOCAL,
    )
}
