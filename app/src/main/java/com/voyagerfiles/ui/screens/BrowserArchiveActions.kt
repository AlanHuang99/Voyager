package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.archive.ArchiveFormat
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.util.FileNameValidationResult
import com.voyagerfiles.util.FileNameValidator

enum class BrowserArchiveAction {
    COMPRESS_TO_ZIP,
    EXTRACT_HERE,
    EXTRACTION_UNSUPPORTED,
}

enum class ArchiveTapAction {
    CONFIRM_EXTRACTION,
    SHOW_UNSUPPORTED,
    OPEN_EXTERNALLY,
}

object BrowserArchiveActions {

    fun tapAction(item: FileItem): ArchiveTapAction = when {
        ArchiveFormat.detect(item.name)?.canExtract == true ->
            ArchiveTapAction.CONFIRM_EXTRACTION
        item.isArchive -> ArchiveTapAction.SHOW_UNSUPPORTED
        else -> ArchiveTapAction.OPEN_EXTERNALLY
    }

    fun forSelection(items: List<FileItem>): Set<BrowserArchiveAction> = buildSet {
        if (items.isEmpty()) return@buildSet
        add(BrowserArchiveAction.COMPRESS_TO_ZIP)
        if (items.size != 1) return@buildSet

        val item = items.single()
        val format = ArchiveFormat.detect(item.name)
        when {
            format?.canExtract == true -> add(BrowserArchiveAction.EXTRACT_HERE)
            format == ArchiveFormat.RAR_UNSUPPORTED || item.isArchive -> {
                add(BrowserArchiveAction.EXTRACTION_UNSUPPORTED)
            }
        }
    }

    fun unsupportedExtractionReason(items: List<FileItem>): String? {
        if (items.size != 1) return null
        val item = items.single()
        val format = ArchiveFormat.detect(item.name)
        return when {
            format == ArchiveFormat.RAR_UNSUPPORTED ->
                "RAR extraction is not available in this build"
            format == null && item.isArchive ->
                "${item.extension.uppercase()} extraction is not available in this build"
            else -> null
        }
    }

    fun defaultZipName(
        selectedItems: List<FileItem>,
        existingNames: Set<String>,
    ): String {
        require(selectedItems.isNotEmpty()) { "Select at least one item to compress" }
        val proposedBase = if (selectedItems.size == 1) {
            selectedItems.single().let { item ->
                if (item.isDirectory) {
                    item.name
                } else {
                    item.name.substringBeforeLast('.', item.name)
                }
            }
        } else {
            "Archive"
        }
        val base = if ('\\' in proposedBase) {
            "Archive"
        } else {
            when (val validation = FileNameValidator.validate(proposedBase)) {
                is FileNameValidationResult.Valid -> validation.name
                is FileNameValidationResult.Invalid -> "Archive"
            }.ifBlank { "Archive" }
        }
        val normalizedExistingNames = existingNames.mapTo(mutableSetOf()) { it.lowercase() }

        var candidate = "$base.zip"
        var suffix = 2
        while (candidate.lowercase() in normalizedExistingNames) {
            candidate = "$base ($suffix).zip"
            suffix++
        }
        return candidate
    }
}
