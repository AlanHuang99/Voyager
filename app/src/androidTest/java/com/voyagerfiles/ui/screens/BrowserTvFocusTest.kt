package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowserTvFocusTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var root: File

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        root = File(application.cacheDir, "browser-tv-focus-test").apply {
            deleteRecursively()
            mkdirs()
            resolve("first.txt").writeText("first")
            resolve("second.txt").writeText("second")
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun listFocusesFirstFileInsteadOfSearch() {
        val viewModel = launchBrowser(ViewMode.LIST)

        waitForBrowser(viewModel, ViewMode.LIST)

        assertFirstFileFocused()
    }

    @Test
    fun gridFocusesFirstFileInsteadOfSearch() {
        val viewModel = launchBrowser(ViewMode.GRID)

        waitForBrowser(viewModel, ViewMode.GRID)

        assertFirstFileFocused()
    }

    private fun launchBrowser(viewMode: ViewMode): FileBrowserViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {
            MaterialTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    isTelevision = true,
                )
            }
        }
        composeTestRule.runOnIdle {
            viewModel.setViewMode(viewMode)
            viewModel.openLocalRoot(root.absolutePath)
        }
        return viewModel
    }

    private fun waitForBrowser(viewModel: FileBrowserViewModel, viewMode: ViewMode) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            val state = viewModel.browseState.value
            state.currentPath == root.absolutePath &&
                !state.isLoading &&
                state.viewMode == viewMode &&
                state.visibleFiles.map { it.name } == listOf("first.txt", "second.txt")
        }
    }

    private fun assertFirstFileFocused() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeTestRule.onNode(hasText("first.txt") and hasClickAction())
                    .assertIsFocused()
            }.isSuccess
        }
        composeTestRule.onNode(hasText("first.txt") and hasClickAction()).assertIsFocused()
        composeTestRule.onNodeWithTag(BROWSER_SEARCH_TEST_TAG).assertIsNotFocused()
    }
}
