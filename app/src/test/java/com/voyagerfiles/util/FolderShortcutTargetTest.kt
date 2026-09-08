package com.voyagerfiles.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderShortcutTargetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsExistingFolderWithinStorageAndRejectsFilesAndMissingTargets() {
        val root = temporary.newFolder("storage")
        val folder = File(root, "folder").apply { mkdirs() }
        assertEquals(folder.canonicalFile, FolderShortcutTarget.resolve(folder.path, listOf(root)))
        val file = File(root, "file.txt").apply { writeText("file") }
        assertThrows(IllegalArgumentException::class.java) { FolderShortcutTarget.resolve(file.path, listOf(root)) }
        folder.delete()
        assertThrows(IllegalArgumentException::class.java) { FolderShortcutTarget.resolve(folder.path, listOf(root)) }
    }

    @Test fun rejectsTraversalAndSymlinksOutsideSharedStorage() {
        val root = temporary.newFolder("storage")
        val outside = temporary.newFolder("storage-private")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        for (path in listOf(outside.path, File(root, "escape").path, File(root, "../storage-private").path, "relative/path")) {
            assertThrows(IllegalArgumentException::class.java) { FolderShortcutTarget.resolve(path, listOf(root)) }
        }
    }
}
