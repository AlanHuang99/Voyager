package com.voyagerfiles.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.voyagerfiles.R
import com.voyagerfiles.app.MainActivity
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.SearchBarMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowserKeyboardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var prefs: PreferencesManager
    private lateinit var originalSearch: SearchBarMode

    @Before fun setUp() {
        assumeTrue(android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager())
        prefs = PreferencesManager(compose.activity)
        originalSearch = runBlocking { prefs.searchBarMode.first() }
        runBlocking { prefs.setSearchBarMode(SearchBarMode.BOTTOM) }
    }

    @After fun restorePreferences() {
        if (::originalSearch.isInitialized) runBlocking { prefs.setSearchBarMode(originalSearch) }
    }

    @Test fun keyboardLeavesToolbarAndFilesVisibleInTheRealActivity() {
        compose.onNodeWithText("Internal shared storage").performClick()
        val search = compose.onNodeWithTag(BROWSER_SEARCH_TEST_TAG)
        search.assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.content_desc_back)).assertIsDisplayed()
        compose.onNodeWithTag("browser-files").assertIsDisplayed()
        search.assertIsDisplayed()
    }
}
