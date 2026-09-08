package com.voyagerfiles.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.voyagerfiles.R
import com.voyagerfiles.ui.text.UiText
import com.voyagerfiles.viewmodel.OperationState
import com.voyagerfiles.viewmodel.OperationOutcome
import com.voyagerfiles.viewmodel.OperationResult
import com.voyagerfiles.viewmodel.TransferProgress
import org.junit.Rule
import org.junit.Test

class BrowserOperationProgressTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun completedResultShowsFinalCountAndCanBeDismissed() {
        var dismissed = false
        composeTestRule.setContent {
            MaterialTheme {
                OperationResultContent(
                    OperationResult(TransferProgress(UiText.Resource(R.string.progress_copying), 5, 5), OperationOutcome.COMPLETED),
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Completed").assertIsDisplayed()
        composeTestRule.onNodeWithText("5 of 5 completed").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Dismiss operation result").performClick()
        org.junit.Assert.assertTrue(dismissed)
    }

    @Test
    fun failedResultPreservesPartialCount() {
        composeTestRule.setContent {
            MaterialTheme {
                OperationResultContent(
                    OperationResult(TransferProgress(UiText.Resource(R.string.progress_copying), 1, 5), OperationOutcome.FAILED),
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Finished with errors").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 of 5 completed").assertIsDisplayed()
    }

    @Test
    fun displaysDeterminateTransferDetailsAndAccessibleStatus() {
        val progress = TransferProgress(
            label = UiText.Resource(R.string.progress_copying),
            completedItems = 2,
            totalItems = 5,
            currentItemName = "report.pdf",
            copiedBytes = 1_572_864,
            totalBytes = 3_145_728,
            elapsedNanos = 1_500_000_000,
        )
        composeTestRule.setContent {
            MaterialTheme {
                OperationProgressContent(OperationState.Running(progress))
            }
        }

        composeTestRule.onNodeWithText("Copying").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "report.pdf • 2 of 5 completed • 1.5 MB of 3 MB • 50% • 1 MB/s",
        ).assertIsDisplayed()
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Copying, report.pdf, 2 of 5 completed, 1.5 MB of 3 MB, 50%, 1 MB/s",
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun leavesUnknownProgressIndeterminateWithoutInventingPercentage() {
        val progress = TransferProgress(
            label = UiText.Resource(R.string.progress_downloading),
            currentItemName = "unknown.bin",
            copiedBytes = 1_024,
            totalBytes = null,
            elapsedNanos = 1_000_000_000,
        )
        composeTestRule.setContent {
            MaterialTheme {
                OperationProgressContent(OperationState.Running(progress))
            }
        }

        composeTestRule.onNodeWithText("Downloading").assertIsDisplayed()
        composeTestRule.onNodeWithText("unknown.bin • 1 KB • 1 KB/s").assertIsDisplayed()
        composeTestRule.onNodeWithText("0%", substring = true).assertDoesNotExist()
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Downloading, unknown.bin, 1 KB, 1 KB/s",
            ),
        ).assertIsDisplayed()
    }
}
