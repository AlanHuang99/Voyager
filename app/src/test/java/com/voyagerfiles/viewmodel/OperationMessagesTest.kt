package com.voyagerfiles.viewmodel

import com.voyagerfiles.R
import com.voyagerfiles.data.archive.ArchiveConflictException
import com.voyagerfiles.data.archive.ArchiveFormat
import com.voyagerfiles.data.archive.PartialExtractionException
import com.voyagerfiles.data.archive.UnsupportedArchiveException
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.ui.text.UiText
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OperationMessagesTest {

    @Test
    fun extractionSummaryMentionsOnlyRenamesAndLeftOutEntriesThatHappened() {
        val root = UiText.Dynamic("photos_extracted")
        assertEquals(
            UiText.Resource(R.string.archive_extracted_to, listOf(root)),
            OperationMessages.archiveExtracted("photos_extracted", renamedEntries = 0, notExtractedEntries = 0),
        )
        assertEquals(
            UiText.Resource(
                R.string.archive_extracted_to_with_note,
                listOf(root, UiText.Plural(R.plurals.archive_entries_not_extracted, 3, listOf(3))),
            ),
            OperationMessages.archiveExtracted("photos_extracted", renamedEntries = 0, notExtractedEntries = 3),
        )
        assertEquals(
            UiText.Resource(
                R.string.archive_extracted_to_with_notes,
                listOf(
                    root,
                    UiText.Plural(R.plurals.archive_entries_renamed, 1, listOf(1)),
                    UiText.Plural(R.plurals.archive_entries_not_extracted, 2, listOf(2)),
                ),
            ),
            OperationMessages.archiveExtracted("photos_extracted", renamedEntries = 1, notExtractedEntries = 2),
        )
    }

    @Test
    fun keptPartialExtractionNamesTheFolderAndTheReason() {
        val root = FileItem(name = "bundle_extracted", path = "/w/bundle_extracted", isDirectory = true)
        val error = PartialExtractionException(root, IOException("CRC mismatch"))

        assertEquals(
            UiText.Resource(
                R.string.archive_extraction_stopped_kept,
                listOf(UiText.Dynamic("CRC mismatch"), UiText.Dynamic("bundle_extracted")),
            ),
            OperationMessages.archivePartiallyExtracted(error),
        )
    }

    @Test
    fun conflictSuggestsHowToRecover() {
        val error = DestinationConflictException("/target/report.pdf")

        assertEquals(
            UiText.Resource(
                R.string.operation_failed,
                listOf(
                    UiText.Resource(R.string.operation_paste),
                    UiText.Resource(
                        R.string.error_conflict,
                        listOf(UiText.Dynamic("An item named report.pdf already exists in this folder")),
                    ),
                ),
            ),
            OperationMessages.failure(R.string.operation_paste, error),
        )
    }

    @Test
    fun archiveConflictSuggestsHowToRecover() {
        val error = ArchiveConflictException("/target/backup.zip")

        assertEquals(
            UiText.Resource(
                R.string.operation_failed,
                listOf(
                    UiText.Resource(R.string.operation_compress),
                    UiText.Resource(
                        R.string.error_conflict,
                        listOf(UiText.Dynamic("An item named backup.zip already exists in this folder")),
                    ),
                ),
            ),
            OperationMessages.failure(R.string.operation_compress, error),
        )
    }

    @Test
    fun unsupportedArchivePreservesActionableReason() {
        val error = UnsupportedArchiveException(
            format = ArchiveFormat.RAR_UNSUPPORTED,
            message = "RAR extraction is not available in this build",
        )

        assertEquals(
            UiText.Resource(
                R.string.operation_failed,
                listOf(
                    UiText.Resource(R.string.operation_extract),
                    UiText.Dynamic("RAR extraction is not available in this build"),
                ),
            ),
            OperationMessages.failure(R.string.operation_extract, error),
        )
    }

    @Test
    fun permissionFailurePointsToStorageAccess() {
        assertEquals(
            UiText.Resource(
                R.string.operation_failed,
                listOf(
                    UiText.Resource(R.string.operation_delete),
                    UiText.Resource(R.string.error_permission_denied),
                ),
            ),
            OperationMessages.failure(R.string.operation_delete, SecurityException()),
        )
    }

    @Test
    fun networkFailuresDistinguishAddressAndTimeoutProblems() {
        assertEquals(
            UiText.Resource(
                R.string.operation_failed,
                listOf(
                    UiText.Resource(R.string.operation_connect),
                    UiText.Resource(R.string.error_server_unavailable),
                ),
            ),
            OperationMessages.failure(R.string.operation_connect, UnknownHostException()),
        )
        assertEquals(
            UiText.Resource(
                R.string.operation_failed,
                listOf(
                    UiText.Resource(R.string.operation_connect),
                    UiText.Resource(R.string.error_connection_timeout),
                ),
            ),
            OperationMessages.failure(R.string.operation_connect, SocketTimeoutException()),
        )
    }

    @Test
    fun missingFileSuggestsRefresh() {
        assertEquals(
            UiText.Resource(
                R.string.operation_failed,
                listOf(
                    UiText.Resource(R.string.operation_open),
                    UiText.Resource(R.string.error_item_unavailable),
                ),
            ),
            OperationMessages.failure(R.string.operation_open, FileNotFoundException()),
        )
    }

    @Test
    fun partialFailureIncludesCountAndFirstActionableCause() {
        assertEquals(
            UiText.Plural(
                R.plurals.partial_operation_failed,
                3,
                listOf(
                    1,
                    3,
                    UiText.Resource(R.string.operation_paste),
                    UiText.Resource(
                        R.string.error_conflict,
                        listOf(UiText.Dynamic("An item named photo.jpg already exists in this folder")),
                    ),
                ),
            ),
            OperationMessages.partial(
                failed = 1,
                total = 3,
                action = R.string.operation_paste,
                error = DestinationConflictException("/target/photo.jpg"),
            ),
        )
    }

    @Test
    fun failureKeepsStableCopyAndDynamicDetailSeparate() {
        assertEquals(
            UiText.Resource(
                R.string.operation_failed,
                listOf(UiText.Resource(R.string.operation_download), UiText.Dynamic("disk full")),
            ),
            OperationMessages.failure(R.string.operation_download, IOException("disk full")),
        )
    }

    @Test
    fun missingFailureDetailUsesLocalizedUnknownError() {
        val message = OperationMessages.failure(R.string.operation_rename, IOException())

        assertEquals(UiText.Resource(R.string.unknown_error), (message as UiText.Resource).args.last())
    }
}
