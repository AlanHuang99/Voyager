package com.voyagerfiles.data.archive

data class ArchiveProgress(
    val currentEntryName: String? = null,
    val completedEntries: Int = 0,
    val processedBytes: Long = 0,
    val totalBytes: Long? = null,
) {
    init {
        require(completedEntries >= 0) { "Completed entry count must not be negative" }
        require(processedBytes >= 0) { "Processed byte count must not be negative" }
        require(totalBytes == null || totalBytes >= 0) { "Total byte count must not be negative" }
    }
}
