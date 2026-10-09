package com.voyagerfiles.data.archive

import com.voyagerfiles.data.model.FileItem

open class ArchiveException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class UnsafeArchiveEntryException(
    val entryName: String,
    reason: String,
) : ArchiveException("Unsafe archive entry \"$entryName\": $reason")

class UnsupportedArchiveException(
    val format: ArchiveFormat?,
    message: String,
) : ArchiveException(message)

class CorruptArchiveException(message: String, cause: Throwable? = null) :
    ArchiveException(message, cause)

class ArchiveConflictException(val path: String) :
    ArchiveException("An item named ${path.substringAfterLast('/')} already exists in this folder")

/** Extraction stopped with [cause]; the entries already written into [root] were kept. */
class PartialExtractionException(
    val root: FileItem,
    cause: Throwable,
) : ArchiveException(cause.message ?: "Extraction stopped", cause)

/** Writing [path] failed and removing the incomplete file failed too, so extraction cannot go on. */
class ArchiveCleanupException(
    val path: String,
    cause: Throwable,
) : ArchiveException("Could not remove the incomplete file ${path.substringAfterLast('/')}", cause)
