package com.voyagerfiles.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.TrashEntry
import com.voyagerfiles.data.repository.LocalTrashManager
import com.voyagerfiles.ui.screens.TrashEntryRow
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MediaThumbnailRenderingTest {
    // Queue recomposition onto the test clock instead of preview IO callbacks.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "preview-rendering-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun cleanup() {
        MediaThumbnailLoader.clear()
        directory.deleteRecursively()
    }

    @Test
    fun videoAndOfficeAppearInListCompactAndGrid() {
        val video = previewVideo(directory).previewItem()
        val office = previewOffice(directory).previewItem()
        compose.setContent {
            MaterialTheme {
                Column {
                    for (item in listOf(video, office)) {
                        FileListItem(item, false, false, {}, {})
                        FileListItem(item, false, false, {}, {}, compact = true)
                        FileGridItem(item, false, false, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            count(VIDEO_THUMBNAIL_TEST_TAG) == 3 && count(OFFICE_THUMBNAIL_TEST_TAG) == 3
        }
    }

    @Test
    fun remoteVideoAndMissingOfficePreviewKeepIcons() {
        val remote = previewVideo(directory).previewItem().copy(source = FileSource.WEBDAV)
        val noPreview = previewOffice(directory, thumbnail = null).previewItem()
        compose.setContent {
            MaterialTheme {
                Column {
                    FileThumbnailOrIcon(remote, 40.dp)
                    FileThumbnailOrIcon(noPreview, 40.dp)
                }
            }
        }
        compose.waitForIdle()
        assertEquals(2, count(FILE_ICON_TEST_TAG))
        assertEquals(0, count(VIDEO_THUMBNAIL_TEST_TAG))
        assertEquals(0, count(OFFICE_THUMBNAIL_TEST_TAG))
    }

    @Test
    fun trashUsesPayloadWithOriginalNameThenDropsRestoredPreview() = runBlocking {
        val original = previewVideo(directory)
        val manager = LocalTrashManager(listOf(directory))
        val entry = manager.moveToTrash(original.path).getOrThrow()
        assertFalse(original.exists())
        assertEquals("payload", entry.payload.name)
        assertTrue(entry.previewFile().isVideo)
        val shown = mutableStateOf<TrashEntry?>(entry)
        compose.setContent {
            MaterialTheme {
                shown.value?.let { TrashEntryRow(it, false, true, {}) }
            }
        }
        compose.waitUntil(10_000) { count(VIDEO_THUMBNAIL_TEST_TAG) == 1 }
        compose.onNodeWithText(original.name).assertExists()
        manager.restore(entry).getOrThrow()
        compose.runOnIdle { shown.value = null }
        compose.onNodeWithTag(VIDEO_THUMBNAIL_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
        assertTrue(original.exists())
        assertFalse(entry.payload.exists())
        assertTrue(MediaThumbnailLoader.load(context, entry.previewFile(), 32, 32).isFailure)
        assertTrue(MediaThumbnailLoader.load(context, original.previewItem(), 32, 32).isSuccess)
    }

    @Test
    fun trashOfficePreviewDisappearsAfterPermanentDeletion() = runBlocking {
        val manager = LocalTrashManager(listOf(directory))
        val entry = manager.moveToTrash(previewOffice(directory).path).getOrThrow()
        val shown = mutableStateOf<TrashEntry?>(entry)
        compose.setContent {
            MaterialTheme { shown.value?.let { TrashEntryRow(it, false, true, {}) } }
        }
        compose.waitUntil(10_000) { count(OFFICE_THUMBNAIL_TEST_TAG) == 1 }
        manager.deletePermanently(entry).getOrThrow()
        compose.runOnIdle { shown.value = null }
        compose.onNodeWithTag(OFFICE_THUMBNAIL_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
        assertTrue(MediaThumbnailLoader.load(context, entry.previewFile(), 32, 32).isFailure)
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
}
