package com.voyagerfiles.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalFileProviderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val provider = LocalFileProvider()

    @Test
    fun openNewFileWritesAFreshFileAndRefusesAnExistingOne() = runBlocking {
        val folder = temporaryFolder.newFolder("new-files")
        File(folder, "taken.txt").writeText("existing")

        val created = provider.openNewFile(folder.path, "fresh.txt").getOrThrow()
        created.output.use { it.write("written".toByteArray()) }
        val refused = provider.openNewFile(folder.path, "taken.txt")

        assertEquals("fresh.txt", created.name)
        assertEquals(File(folder, "fresh.txt").absolutePath, created.path)
        assertEquals("written", File(folder, "fresh.txt").readText())
        assertTrue(refused.isFailure)
        assertEquals("existing", File(folder, "taken.txt").readText())
    }

    @Test
    fun copyDirectoryRefusesExistingDestinationAndPreservesBothTrees() = runBlocking {
        val source = temporaryFolder.newFolder("source")
        File(source, "source-only.txt").writeText("source")
        val destination = temporaryFolder.newFolder("destination")
        val existingTarget = File(destination, source.name).apply { mkdirs() }
        File(existingTarget, "existing.txt").writeText("existing")

        val result = provider.copy(source.path, destination.path)

        assertTrue(result.isFailure)
        assertTrue(source.exists())
        assertEquals("source", File(source, "source-only.txt").readText())
        assertEquals("existing", File(existingTarget, "existing.txt").readText())
        assertFalse(File(existingTarget, "source-only.txt").exists())
    }

    @Test
    fun moveDirectoryRefusesExistingDestinationWithoutDeletingSource() = runBlocking {
        val source = temporaryFolder.newFolder("move-source")
        File(source, "important.txt").writeText("keep me")
        val destination = temporaryFolder.newFolder("move-destination")
        val existingTarget = File(destination, source.name).apply { mkdirs() }
        File(existingTarget, "existing.txt").writeText("existing")

        val result = provider.move(source.path, destination.path)

        assertTrue(result.isFailure)
        assertTrue(source.exists())
        assertEquals("keep me", File(source, "important.txt").readText())
        assertEquals("existing", File(existingTarget, "existing.txt").readText())
    }

    @Test
    fun fileCopyRefusesExistingDestinationWithoutOverwriting() = runBlocking {
        val source = temporaryFolder.newFile("report.txt").apply { writeText("new") }
        val destination = temporaryFolder.newFolder("file-destination")
        val existingTarget = File(destination, source.name).apply { writeText("existing") }

        val result = provider.copy(source.path, destination.path)

        assertTrue(result.isFailure)
        assertEquals("new", source.readText())
        assertEquals("existing", existingTarget.readText())
    }

    @Test
    fun copyingDirectoryIntoDescendantIsRejectedWithoutCreatingTarget() = runBlocking {
        val source = temporaryFolder.newFolder("parent")
        val descendant = File(source, "child").apply { mkdirs() }

        val result = provider.copy(source.path, descendant.path)

        assertTrue(result.isFailure)
        assertTrue(source.exists())
        assertFalse(File(descendant, source.name).exists())
    }
}
