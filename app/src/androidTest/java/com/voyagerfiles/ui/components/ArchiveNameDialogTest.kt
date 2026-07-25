package com.voyagerfiles.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ArchiveNameDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun requiresZipSuffixAndReturnsTrimmedName() {
        var submitted: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                ArchiveNameDialog(
                    initialName = "Archive.zip",
                    onDismiss = {},
                    onCreate = { submitted = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Archive.zip").performTextClearance()
        composeTestRule.onNodeWithText("Archive name").performTextInput("backup")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Name must end with .zip").assertExists()

        composeTestRule.onNodeWithText("Archive name").performTextClearance()
        composeTestRule.onNodeWithText("Archive name").performTextInput("folder\\backup.zip")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Names cannot contain backslashes").assertExists()

        composeTestRule.onNodeWithText("Archive name").performTextClearance()
        composeTestRule.onNodeWithText("Archive name").performTextInput(" backup.zip ")
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()

        assertEquals("backup.zip", submitted)
    }
}
