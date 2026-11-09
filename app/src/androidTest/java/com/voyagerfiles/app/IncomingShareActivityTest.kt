package com.voyagerfiles.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

class IncomingShareActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun incomingShareSurvivesRecreationAndDoesNotReturnAfterCancel() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.voyagerfiles.test.upload/document/zero"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithText("Save here").assertIsNotEnabled()
            scenario.recreate()
            compose.onNodeWithText("Save here").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            scenario.recreate()
            compose.onNodeWithText("Save here").assertDoesNotExist()
        }
    }

    @Test fun launcherIntentKeepsPendingShare() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.voyagerfiles.test.upload/document/zero"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            try {
                compose.onNodeWithText("Save here").assertIsDisplayed()
                scenario.onActivity {
                    it.startActivity(Intent(it, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                compose.waitUntil(10_000) {
                    var received = false
                    scenario.onActivity { received = it.intent.action == Intent.ACTION_MAIN }
                    received
                }
                compose.onNodeWithText("Save here").assertIsDisplayed()
            } finally {
                // ActivityScenario matches lifecycle callbacks against its original launch Intent.
                scenario.onActivity { it.intent = intent }
            }
        }
    }
}
