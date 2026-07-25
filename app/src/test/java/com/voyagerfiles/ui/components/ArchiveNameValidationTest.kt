package com.voyagerfiles.ui.components

import com.voyagerfiles.util.FileNameValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveNameValidationTest {

    @Test
    fun acceptsAndTrimsZipNames() {
        assertEquals(
            FileNameValidationResult.Valid("backup.ZIP"),
            validateZipArchiveName(" backup.ZIP "),
        )
    }

    @Test
    fun rejectsMissingZipSuffixAndProviderSeparators() {
        assertEquals(
            FileNameValidationResult.Invalid("Name must end with .zip"),
            validateZipArchiveName("backup.tar"),
        )
        val separatorResult = validateZipArchiveName("folder\\backup.zip")
        assertTrue(separatorResult is FileNameValidationResult.Invalid)
        assertEquals(
            "Names cannot contain backslashes",
            (separatorResult as FileNameValidationResult.Invalid).message,
        )
    }
}
