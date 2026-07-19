package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.HomeLayout
import com.voyagerfiles.data.model.HomeSection
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HomeLayoutSettingsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var preferences: PreferencesManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        preferences = PreferencesManager(application)
        runBlocking { preferences.setHomeLayout(HomeLayout.DEFAULT) }
    }

    @After
    fun tearDown() {
        runBlocking { preferences.setHomeLayout(HomeLayout.DEFAULT) }
    }

    @Test
    fun editorPersistsVisibilityAndOrderingChanges() {
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
            viewModel.homeLayout.value == HomeLayout.DEFAULT
        }

        composeTestRule.onNodeWithContentDescription("Move Storage up")
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Show Quick access on Home")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            HomeSection.QUICK_ACCESS in viewModel.homeLayout.value.hiddenSections
        }
        composeTestRule.onNodeWithContentDescription("Show Quick access on Home")
            .assertIsOff()
        composeTestRule.onNodeWithContentDescription("Move Folders up")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            val order = viewModel.homeLayout.value.sectionOrder
            order.indexOf(HomeSection.FOLDERS) < order.indexOf(HomeSection.BOOKMARKS)
        }

        val restoredViewModel = FileBrowserViewModel(application)
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            val restored = restoredViewModel.homeLayout.value
            HomeSection.QUICK_ACCESS in restored.hiddenSections &&
                restored.sectionOrder.indexOf(HomeSection.FOLDERS) <
                restored.sectionOrder.indexOf(HomeSection.BOOKMARKS)
        }

        composeTestRule.onNodeWithContentDescription("Reset Home layout")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.homeLayout.value == HomeLayout.DEFAULT
        }
    }
}
