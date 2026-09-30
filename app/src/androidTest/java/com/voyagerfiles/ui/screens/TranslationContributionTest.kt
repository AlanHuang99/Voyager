package com.voyagerfiles.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.voyagerfiles.ui.components.TranslationContributionButton
import com.voyagerfiles.ui.components.TranslationNotice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TranslationContributionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dismissingNoticeLeavesPermanentContributionLinkUsable() {
        var opens = 0
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            MaterialTheme {
                Column {
                    if (visible) {
                        TranslationNotice(onImprove = { opens++ }, onDismiss = { visible = false })
                    }
                    TranslationContributionButton(onClick = { opens++ })
                }
            }
        }
        compose.onNodeWithText("This translation is a work in progress.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss translation notice").performClick()
        compose.onNodeWithText("This translation is a work in progress.").assertDoesNotExist()
        compose.onNodeWithText("Help improve translations").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
