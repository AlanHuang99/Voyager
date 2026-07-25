package com.voyagerfiles.data.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveEntryPathTest {

    @Test
    fun normalizesBackslashesAndPreservesUnicodeSegments() {
        assertEquals(
            listOf("资料", "résumé.txt"),
            ArchiveEntryPath.parse("资料\\résumé.txt").getOrThrow(),
        )
        assertEquals(
            listOf("folder", "nested"),
            ArchiveEntryPath.parse("folder/nested/").getOrThrow(),
        )
    }

    @Test
    fun rejectsUnixWindowsAndUncAbsolutePaths() {
        assertTrue(ArchiveEntryPath.parse("/etc/passwd").isFailure)
        assertTrue(ArchiveEntryPath.parse("C:\\Windows\\system.ini").isFailure)
        assertTrue(ArchiveEntryPath.parse("C:relative.txt").isFailure)
        assertTrue(ArchiveEntryPath.parse("\\\\server\\share\\file.txt").isFailure)
    }

    @Test
    fun rejectsTraversalDotNulAndBlankSegments() {
        assertTrue(ArchiveEntryPath.parse("../escape.txt").isFailure)
        assertTrue(ArchiveEntryPath.parse("folder/../escape.txt").isFailure)
        assertTrue(ArchiveEntryPath.parse("./file.txt").isFailure)
        assertTrue(ArchiveEntryPath.parse("folder/./file.txt").isFailure)
        assertTrue(ArchiveEntryPath.parse("folder//file.txt").isFailure)
        assertTrue(ArchiveEntryPath.parse("folder/ /file.txt").isFailure)
        assertTrue(ArchiveEntryPath.parse("folder/\u0000file.txt").isFailure)
        assertTrue(ArchiveEntryPath.parse("").isFailure)
        assertTrue(ArchiveEntryPath.parse("   ").isFailure)
    }
}
