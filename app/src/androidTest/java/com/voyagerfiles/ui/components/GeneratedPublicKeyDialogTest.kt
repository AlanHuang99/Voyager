package com.voyagerfiles.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class GeneratedPublicKeyDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun exposesGeneratedPublicKeyWithCopyAndSaveActions() {
        val publicKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQTest voyager@test"

        composeTestRule.setContent {
            MaterialTheme {
                GeneratedPublicKeyDialog(
                    publicKey = publicKey,
                    onCopy = {},
                    onSave = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText(publicKey).assertExists()
        composeTestRule.onNodeWithText("Copy").assertExists()
        composeTestRule.onNodeWithText("Save").assertExists()
        composeTestRule.onNodeWithText("Done").assertExists()
    }
}
