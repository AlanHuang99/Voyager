package com.voyagerfiles.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameValidatorTest {

    @Test
    fun trimsAndAcceptsARegularName() {
        assertEquals(
            FileNameValidationResult.Valid("report.txt"),
            FileNameValidator.validate(" report.txt "),
        )
    }

    @Test
    fun rejectsBlankTraversalSeparatorsAndControlCharacters() {
        listOf("", "   ", ".", "..", "../secret", "folder/name", "bad\u0000name", "line\nbreak").forEach { name ->
            assertTrue(
                "Expected an invalid result for ${name.toCharArray().contentToString()}",
                FileNameValidator.validate(name) is FileNameValidationResult.Invalid,
            )
        }
    }

    @Test
    fun preservesValidDotsAndBackslashes() {
        assertEquals(
            FileNameValidationResult.Valid("archive.tar.gz"),
            FileNameValidator.validate("archive.tar.gz"),
        )
        assertEquals(
            FileNameValidationResult.Valid("notes\\draft"),
            FileNameValidator.validate("notes\\draft"),
        )
    }
}
