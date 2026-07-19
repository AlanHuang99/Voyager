package com.voyagerfiles.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class BrowserCreateMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun remoteMenuShowsCreateAndUploadCommands() {
        composeTestRule.setContent {
            MaterialTheme {
                Box {
                    BrowserCreateMenu(
                        expanded = true,
                        model = BrowserCreateMenuModel.forState(isRemote = true),
                        onDismiss = {},
                        onAction = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("New Folder").assertIsDisplayed()
        composeTestRule.onNodeWithText("New File").assertIsDisplayed()
        composeTestRule.onNodeWithText("Upload Files").assertIsDisplayed()
    }
}
