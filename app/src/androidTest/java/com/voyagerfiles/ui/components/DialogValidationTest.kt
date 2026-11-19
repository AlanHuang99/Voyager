package com.voyagerfiles.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class DialogValidationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newItemWaitsForEditingButStillRequiresAValidName() {
        compose.setContent { MaterialTheme { CreateItemDialog(true, {}, {}) } }
        compose.onNodeWithText("Enter a name").assertDoesNotExist()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("folder")
        compose.onNodeWithText("Create").assertIsEnabled()
        compose.onNodeWithText("Name").performTextClearance()
        compose.onNodeWithText("Enter a name").assertExists()
        compose.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test fun newConnectionWaitsForHostEditingAndRetainsValidation() {
        compose.setContent { MaterialTheme { ConnectionDialog(onDismiss = {}, onSave = {}) } }
        compose.onNodeWithText("Host is required").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Host").performTextInput("example.invalid")
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Host").performTextClearance()
        compose.onNodeWithText("Host is required").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun emptyArchiveNameWaitsForEditing() {
        compose.setContent { MaterialTheme { ArchiveNameDialog("", {}, {}) } }
        compose.onNodeWithText("Enter a name").assertDoesNotExist()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNodeWithText("Archive name").performTextInput("backup.zip")
        compose.onNodeWithText("Create").assertIsEnabled()
        compose.onNodeWithText("Archive name").performTextClearance()
        compose.onNodeWithText("Enter a name").assertExists()
        compose.onNodeWithText("Create").assertIsNotEnabled()
    }
}
