package com.voyagerfiles.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ConnectionProtocolKeyboardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dpadSelectsProtocolUpdatesPortAndRestoresFocus() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        composeTestRule.setContent {
            MaterialTheme { ConnectionDialog(onDismiss = {}, onSave = {}) }
        }
        composeTestRule.onNodeWithText("SFTP").performSemanticsAction(SemanticsActions.RequestFocus)
        composeTestRule.onNodeWithText("SFTP").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        composeTestRule.onNodeWithText("FTP").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        composeTestRule.onNodeWithText("FTP").assertIsFocused().performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        composeTestRule.onNodeWithText("21").assertExists()
        composeTestRule.onNodeWithText("FTP").assertIsFocused()
        composeTestRule.onNodeWithText("FTP").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        composeTestRule.onNodeWithText("WebDAV").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeTestRule.onNodeWithText("WebDAV").assertDoesNotExist()
        composeTestRule.onNodeWithText("FTP").assertIsFocused()
        composeTestRule.onNodeWithText("21").assertExists()
        composeTestRule.onNodeWithText("FTP").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeTestRule.onNodeWithText("Host").assertIsFocused()
    }
}
