package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.ui.theme.AppTheme
import com.voyagerfiles.ui.theme.DarkColors
import com.voyagerfiles.ui.theme.VoyagerTheme
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationBackgroundTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun darkNavigationCrossfadeKeepsHostBackgroundDark() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            VoyagerTheme(appTheme = AppTheme.DARK) {
                AppNavigation(
                    viewModel = viewModel,
                    hasAllFilesAccess = false,
                    onRequestAllFilesAccess = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.mainClock.advanceTimeBy(350L)

        val image = composeTestRule.onRoot().captureToImage()
        val edgePixel = image.toPixelMap()[1, image.height / 2]
        assertColorNear(DarkColors.background, edgePixel)
    }

    private fun assertColorNear(expected: Color, actual: Color) {
        val tolerance = 0.03f
        assertTrue(
            "Expected $expected but captured $actual at the navigation host edge",
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance,
        )
    }
}
