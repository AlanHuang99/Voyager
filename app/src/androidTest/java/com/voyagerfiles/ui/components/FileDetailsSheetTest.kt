package com.voyagerfiles.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.ui.theme.VoyagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileDetailsSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun detailsShowsAvailableMetadata() {
        composeTestRule.setContent {
            VoyagerTheme {
                FileDetailsSheet(
                    file = FileItem(
                        name = "report.pdf",
                        path = "/storage/emulated/0/Documents/report.pdf",
                        isDirectory = false,
                        size = 2048,
                        owner = "media_rw",
                        permissions = "rw-r--r--",
                    ),
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 KB").assertIsDisplayed()
        composeTestRule.onNodeWithText("/storage/emulated/0/Documents/report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("media_rw").assertIsDisplayed()
        composeTestRule.onNodeWithText("rw-r--r--").performScrollTo().assertIsDisplayed()
    }
}
