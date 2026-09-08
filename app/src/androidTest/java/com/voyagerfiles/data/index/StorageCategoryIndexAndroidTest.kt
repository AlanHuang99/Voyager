package com.voyagerfiles.data.index

import android.content.Context
import android.app.Application
import android.graphics.Bitmap
import android.os.Environment
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.util.StorageVolumeInfo
import com.voyagerfiles.viewmodel.StorageCategoryViewModel
import java.io.File
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class StorageCategoryIndexAndroidTest {
    @Test fun liveMountedStorageScanAndViewModelHandleRemovalAndAccessLoss() = runBlocking {
        assumeTrue("Live all-files access case requires Android 11 or later", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assumeTrue("Run with MANAGE_EXTERNAL_STORAGE allowed", Environment.isExternalStorageManager())
        val application = ApplicationProvider.getApplicationContext<Application>()
        val folder = File(Environment.getExternalStorageDirectory(), "VoyagerCategoryTest-${System.nanoTime()}")
        val viewModel = StorageCategoryViewModel(application)
        assertTrue(folder.mkdirs())
        try {
            val source = File(folder, "unique-category-fixture.jpg").apply { writeText("fixture") }
            withContext(Dispatchers.Main) { viewModel.refresh(StorageCategory.IMAGES, false, true) }
            val found = withTimeout(30_000) { viewModel.state.first { state -> state.files.any { it.path == source.canonicalPath } } }
            val result = found.files.first { it.path == source.canonicalPath }
            withContext(Dispatchers.Main) {
                viewModel.cancel()
                assertTrue(viewModel.validate(result))
                assertTrue(source.delete())
                assertFalse(viewModel.validate(result))
                assertEquals(1, viewModel.state.value.staleEntries)
                assertTrue(viewModel.state.value.files.none { it.path == result.path })
                viewModel.refresh(StorageCategory.IMAGES, false, false)
                assertEquals(CategoryScanStatus.DENIED, viewModel.state.value.status)
                assertTrue(viewModel.state.value.files.isEmpty())
            }
        } finally {
            withContext(Dispatchers.Main) { viewModel.cancel() }
            assertTrue(folder.deleteRecursively())
        }
    }

    @Test fun deviceFilesAcrossDirectoriesAreClassifiedAndStaleResultsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.getExternalFilesDir(null), "category-index-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        try {
            val folderA = File(root, "a").apply { mkdirs() }
            val folderB = File(root, "b/nested").apply { mkdirs() }
            val photo = File(folderA, "PHOTO.JPG")
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            File(folderB, "movie.mp4").outputStream().use { output ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("previews/red.mp4").use { it.copyTo(output) }
            }
            // Category lookup is extension/MIME based; it does not decode or hash audio payloads.
            File(folderA, "recording.M4A").writeBytes(byteArrayOf(0, 0, 0, 20, 102, 116, 121, 112, 77, 52, 65, 32))
            File(context.applicationInfo.sourceDir).copyTo(File(folderB, "voyager.APK"))
            File(folderB, "notes.TXT").writeText("Category fixture")
            val hidden = File(root, ".hidden").apply { mkdirs() }
            photo.copyTo(File(hidden, "hidden.jpg"))
            val volume = StorageVolumeInfo("Device fixtures", root.path, true, false, StorageVolumeInfo.STATE_MOUNTED)
            val index = StorageCategoryIndex()
            val expected = mapOf(
                StorageCategory.APPS to "voyager.APK",
                StorageCategory.VIDEOS to "movie.mp4",
                StorageCategory.AUDIO to "recording.M4A",
                StorageCategory.IMAGES to "PHOTO.JPG",
                StorageCategory.DOCUMENTS to "notes.TXT",
            )
            for ((category, name) in expected) {
                val state = index.scan(category, listOf(volume), false) { true }.last()
                val result = state.files.single()
                assertEquals(name, result.name)
                assertEquals(1, state.coverage.single().skippedEntries)
                assertEquals(CategoryScanStatus.PARTIAL, state.status)
                assertTrue(index.isCurrent(result, listOf(volume)) { true })
                assertTrue(FileUtils.createOpenFileIntent(context, result).isSuccess)
            }
            val images = index.scan(StorageCategory.IMAGES, listOf(volume), true) { true }.last()
            assertEquals(2, images.files.size)
            val snapshot = images.files.first { it.name == photo.name }
            assertTrue(photo.delete())
            assertFalse(index.isCurrent(snapshot, listOf(volume)) { true })
            assertTrue(index.scan(StorageCategory.IMAGES, listOf(volume), false) { true }.last().files.isEmpty())
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }
}
