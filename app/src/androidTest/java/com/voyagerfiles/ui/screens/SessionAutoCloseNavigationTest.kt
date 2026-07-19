package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.SessionAutoCloseTimeout
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionAutoCloseNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var preferences: PreferencesManager
    private lateinit var root: File
    private lateinit var returningRoot: File

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        preferences = PreferencesManager(application)
        runBlocking {
            preferences.setAutoCloseSessions(true)
            preferences.setSessionAutoCloseTimeout(SessionAutoCloseTimeout.FIVE_MINUTES)
        }
        root = File(application.cacheDir, "session-auto-close-navigation-test").apply {
            deleteRecursively()
            mkdirs()
        }
        returningRoot = File(application.cacheDir, "session-auto-close-returning-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        returningRoot.deleteRecursively()
        runBlocking {
            preferences.setAutoCloseSessions(false)
            preferences.setSessionAutoCloseTimeout(SessionAutoCloseTimeout.FIFTEEN_MINUTES)
        }
    }

    @Test
    fun automaticClosureReturnsAnOpenBrowserToHome() {
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {
            MaterialTheme {
                AppNavigation(
                    viewModel = viewModel,
                    hasAllFilesAccess = true,
                    onRequestAllFilesAccess = {},
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.autoCloseSessions.value
        }
        composeTestRule.runOnIdle { viewModel.openLocalRoot(root.absolutePath) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.sessions.value.size == 1 && !viewModel.browseState.value.isLoading
        }
        composeTestRule.onNodeWithText(root.name).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        val previousGeneration = viewModel.sessionClosureGeneration.value

        composeTestRule.runOnIdle {
            viewModel.onAppBackgrounded(nowMillis = 1_000L)
            viewModel.onAppForegrounded(
                nowMillis = 1_000L + SessionAutoCloseTimeout.FIVE_MINUTES.durationMillis,
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.sessionClosureGeneration.value == previousGeneration + 1L
        }
        composeTestRule.onNodeWithText("Voyager").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun sessionCreatedByReturningPickerResultRemainsOpen() {
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {
            MaterialTheme {
                AppNavigation(
                    viewModel = viewModel,
                    hasAllFilesAccess = true,
                    onRequestAllFilesAccess = {},
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.autoCloseSessions.value
        }
        composeTestRule.runOnIdle { viewModel.openLocalRoot(root.absolutePath) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.sessions.value.size == 1 && !viewModel.browseState.value.isLoading
        }
        composeTestRule.onNodeWithText(root.name).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        val previousGeneration = viewModel.sessionClosureGeneration.value

        composeTestRule.runOnIdle {
            viewModel.onAppBackgrounded(nowMillis = 1_000L)
            viewModel.openLocalRoot(returningRoot.absolutePath)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.sessions.value.size == 2 &&
                viewModel.activeSession.value?.rootPath == returningRoot.absolutePath &&
                !viewModel.browseState.value.isLoading
        }
        composeTestRule.runOnIdle {
            viewModel.onAppForegrounded(
                nowMillis = 1_000L + SessionAutoCloseTimeout.FIVE_MINUTES.durationMillis,
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.sessionClosureGeneration.value == previousGeneration + 1L &&
                viewModel.sessions.value.singleOrNull()?.rootPath == returningRoot.absolutePath
        }
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeTestRule.onNodeWithText("Voyager").assertDoesNotExist()
    }
}
