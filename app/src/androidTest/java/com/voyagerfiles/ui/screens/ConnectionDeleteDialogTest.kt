package com.voyagerfiles.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionDeleteDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun destructiveActionRequiresExplicitConfirmation() {
        var confirmed = false
        composeTestRule.setContent {
            MaterialTheme {
                ConnectionDeleteDialog(
                    connectionName = "Home server",
                    onDismiss = {},
                    onConfirm = { confirmed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Delete \"Home server\"? Saved login details will be removed from this device.")
            .assertIsDisplayed()
        assertTrue(!confirmed)

        composeTestRule.onNodeWithText("Delete").performClick()

        assertTrue(confirmed)
    }
}
