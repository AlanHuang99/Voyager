package com.voyagerfiles.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.voyagerfiles.util.StorageVolumeInfo
import org.junit.Rule
import org.junit.Test

class HomeStorageSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun unavailableVolumeIsLabeledAndDisabled() {
        composeTestRule.setContent {
            MaterialTheme {
                StorageVolumeCard(
                    volume = StorageVolumeInfo(
                        description = "USB drive",
                        path = null,
                        isPrimary = false,
                        isRemovable = true,
                        state = "unmounted",
                    ),
                    storageInfo = null,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("storage-volume:USB drive").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Unavailable").assertTextContains("Unavailable")
    }
}
