package com.voyagerfiles.data.archive

/**
 * Overall progress of an archive operation. Byte counts cover the whole operation, not the current
 * entry; totals are null when the format cannot know them up front.
 */
data class ArchiveProgress(
    val phase: ArchivePhase = ArchivePhase.WRITING,
    val currentEntryName: String? = null,
    val completedEntries: Int = 0,
    val totalEntries: Int? = null,
    val processedBytes: Long = 0,
    val totalBytes: Long? = null,
    val skippedEntries: Int = 0,
    val renamedEntries: Int = 0,
) {
    init {
        require(completedEntries >= 0) { "Completed entry count must not be negative" }
        require(skippedEntries >= 0) { "Skipped entry count must not be negative" }
        require(renamedEntries >= 0) { "Renamed entry count must not be negative" }
        require(totalEntries == null || totalEntries >= 0) { "Total entry count must not be negative" }
        require(processedBytes >= 0) { "Processed byte count must not be negative" }
        require(totalBytes == null || totalBytes >= 0) { "Total byte count must not be negative" }
    }
}

enum class ArchivePhase {
    /** Copying the archive to a temporary file before it can be read. */
    READING_SOURCE,

    /** Walking the selection to learn what will be compressed. */
    PREPARING,

    /** Creating the archive or writing extracted entries. */
    WRITING,

    /** Deleting what a stopped or cancelled extraction had written. */
    REMOVING,
}
