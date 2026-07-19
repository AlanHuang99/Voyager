package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.SessionAutoCloseTimeout
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionAutoCloseTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var root: File
    private lateinit var preferences: PreferencesManager

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        preferences = PreferencesManager(application)
        runBlocking {
            preferences.setAutoCloseSessions(false)
            preferences.setSessionAutoCloseTimeout(SessionAutoCloseTimeout.FIVE_MINUTES)
        }
        root = File(application.cacheDir, "session-auto-close-test").apply {
            deleteRecursively()
            mkdirs()
            resolve("notes.txt").writeText("notes")
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        runBlocking {
            preferences.setAutoCloseSessions(false)
            preferences.setSessionAutoCloseTimeout(SessionAutoCloseTimeout.FIFTEEN_MINUTES)
        }
    }

    @Test
    fun expiredBackgroundIntervalClosesSessionsAndProviderBackedState() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {}
        composeTestRule.runOnIdle {
            viewModel.openLocalRoot(root.absolutePath)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.sessions.value.size == 1 &&
                viewModel.browseState.value.currentPath == root.absolutePath &&
                !viewModel.browseState.value.isLoading
        }
        composeTestRule.runOnIdle {
            viewModel.copyToClipboard(listOf(root.resolve("notes.txt").absolutePath))
            viewModel.setAutoCloseSessions(true)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.autoCloseSessions.value
        }
        val previousGeneration = viewModel.sessionClosureGeneration.value

        composeTestRule.runOnIdle {
            viewModel.onAppBackgrounded(nowMillis = 1_000L)
            viewModel.onAppForegrounded(
                nowMillis = 1_000L + SessionAutoCloseTimeout.FIVE_MINUTES.durationMillis,
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.sessions.value.isEmpty() &&
                viewModel.sessionClosureGeneration.value == previousGeneration + 1L
        }
        assertNull(viewModel.activeSession.value)
        assertTrue(viewModel.clipboardPaths.value.isEmpty())
        assertEquals(ClipboardOperation.NONE, viewModel.clipboardOperation.value)
        assertNull(viewModel.snackbarMessage.value)
        assertEquals(FileSource.LOCAL, viewModel.browseState.value.source)
        assertTrue(viewModel.browseState.value.files.isEmpty())
    }

    @Test
    fun expiredIntervalWaitsForAnActiveOperationToFinish() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {}
        composeTestRule.runOnIdle {
            viewModel.openLocalRoot(root.absolutePath)
            viewModel.setAutoCloseSessions(true)
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.autoCloseSessions.value &&
                viewModel.sessions.value.size == 1 &&
                !viewModel.browseState.value.isLoading
        }
        val previousGeneration = viewModel.sessionClosureGeneration.value

        composeTestRule.runOnIdle {
            viewModel.createFile("completed-before-close.txt")
            assertTrue(viewModel.operationState.value is OperationState.Running)
            viewModel.onAppBackgrounded(nowMillis = 1_000L)
            viewModel.onAppForegrounded(
                nowMillis = 1_000L + SessionAutoCloseTimeout.FIVE_MINUTES.durationMillis,
            )
            assertEquals(1, viewModel.sessions.value.size)
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.operationState.value == OperationState.Idle &&
                viewModel.sessions.value.isEmpty() &&
                viewModel.sessionClosureGeneration.value == previousGeneration + 1L
        }
        assertTrue(root.resolve("completed-before-close.txt").exists())
    }
}
