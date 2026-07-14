package com.voyagerfiles.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.voyagerfiles.data.model.FileItem
import org.junit.Rule
import org.junit.Test

class FileThumbnailTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun localImageRendersWhileThumbnailIsLoadingOrUnavailable() {
        composeTestRule.setContent {
            MaterialTheme {
                FileThumbnailOrIcon(
                    file = FileItem(
                        name = "missing-local-image.jpg",
                        path = "/storage/emulated/0/Pictures/missing-local-image.jpg",
                        isDirectory = false,
                    ),
                    iconSize = 40.dp,
                )
            }
        }

        composeTestRule.waitForIdle()
    }
}
