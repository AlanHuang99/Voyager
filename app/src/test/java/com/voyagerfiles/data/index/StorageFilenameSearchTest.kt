package com.voyagerfiles.data.index

import com.voyagerfiles.util.StorageVolumeInfo
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageFilenameSearchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun findsNamesAcrossVolumesWithoutRestrictingFileTypes() = runBlocking {
        val internal = temporary.newFolder("internal")
        val removable = temporary.newFolder("removable")
        val first = write(internal, "Download/Report.LOG")
        val second = write(removable, "nested/REPORT.bin")
        write(internal, "report-folder/unrelated.txt")
        val result = StorageCategoryIndex().search(
            "  rEpOrT ", listOf(volume(internal), volume(removable)), false,
        ) { true }.last()

        assertEquals(setOf(first.path, second.path), result.files.map { it.path }.toSet())
        assertEquals(CategoryScanStatus.COMPLETE, result.status)
        assertEquals(3, result.coverage.sumOf { it.filesExamined })
    }

    @Test
    fun searchPreservesHiddenLinkAndPermissionRules() = runBlocking {
        val root = temporary.newFolder("storage")
        write(root, "visible/match.dat")
        write(root, ".hidden/match.bin")
        Files.createSymbolicLink(root.toPath().resolve("match-link"), root.toPath())
        val index = StorageCategoryIndex()
        val volumes = listOf(volume(root))

        val visible = index.search("match", volumes, false) { true }.last()
        assertEquals(listOf("match.dat"), visible.files.map { it.name })
        assertEquals(CategoryScanStatus.PARTIAL, visible.status)
        val hidden = index.search("match", volumes, true) { true }.last()
        assertEquals(setOf("match.dat", "match.bin"), hidden.files.map { it.name }.toSet())
        val denied = StorageCategoryIndex(canRead = { error("Denied search must not read storage") })
            .search("match", volumes, false) { false }.last()
        assertEquals(CategoryScanStatus.DENIED, denied.status)
        assertTrue(denied.files.isEmpty())
    }

    @Test
    fun blankQueriesDoNotScanAndResultLimitsRemainExplicit() = runBlocking {
        val root = temporary.newFolder("storage")
        write(root, "match-one.txt")
        write(root, "match-two.txt")
        val index = StorageCategoryIndex(maxResults = 1)
        assertThrows(IllegalArgumentException::class.java) {
            index.search("  ", listOf(volume(root)), false) { true }
        }
        val limited = index.search("match", listOf(volume(root)), false) { true }.last()
        assertEquals(1, limited.files.size)
        assertEquals(CategoryScanStatus.LIMITED, limited.status)
    }

    @Test
    fun cancellingAQueryDoesNotPublishACompleteSnapshot() = runBlocking {
        val root = temporary.newFolder("storage")
        repeat(1_000) { write(root, "nested/match-$it.txt") }
        val states = mutableListOf<CategoryIndexState>()
        val job = launch {
            StorageCategoryIndex().search("match", listOf(volume(root)), false) { true }.collect {
                states += it
                if (it.files.isNotEmpty()) cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(states.none { it.status == CategoryScanStatus.COMPLETE })
        assertTrue(states.last().files.size < 1_000)
    }

    private fun write(root: File, relative: String): File = root.resolve(relative).apply {
        parentFile!!.mkdirs()
        writeText("fixture")
    }

    private fun volume(root: File) = StorageVolumeInfo(
        root.name, root.path, isPrimary = false, isRemovable = false, state = "mounted",
    )
}
