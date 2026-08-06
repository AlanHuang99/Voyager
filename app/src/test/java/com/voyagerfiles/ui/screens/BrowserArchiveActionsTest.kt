package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.model.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserArchiveActionsTest {

    @Test
    fun offersCompressionForAnyNonEmptySelection() {
        val normalFile = file("notes.txt")
        val folder = file("Folder", isDirectory = true)

        assertTrue(
            BrowserArchiveAction.COMPRESS_TO_ZIP in
                BrowserArchiveActions.forSelection(listOf(normalFile)),
        )
        assertTrue(
            BrowserArchiveAction.COMPRESS_TO_ZIP in
                BrowserArchiveActions.forSelection(listOf(normalFile, folder)),
        )
        assertTrue(BrowserArchiveActions.forSelection(emptyList()).isEmpty())
    }

    @Test
    fun offersExtractionOnlyForOneSupportedArchive() {
        assertEquals(
            setOf(
                BrowserArchiveAction.COMPRESS_TO_ZIP,
                BrowserArchiveAction.EXTRACT_HERE,
            ),
            BrowserArchiveActions.forSelection(listOf(file("bundle.tar.gz"))),
        )
        assertEquals(
            setOf(BrowserArchiveAction.COMPRESS_TO_ZIP),
            BrowserArchiveActions.forSelection(listOf(file("one.zip"), file("two.zip"))),
        )
    }

    @Test
    fun identifiesRarAsUnsupportedWithActionableExplanation() {
        val selection = listOf(file("legacy.rar"))

        assertEquals(
            setOf(
                BrowserArchiveAction.COMPRESS_TO_ZIP,
                BrowserArchiveAction.EXTRACTION_UNSUPPORTED,
            ),
            BrowserArchiveActions.forSelection(selection),
        )
        assertEquals(
            "RAR extraction is not available in this build",
            BrowserArchiveActions.unsupportedExtractionReason(selection),
        )
        assertNull(BrowserArchiveActions.unsupportedExtractionReason(listOf(file("notes.txt"))))
    }

    @Test
    fun generatesNonConflictingZipDefaults() {
        assertEquals(
            "notes.zip",
            BrowserArchiveActions.defaultZipName(
                selectedItems = listOf(file("notes.txt")),
                existingNames = setOf("notes.txt"),
            ),
        )
        assertEquals(
            "notes (3).zip",
            BrowserArchiveActions.defaultZipName(
                selectedItems = listOf(file("notes.txt")),
                existingNames = setOf("notes.txt", "notes.zip", "notes (2).ZIP"),
            ),
        )
        assertEquals(
            "Archive.zip",
            BrowserArchiveActions.defaultZipName(
                selectedItems = listOf(file("one.txt"), file("two.txt")),
                existingNames = emptySet(),
            ),
        )
    }

    @Test
    fun generatesSafeDefaultForProviderNameThatCannotBeAnArchiveChild() {
        assertEquals(
            "Archive.zip",
            BrowserArchiveActions.defaultZipName(
                selectedItems = listOf(file("folder\\notes.txt")),
                existingNames = emptySet(),
            ),
        )
    }

    @Test
    fun supportedArchiveTapRequestsConfirmation() {
        assertEquals(
            ArchiveTapAction.CONFIRM_EXTRACTION,
            BrowserArchiveActions.tapAction(file("bundle.zip")),
        )
    }

    @Test
    fun unsupportedAndOrdinaryFilesKeepDistinctTapBehavior() {
        assertEquals(
            ArchiveTapAction.SHOW_UNSUPPORTED,
            BrowserArchiveActions.tapAction(file("legacy.rar")),
        )
        assertEquals(
            ArchiveTapAction.OPEN_EXTERNALLY,
            BrowserArchiveActions.tapAction(file("notes.txt")),
        )
    }

    private fun file(name: String, isDirectory: Boolean = false): FileItem =
        FileItem(name = name, path = "/$name", isDirectory = isDirectory)
}
