package com.voyagerfiles.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.voyagerfiles.viewmodel.OperationState
import com.voyagerfiles.viewmodel.TransferProgress
import org.junit.Rule
import org.junit.Test

class BrowserOperationProgressTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysDeterminateTransferDetailsAndAccessibleStatus() {
        val progress = TransferProgress(
            label = "Copying",
            completedItems = 2,
            totalItems = 5,
            currentItemName = "report.pdf",
            copiedBytes = 40,
            totalBytes = 100,
        )
        composeTestRule.setContent {
            MaterialTheme {
                OperationProgressContent(OperationState.Running(progress))
            }
        }

        composeTestRule.onNodeWithText("Copying").assertIsDisplayed()
        composeTestRule.onNodeWithText("report.pdf • 2 of 5 • 40%").assertIsDisplayed()
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Copying, report.pdf, 2 of 5, 40%",
            )
        ).assertIsDisplayed()
    }

    @Test
    fun leavesUnknownProgressIndeterminateWithoutInventingPercentage() {
        val progress = TransferProgress(
            label = "Moving",
            currentItemName = "folder",
        )
        composeTestRule.setContent {
            MaterialTheme {
                OperationProgressContent(OperationState.Running(progress))
            }
        }

        composeTestRule.onNodeWithText("Moving").assertIsDisplayed()
        composeTestRule.onNodeWithText("folder").assertIsDisplayed()
        composeTestRule.onNodeWithText("0%", substring = true).assertDoesNotExist()
    }
}
