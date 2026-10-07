package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.R
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RemoteDownloadPreferenceTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val preferences = PreferencesManager(application)
    private val store = ViewModelStore()
    private var originalConfirmation = true

    @Before fun setUp() = runBlocking {
        originalConfirmation = preferences.confirmRemoteDownloads.first()
        preferences.setConfirmRemoteDownloads(true)
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        runBlocking { preferences.setConfirmRemoteDownloads(originalConfirmation) }
    }

    @Test fun namedSettingIsOneToggleAndItsChoicePersists() {
        val viewModel = FileBrowserViewModel(application)
        store.put("settings", viewModel)
        compose.setContent {
            MaterialTheme { SettingsScreen(viewModel, {}, hasAllFilesAccess = true, onRequestAllFilesAccess = {}) }
        }
        val setting = compose.onNode(hasText(application.getString(R.string.settings_confirm_remote_downloads)) and isToggleable())
        setting.performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { !viewModel.confirmRemoteDownloads.value }
        setting.assertIsOff()
        assertFalse(runBlocking { PreferencesManager(application).confirmRemoteDownloads.first() })
    }
}
