package com.voyagerfiles.data.archive

import com.voyagerfiles.data.model.FileItem

/**
 * What an extraction wrote into [root]. Extraction never stops to ask: folders whose names differ only
 * in letter case are merged, such files get numbered names, entries that cannot be extracted are left
 * out, and renames and failures are listed here so the result can say what happened. Each list keeps at
 * most [MAX_LISTED] entries; the counts are complete.
 */
data class ArchiveExtractionReport(
    val root: FileItem,
    val extractedEntries: Int,
    val extractedFiles: Int,
    val totalEntries: Int?,
    val renamed: List<RenamedEntry>,
    val notExtracted: List<FailedEntry>,
    val renamedCount: Int = renamed.size,
    val notExtractedCount: Int = notExtracted.size,
    /** False when the extraction was cancelled or the archive broke off; what was written is kept. */
    val complete: Boolean,
) {
    companion object {
        /** Enough to read through; an archive with thousands of broken entries must not hold them all. */
        const val MAX_LISTED = 200
    }
}

/** [entryPath] was extracted as [newName], because this location ignores letter case. */
data class RenamedEntry(val entryPath: String, val newName: String)

data class FailedEntry(val entryPath: String, val error: Throwable)
