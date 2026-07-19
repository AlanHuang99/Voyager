package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.HomeLayout
import com.voyagerfiles.data.model.HomeSection
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CustomHomeLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var preferences: PreferencesManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        preferences = PreferencesManager(application)
        runBlocking {
            preferences.setHomeLayout(
                HomeLayout.fromPersisted(
                    order = "REMOTE_CONNECTIONS,STORAGE,ACTIVE_SESSIONS,QUICK_ACCESS,BOOKMARKS,FOLDERS",
                    hidden = "QUICK_ACCESS",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking { preferences.setHomeLayout(HomeLayout.DEFAULT) }
    }

    @Test
    fun homeUsesPersistedSectionVisibilityAndOrder() {
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {
            MaterialTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToBrowser = {},
                    onNavigateToSession = { _, _ -> },
                    onNavigateToConnections = {},
                    onNavigateToTrash = {},
                    onNavigateToSettings = {},
                    onOpenSafTree = {},
                    hasAllFilesAccess = true,
                    onRequestAllFilesAccess = {},
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.homeLayout.value.visibleSections.first() == HomeSection.REMOTE_CONNECTIONS
        }

        composeTestRule.onNodeWithText("Quick Access").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        val remoteTop = composeTestRule.onNodeWithText("Remote Connections")
            .fetchSemanticsNode().boundsInRoot.top
        val storageTop = composeTestRule.onNodeWithText("Storage")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("Remote Connections should render before Storage", remoteTop < storageTop)
    }
}
