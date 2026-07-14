package com.voyagerfiles.data.model

import java.io.File

data class TrashEntry(
    val id: String,
    val originalPath: String,
    val displayName: String,
    val isDirectory: Boolean,
    val deletedAt: Long,
    val entryDirectory: File,
    val payload: File,
)
