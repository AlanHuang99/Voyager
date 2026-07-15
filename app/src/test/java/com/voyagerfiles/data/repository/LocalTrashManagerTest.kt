package com.voyagerfiles.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LocalTrashManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val volume by lazy { temporaryFolder.newFolder("volume") }
    private val ids = AtomicInteger()
    private val manager by lazy {
        LocalTrashManager(
            volumeRoots = listOf(volume),
            clock = { 1_720_000_000_000L + ids.get() },
            idGenerator = { "entry-${ids.incrementAndGet()}" },
        )
    }

    @Test
    fun moveAndRestorePreserveFileContentsAndOriginalPath() = runBlocking {
        val source = File(volume, "Documents/report.txt").apply {
            parentFile!!.mkdirs()
            writeText("report")
        }

        val entry = manager.moveToTrash(source.path).getOrThrow()

        assertFalse(source.exists())
        assertEquals("report.txt", entry.displayName)
        assertEquals("report", entry.payload.readText())
        manager.restore(entry).getOrThrow()
        assertEquals("report", source.readText())
        assertTrue(manager.listEntries().isEmpty())
    }

    @Test
    fun restoreRefusesExistingTargetAndKeepsTrashEntry() = runBlocking {
        val source = File(volume, "Documents/report.txt").apply {
            parentFile!!.mkdirs()
            writeText("original")
        }
        val entry = manager.moveToTrash(source.path).getOrThrow()
        source.writeText("replacement")

        val result = manager.restore(entry)

        assertTrue(result.isFailure)
        assertEquals("replacement", source.readText())
        assertEquals("original", entry.payload.readText())
    }

    @Test
    fun directoriesAndDuplicateNamesUseIndependentEntries() = runBlocking {
        val first = File(volume, "one/shared").apply {
            mkdirs()
            File(this, "first.txt").writeText("first")
        }
        val second = File(volume, "two/shared").apply {
            mkdirs()
            File(this, "second.txt").writeText("second")
        }

        val firstEntry = manager.moveToTrash(first.path).getOrThrow()
        val secondEntry = manager.moveToTrash(second.path).getOrThrow()

        assertNotEquals(firstEntry.id, secondEntry.id)
        assertTrue(File(firstEntry.payload, "first.txt").exists())
        assertTrue(File(secondEntry.payload, "second.txt").exists())
        assertEquals(2, manager.listEntries().size)
    }

    @Test
    fun pathsOutsideConfiguredVolumesAreRejected() = runBlocking {
        val outside = temporaryFolder.newFile("outside.txt")

        val result = manager.moveToTrash(outside.path)

        assertTrue(result.isFailure)
        assertTrue(outside.exists())
    }

    @Test
    fun permanentDeleteRemovesPayloadAndEntry() = runBlocking {
        val source = File(volume, "delete.txt").apply { writeText("delete") }
        val entry = manager.moveToTrash(source.path).getOrThrow()

        manager.deletePermanently(entry).getOrThrow()

        assertFalse(entry.entryDirectory.exists())
        assertTrue(manager.listEntries().isEmpty())
    }

    @Test
    fun emptyRemovesValidAndCorruptEntries() = runBlocking {
        val source = File(volume, "delete.txt").apply { writeText("delete") }
        manager.moveToTrash(source.path).getOrThrow()
        val corrupt = File(volume, ".VoyagerTrash/corrupt").apply { mkdirs() }
        File(corrupt, "payload").writeText("orphan")

        val removed = manager.empty().getOrThrow()

        assertEquals(2, removed)
        assertFalse(corrupt.exists())
        assertTrue(manager.listEntries().isEmpty())
    }
}
