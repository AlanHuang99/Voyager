package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowserBackNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var root: File

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        root = File(application.cacheDir, "browser-back-navigation-test").apply {
            deleteRecursively()
            resolve("ChildFolder").mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun backReturnsToParentAfterSearchFocusedDirectoryNavigation() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)

        composeTestRule.setContent {
            MaterialTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            viewModel.openLocalRoot(root.absolutePath)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.currentPath == root.absolutePath &&
                !viewModel.browseState.value.isLoading
        }

        composeTestRule.onNode(hasSetTextAction()).performClick().performTextInput("child")
        composeTestRule.onNode(hasText("ChildFolder") and hasClickAction()).performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.currentPath == root.resolve("ChildFolder").absolutePath &&
                !viewModel.browseState.value.isLoading
        }

        pressBack()

        composeTestRule.waitUntil(timeoutMillis = 2_000) {
            viewModel.browseState.value.currentPath == root.absolutePath
        }
        assertEquals(root.absolutePath, viewModel.browseState.value.currentPath)
    }
}
