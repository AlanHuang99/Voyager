package com.voyagerfiles.data.archive

object ArchiveEntryPath {

    fun parse(raw: String): Result<List<String>> = runCatching {
        if (raw.isBlank()) throw UnsafeArchiveEntryException(raw, "the path is blank")
        if ('\u0000' in raw) throw UnsafeArchiveEntryException(raw, "NUL characters are not allowed")

        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/')) {
            throw UnsafeArchiveEntryException(raw, "absolute paths are not allowed")
        }
        if (WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) {
            throw UnsafeArchiveEntryException(raw, "Windows drive paths are not allowed")
        }

        val withoutDirectorySuffix = normalized.removeSuffix("/")
        if (withoutDirectorySuffix.isBlank()) {
            throw UnsafeArchiveEntryException(raw, "the path is blank")
        }
        val segments = withoutDirectorySuffix.split('/')
        if (segments.any { it.isBlank() }) {
            throw UnsafeArchiveEntryException(raw, "blank path segments are not allowed")
        }
        if (segments.any { it == "." || it == ".." }) {
            throw UnsafeArchiveEntryException(raw, "relative traversal segments are not allowed")
        }
        segments
    }

    private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
}
