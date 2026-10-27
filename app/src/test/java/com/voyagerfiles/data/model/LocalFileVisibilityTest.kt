package com.voyagerfiles.data.model

import org.junit.Assert.*
import org.junit.Test

class LocalFileVisibilityTest {
    @Test fun togglesNamesWithoutChangingExtensionsOrWhitespace() {
        val cases = mapOf(
            "notes.txt" to ".notes.txt",
            ".notes.txt" to "notes.txt",
            "..private" to "private",
            "Folder" to ".Folder",
            ".Folder" to "Folder",
            " spaced.txt " to ". spaced.txt ",
            ". spaced.txt " to " spaced.txt ",
        )
        for ((name, target) in cases) {
            assertEquals(target, LocalFileVisibility.targetName(FileItem(name, "/$name", false)))
        }
    }

    @Test fun rejectsUnusableNamesAndUnsupportedStorageProviders() {
        for (name in listOf("", " ", ".", "..", "...", ".  ", "bad/name", "bad\u0000name")) {
            assertNull(name, LocalFileVisibility.targetName(FileItem(name, "/$name", false)))
        }
        for (source in FileSource.entries.filter { it != FileSource.LOCAL }) {
            assertNull(source.name, LocalFileVisibility.targetName(FileItem("notes.txt", "/notes.txt", false, source = source)))
        }
    }
}
