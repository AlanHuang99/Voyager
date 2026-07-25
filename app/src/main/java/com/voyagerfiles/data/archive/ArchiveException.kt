package com.voyagerfiles.data.archive

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
