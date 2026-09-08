package com.voyagerfiles.data.duplicates

import com.voyagerfiles.data.repository.LocalTrashManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicateRemovalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun trashPreservesOneCopyAndSupportsRestoringTheRemovedDuplicate() = runBlocking {
        val root = temp.newFolder()
        val a = root.resolve("a").apply { writeText("same") }
        val b = root.resolve("b").apply { writeText("same") }
        val trash = LocalTrashManager(listOf(root))
        val groups = DuplicateScanner().scan(root).groups
        val result = removeVerifiedDuplicates(groups, setOf(b.path)) { trash.moveToTrash(it).getOrThrow() }
        assertEquals(setOf(b.path), result.removed)
        assertTrue(result.failures.isEmpty())
        assertEquals("same", a.readText())
        assertFalse(b.exists())
        val entry = trash.listEntries().single()
        assertEquals("same", entry.payload.readText())
        trash.restore(entry).getOrThrow()
        assertEquals("same", b.readText())
    }

    @Test fun mutatedKeeperStopsDeletionEvenWhenSizeAndTimeAreUnchanged() = runBlocking {
        val root = temp.newFolder()
        val a = root.resolve("a").apply { writeText("same") }
        val b = root.resolve("b").apply { writeText("same") }
        val groups = DuplicateScanner().scan(root).groups
        val timestamp = Files.getLastModifiedTime(a.toPath())
        a.writeText("diff")
        Files.setLastModifiedTime(a.toPath(), timestamp)
        var calls = 0
        val result = removeVerifiedDuplicates(groups, setOf(b.path)) { calls++; Files.delete(File(it).toPath()) }
        assertEquals(0, calls)
        assertTrue(result.removed.isEmpty())
        assertEquals(setOf(b.path), result.failures.keys)
        assertEquals("same", b.readText())
    }

    @Test fun selectingAllCopiesRejectsTheWholeBatchBeforeAnyMutation() = runBlocking {
        val root = temp.newFolder()
        val a = root.resolve("a").apply { writeText("same") }
        val b = root.resolve("b").apply { writeText("same") }
        val groups = DuplicateScanner().scan(root).groups
        var calls = 0
        val error = runCatching { removeVerifiedDuplicates(groups, setOf(a.path, b.path)) { calls++ } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, calls)
        assertEquals("same", a.readText())
        assertEquals("same", b.readText())
    }
}
