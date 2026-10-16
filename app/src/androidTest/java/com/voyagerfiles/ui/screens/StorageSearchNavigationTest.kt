package com.voyagerfiles.ui.screens

import android.os.Build
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.voyagerfiles.app.MainActivity
import com.voyagerfiles.app.VoyagerApp
import com.voyagerfiles.data.model.FileSource
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class StorageSearchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun realActivitySearchOpensTheChosenLocalFolderAndReturnsToSearch() {
        assumeTrue("Full storage access is required for the shared-storage navigation fixture", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        val fixture = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "voyager-search-${UUID.randomUUID()}")
        val needle = "voyager-search-${UUID.randomUUID()}"
        val firstFolder = fixture.resolve("one").apply { mkdirs() }
        val chosenFolder = fixture.resolve("two").apply { mkdirs() }
        val first = firstFolder.resolve("$needle.txt").apply { writeText("first") }
        val chosen = chosenFolder.resolve("$needle.txt").apply { writeText("chosen") }
        val browser = (compose.activity.application as VoyagerApp).browserViewModel
        try {
            compose.runOnIdle { browser.openLocalRoot(firstFolder.canonicalPath) }
            compose.waitUntil(10_000) { browser.browseState.value.currentPath == firstFolder.canonicalPath }
            val originalSession = browser.activeSession.value?.id
            compose.onNodeWithContentDescription("Search shared storage").performClick()
            compose.onNodeWithTag("storage-search-query").performTextInput(needle)
            compose.onNodeWithTag("storage-search-query").performImeAction()
            compose.waitUntil(45_000) { compose.onAllNodesWithText("2 results").fetchSemanticsNodes().isNotEmpty() }
            val chosenPath = chosen.canonicalPath
            compose.onNodeWithTag("storage-search-results").performScrollToNode(hasContentDescription("Open folder for $chosenPath"))
            compose.onNodeWithContentDescription("Open folder for $chosenPath").performClick()
            compose.waitUntil(10_000) {
                browser.browseState.value.currentPath == chosenFolder.canonicalPath && !browser.browseState.value.isLoading
            }
            compose.onNodeWithText("$needle.txt").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(FileSource.LOCAL, browser.browseState.value.source)
                assertEquals(chosenFolder.canonicalPath, browser.activeSession.value?.rootPath)
                assertNotEquals(originalSession, browser.activeSession.value?.id)
            }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("storage-search-query").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Search shared storage").assertIsDisplayed()
            assertEquals("first", first.readText())
            assertEquals("chosen", chosen.readText())
        } finally {
            fixture.deleteRecursively()
        }
    }
}
