package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.SessionAutoCloseTimeout
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionAutoCloseSettingsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var preferences: PreferencesManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        preferences = PreferencesManager(application)
        runBlocking {
            preferences.setAutoCloseSessions(false)
            preferences.setSessionAutoCloseTimeout(SessionAutoCloseTimeout.FIFTEEN_MINUTES)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            preferences.setAutoCloseSessions(false)
            preferences.setSessionAutoCloseTimeout(SessionAutoCloseTimeout.FIFTEEN_MINUTES)
        }
    }

    @Test
    fun settingEnablesAutoCloseAndPersistsSelectedTimeout() {
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    hasAllFilesAccess = false,
                    onRequestAllFilesAccess = {},
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            !viewModel.autoCloseSessions.value &&
                viewModel.sessionAutoCloseTimeout.value == SessionAutoCloseTimeout.FIFTEEN_MINUTES
        }

        composeTestRule.onNodeWithContentDescription("Auto-close sessions")
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.autoCloseSessions.value
        }
        composeTestRule.onNodeWithContentDescription("Session timeout, current 15 minutes")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.onNodeWithText("5 minutes").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.sessionAutoCloseTimeout.value == SessionAutoCloseTimeout.FIVE_MINUTES
        }

        val restoredViewModel = FileBrowserViewModel(application)
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            restoredViewModel.autoCloseSessions.value &&
                restoredViewModel.sessionAutoCloseTimeout.value == SessionAutoCloseTimeout.FIVE_MINUTES
        }
    }
}
