package com.voyagerfiles.data.duplicates

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicateScannerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun equalSizeDifferentContentAndZeroLengthAreDistinct() = runBlocking {
        val root = temp.newFolder()
        root.resolve("a").writeText("same")
        root.resolve("nested").mkdir()
        root.resolve("nested/b").writeText("same")
        root.resolve("different").writeText("diff")
        root.resolve("empty1").writeBytes(byteArrayOf())
        root.resolve("empty2").writeBytes(byteArrayOf())
        val scan = DuplicateScanner().scan(root)
        assertEquals(5, scan.filesExamined)
        assertEquals(2, scan.groups.size)
        assertEquals(setOf(setOf("a", "b"), setOf("empty1", "empty2")), scan.groups.map { group -> group.files.map { File(it.path).name }.toSet() }.toSet())
    }

    @Test fun sourceAndKeeperAreRehashedBeforeRemovalAndAllCopiesCannotBeSelected() = runBlocking {
        val root = temp.newFolder()
        val a = root.resolve("a").apply { writeText("same") }
        val b = root.resolve("b").apply { writeText("same") }
        val scanner = DuplicateScanner()
        val group = scanner.scan(root).groups.single()
        val selected = group.files.first { it.path == a.path }
        scanner.verifyRemoval(group, setOf(a.path), selected)
        assertTrue(runCatching { scanner.verifyRemoval(group, setOf(a.path, b.path), selected) }.isFailure)
        val originalTime = Files.getLastModifiedTime(b.toPath())
        b.writeText("diff")
        Files.setLastModifiedTime(b.toPath(), originalTime)
        assertTrue(runCatching { scanner.verifyRemoval(group, setOf(a.path), selected) }.isFailure)
        b.writeText("same")
        Files.setLastModifiedTime(b.toPath(), originalTime)
        val originalSelectedTime = Files.getLastModifiedTime(a.toPath())
        a.writeText("diff")
        Files.setLastModifiedTime(a.toPath(), originalSelectedTime)
        assertTrue(runCatching { scanner.verifyRemoval(group, setOf(a.path), selected) }.isFailure)
    }

    @Test fun missingKeeperAndReplacedParentLinkNeverAuthorizeRemoval() = runBlocking {
        val root = temp.newFolder()
        val nested = root.resolve("nested").apply { mkdirs() }
        val a = nested.resolve("a").apply { writeText("same") }
        val b = root.resolve("b").apply { writeText("same") }
        val scanner = DuplicateScanner()
        val group = scanner.scan(root).groups.single()
        val selected = group.files.first { it.path == a.path }
        b.delete()
        assertTrue(runCatching { scanner.verifyRemoval(group, setOf(a.path), selected) }.isFailure)
        a.delete()
        nested.delete()
        Files.createSymbolicLink(nested.toPath(), temp.newFolder().toPath())
        assertTrue(runCatching { scanner.verifyRemoval(group, setOf(b.path), group.files.first { it.path == b.path }) }.isFailure)
    }

    @Test fun linksAndTrashAreExcludedAndLimitsAreExplicit() = runBlocking {
        val root = temp.newFolder()
        val original = root.resolve("a").apply { writeText("same") }
        Files.createLink(root.resolve("hard").toPath(), original.toPath())
        Files.createSymbolicLink(root.resolve("link").toPath(), original.toPath())
        root.resolve(".VoyagerTrash").apply { mkdir(); resolve("payload").writeText("same") }
        val scan = DuplicateScanner().scan(root)
        assertEquals(1, scan.filesExamined)
        assertEquals(2, scan.linksSkipped)
        assertEquals(1, scan.excludedDirectories)
        assertTrue(scan.groups.isEmpty())
        repeat(4) { root.resolve("more$it").writeText("same") }
        assertTrue(DuplicateScanner(1).scan(root).limited)
    }

    @Test fun removedCandidateIsMissingAndCancellationStopsHashing() = runBlocking {
        val root = temp.newFolder()
        val a = root.resolve("a").apply { writeText("same") }
        root.resolve("b").writeText("same")
        val scan = DuplicateScanner().scan(root) { if (it.hashingPath == a.path) a.delete() }
        assertEquals(1, scan.missing)
        assertEquals(0, scan.unreadable)
        assertTrue(scan.groups.isEmpty())
        a.writeText("same")
        var stopped = false
        val job = launch {
            try {
                DuplicateScanner().scan(root) { if (it.hashingPath != null) cancel() }
                fail("Scan must propagate cancellation")
            } catch (_: CancellationException) { stopped = true }
        }
        job.join()
        assertTrue(stopped)
    }

    @Test fun unreadableAndChangedFilesHaveSeparateCoverageCounts() = runBlocking {
        val root = temp.newFolder()
        root.resolve("a").writeText("same")
        root.resolve("b").writeText("same")
        val unreadable = root.resolve("unreadable").apply { writeText("same") }
        val changed = root.resolve("changed").apply { writeText("same") }
        val originalPermissions = Files.getPosixFilePermissions(unreadable.toPath())
        try {
            Files.setPosixFilePermissions(unreadable.toPath(), emptySet())
            org.junit.Assume.assumeFalse(Files.isReadable(unreadable.toPath()))
            val scan = DuplicateScanner().scan(root) { if (it.hashingPath == changed.path) changed.writeText("changed") }
            assertEquals(1, scan.unreadable)
            assertEquals(1, scan.changed)
            assertEquals(0, scan.missing)
            assertEquals(setOf("a", "b"), scan.groups.single().files.map { File(it.path).name }.toSet())
        } finally {
            Files.setPosixFilePermissions(unreadable.toPath(), originalPermissions)
        }
    }
}
