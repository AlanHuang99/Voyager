package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.TrashEntry
import com.voyagerfiles.ui.screens.TrashEntryRow
import java.io.File
import org.junit.Rule
import org.junit.Test

class SelectionAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun selectionControlsDescribeTheirTargetAndAction() {
        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    FileListItem(
                        file = FileItem(name = "report.txt", path = "/report.txt", isDirectory = false),
                        isSelected = false,
                        isSelectionMode = true,
                        onClick = {},
                        onLongClick = {},
                    )
                    FileGridItem(
                        file = FileItem(name = "photo.png", path = "/photo.png", isDirectory = false),
                        isSelected = true,
                        isSelectionMode = true,
                        onClick = {},
                        onLongClick = {},
                    )
                    TrashEntryRow(
                        entry = TrashEntry(
                            id = "entry",
                            originalPath = "/old.txt",
                            displayName = "old.txt",
                            isDirectory = false,
                            deletedAt = 0L,
                            entryDirectory = File("/entry"),
                            payload = File("/entry/payload"),
                        ),
                        selected = false,
                        enabled = true,
                        onToggle = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Select report.txt").assertExists()
        composeTestRule.onNodeWithContentDescription("Deselect photo.png").assertExists()
        composeTestRule.onNodeWithContentDescription("Select old.txt").assertExists()
    }
}
