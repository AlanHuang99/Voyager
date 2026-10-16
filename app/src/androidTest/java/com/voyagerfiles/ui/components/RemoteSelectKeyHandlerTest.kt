package com.voyagerfiles.ui.components

import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.model.FileItem
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class RemoteSelectKeyHandlerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun listShortPressCallsClick() {
        val events = renderItem(grid = false)

        composeTestRule.onNodeWithTag(TARGET_TAG).performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.DirectionCenter)
            keyUp(androidx.compose.ui.input.key.Key.DirectionCenter)
        }

        composeTestRule.runOnIdle { assertEquals(listOf("click"), events) }
    }

    @Test
    fun gridShortPressCallsClick() {
        val events = renderItem(grid = true)

        composeTestRule.onNodeWithTag(TARGET_TAG).performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.Enter)
            keyUp(androidx.compose.ui.input.key.Key.Enter)
        }

        composeTestRule.runOnIdle { assertEquals(listOf("click"), events) }
    }

    @Test
    fun listHeldPressCallsLongClickOnce() {
        val events = renderItem(grid = false)

        dispatchHeldCenterPress()

        composeTestRule.runOnIdle { assertEquals(listOf("long"), events) }
    }

    @Test
    fun gridHeldPressCallsLongClickOnce() {
        val events = renderItem(grid = true)

        dispatchHeldCenterPress()

        composeTestRule.runOnIdle { assertEquals(listOf("long"), events) }
    }

    @Test
    fun dpadRightReachesTrailingButtonAndCenterPressesIt() {
        val events = renderItem(grid = false, withTrailingButton = true)

        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeTestRule.onNodeWithTag(TRAILING_TAG).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)

        composeTestRule.runOnIdle { assertEquals(listOf("menu"), events) }
    }

    @Test
    fun dpadLeftFromTrailingButtonReturnsToRow() {
        val events = renderItem(grid = false, withTrailingButton = true)

        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeTestRule.onNodeWithTag(TRAILING_TAG).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        composeTestRule.onNodeWithTag(TARGET_TAG).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)

        composeTestRule.runOnIdle { assertEquals(listOf("click"), events) }
    }

    @Test
    fun rowWithTrailingButtonStillHandlesShortAndHeldPress() {
        val events = renderItem(grid = false, withTrailingButton = true)

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        dispatchHeldCenterPress()

        composeTestRule.runOnIdle { assertEquals(listOf("click", "long"), events) }
    }

    private fun sendKey(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        instrumentation.sendKeySync(KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, keyCode, 0))
        composeTestRule.waitForIdle()
    }

    private fun renderItem(grid: Boolean, withTrailingButton: Boolean = false): MutableList<String> {
        val events = Collections.synchronizedList(mutableListOf<String>())
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        composeTestRule.setContent {
            MaterialTheme {
                val modifier = Modifier.testTag(TARGET_TAG)
                if (grid) {
                    FileGridItem(
                        file = FILE,
                        isSelected = false,
                        isSelectionMode = false,
                        onClick = { events += "click" },
                        onLongClick = { events += "long" },
                        enableRemoteSelect = true,
                        modifier = modifier,
                    )
                } else {
                    FileListItem(
                        file = FILE,
                        isSelected = false,
                        isSelectionMode = false,
                        onClick = { events += "click" },
                        onLongClick = { events += "long" },
                        enableRemoteSelect = true,
                        modifier = modifier,
                        trailingContent = if (withTrailingButton) {
                            {
                                IconButton(
                                    onClick = { events += "menu" },
                                    modifier = Modifier.testTag(TRAILING_TAG),
                                ) {
                                    Icon(Icons.Filled.MoreVert, "More")
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag(TARGET_TAG)
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
        return events
    }

    private fun dispatchHeldCenterPress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(
            KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0),
        )
        instrumentation.sendKeySync(
            KeyEvent(downTime, downTime + 600, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1),
        )
        instrumentation.sendKeySync(
            KeyEvent(downTime, downTime + 650, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0),
        )
        composeTestRule.waitForIdle()
    }

    private companion object {
        const val TARGET_TAG = "remote-select-target"
        const val TRAILING_TAG = "remote-select-trailing"
        val FILE = FileItem(
            name = "notes.txt",
            path = "/notes.txt",
            isDirectory = false,
        )
    }
}
