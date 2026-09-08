package com.voyagerfiles.data.index

import com.voyagerfiles.util.StorageVolumeInfo
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageCategoryIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private val index = StorageCategoryIndex()

    @Test fun categoriesFindMixedFilesAcrossFoldersAndMountedReadOnlyVolume() = runBlocking {
        val internal = temporary.newFolder("internal")
        val external = temporary.newFolder("external")
        write(internal, "Download/PHOTO.JPG")
        write(internal, "DCIM/holiday/movie.mp4")
        write(internal, "Pictures/recording.M4A")
        write(external, "misc/second.mp4")
        write(external, "nested/install.APK")
        write(external, "notes/REPORT.TXT")
        write(external, "other/paper.pdf")
        write(external, "other/unknown.bin")
        val roots = listOf(volume(internal), volume(external).copy(state = StorageVolumeInfo.STATE_MOUNTED_READ_ONLY))
        val expected = mapOf(
            StorageCategory.APPS to setOf("install.APK"),
            StorageCategory.VIDEOS to setOf("movie.mp4", "second.mp4"),
            StorageCategory.AUDIO to setOf("recording.M4A"),
            StorageCategory.IMAGES to setOf("PHOTO.JPG"),
            StorageCategory.DOCUMENTS to setOf("REPORT.TXT", "paper.pdf"),
        )
        for ((category, names) in expected) {
            val result = index.scan(category, roots, false) { true }.last()
            assertEquals(names, result.files.map { it.name }.toSet())
            assertEquals(CategoryScanStatus.COMPLETE, result.status)
            assertEquals(8, result.coverage.sumOf { it.filesExamined })
            assertTrue(result.files.all { index.isCurrent(it, roots) { true } })
        }
    }

    @Test fun hiddenLinksAndUnreadableFoldersHaveExplicitPartialCoverage() = runBlocking {
        val root = temporary.newFolder("root")
        write(root, "visible/photo.jpg")
        write(root, ".hidden/secret.jpg")
        write(root, "denied/blocked.jpg")
        Files.createSymbolicLink(root.toPath().resolve("cycle"), root.toPath())
        val guarded = StorageCategoryIndex(canRead = { it.fileName.toString() != "denied" })
        val roots = listOf(volume(root), volume(temporary.root).copy(description = "Disconnected", path = null, state = "unmounted"))
        val result = guarded.scan(StorageCategory.IMAGES, roots, false) { true }.last()
        assertEquals(listOf("photo.jpg"), result.files.map { it.name })
        assertEquals(CategoryScanStatus.PARTIAL, result.status)
        assertEquals(1, result.coverage[0].inaccessibleEntries)
        assertEquals(2, result.coverage[0].skippedEntries)
        assertEquals(CategoryScanStatus.UNAVAILABLE, result.coverage[1].status)
        val includingHidden = guarded.scan(StorageCategory.IMAGES, roots, true) { true }.last()
        assertEquals(setOf("photo.jpg", "secret.jpg"), includingHidden.files.map { it.name }.toSet())
    }

    @Test fun revokedPermissionClearsResultsAndDeniedPermissionNeverReads() = runBlocking {
        val root = temporary.newFolder("root")
        repeat(600) { write(root, "$it.jpg") }
        var checks = 0
        val states = index.scan(StorageCategory.IMAGES, listOf(volume(root)), false) { ++checks < 400 }.toList()
        assertTrue(states.any { it.files.isNotEmpty() })
        assertEquals(CategoryScanStatus.DENIED, states.last().status)
        assertTrue(states.last().files.isEmpty())
        assertTrue(states.last().coverage.isEmpty())
        val denied = StorageCategoryIndex(canRead = { error("Must not inspect storage without permission") })
            .scan(StorageCategory.IMAGES, listOf(volume(root)), false) { false }.last()
        assertEquals(CategoryScanStatus.DENIED, denied.status)
    }

    @Test fun staleFilesAndSymlinkReplacementAreRejected() = runBlocking {
        val root = temporary.newFolder("root")
        val file = write(root, "photo.jpg")
        val roots = listOf(volume(root))
        val snapshot = index.scan(StorageCategory.IMAGES, roots, false) { true }.last().files.single()
        assertTrue(index.isCurrent(snapshot, roots) { true })
        assertFalse(index.isCurrent(snapshot, roots) { false })
        assertFalse(index.isCurrent(snapshot, roots.map { it.copy(state = "unmounted") }) { true })
        file.appendText("changed")
        assertFalse(index.isCurrent(snapshot, roots) { true })
        assertTrue(file.delete())
        assertFalse(index.isCurrent(snapshot, roots) { true })
        val outside = write(temporary.newFolder("outside"), "other.jpg")
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertFalse(index.isCurrent(snapshot, roots) { true })
    }

    @Test fun duplicateRootsAndResultLimitNeverClaimCompleteCoverage() = runBlocking {
        val root = temporary.newFolder("root")
        repeat(4) { write(root, "$it.jpg") }
        val duplicate = index.scan(StorageCategory.IMAGES, listOf(volume(root), volume(root)), false) { true }.last()
        assertEquals(4, duplicate.files.size)
        assertEquals(1, duplicate.coverage[1].skippedEntries)
        val limited = StorageCategoryIndex(maxResults = 2).scan(StorageCategory.IMAGES, listOf(volume(root)), false) { true }.last()
        assertEquals(2, limited.files.size)
        assertEquals(CategoryScanStatus.LIMITED, limited.status)
        assertEquals(CategoryScanStatus.LIMITED, limited.coverage.single().status)
    }

    @Test fun overlappingRootsAreNotIndexedTwiceEvenWhenChildComesFirst() = runBlocking {
        val root = temporary.newFolder("root")
        val nested = write(root, "nested/photo.jpg").parentFile!!
        write(root, "top.jpg")
        for (roots in listOf(listOf(volume(nested), volume(root)), listOf(volume(root), volume(nested)))) {
            val result = index.scan(StorageCategory.IMAGES, roots, false) { true }.last()
            assertEquals(setOf("photo.jpg", "top.jpg"), result.files.map { it.name }.toSet())
            assertEquals(2, result.files.size)
            assertEquals(1, result.coverage.sumOf { it.skippedEntries })
        }
        assertEquals(CategoryScanStatus.UNAVAILABLE, index.scan(StorageCategory.IMAGES, emptyList(), false) { true }.last().status)
    }

    @Test fun depthLimitReportsSkippedFolderWithoutInventingItsContentsCount() = runBlocking {
        val root = temporary.newFolder("root")
        write(root, "visible.jpg")
        write(root, "one/two/deep.jpg")
        val result = StorageCategoryIndex(maxDepth = 2).scan(StorageCategory.IMAGES, listOf(volume(root)), false) { true }.last()
        assertEquals(listOf("visible.jpg"), result.files.map { it.name })
        assertEquals(1, result.coverage.single().skippedEntries)
        assertEquals(1, result.coverage.single().filesExamined)
        assertEquals(CategoryScanStatus.PARTIAL, result.status)
    }

    @Test fun cancellationStopsLargeScanBeforeCompletion() = runBlocking {
        val root = temporary.newFolder("root")
        repeat(2_000) { write(root, "folder/$it.jpg") }
        var wasCancelled = false
        val states = mutableListOf<CategoryIndexState>()
        val job = launch {
            try {
                index.scan(StorageCategory.IMAGES, listOf(volume(root)), false) { true }.collect {
                    states += it
                    if (it.files.isNotEmpty()) cancel()
                }
            } catch (_: CancellationException) {
                wasCancelled = true
            }
        }
        job.join()
        assertTrue(wasCancelled)
        assertTrue(states.none { it.status == CategoryScanStatus.COMPLETE })
        assertTrue(states.last().files.size < 2_000)
    }

    private fun volume(folder: File) = StorageVolumeInfo(folder.name, folder.path, false, true, StorageVolumeInfo.STATE_MOUNTED)

    private fun write(root: File, path: String): File = root.resolve(path).also {
        it.parentFile!!.mkdirs()
        it.writeText("fixture")
    }
}
