package com.voyagerfiles.ui.components

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.archive.ArchiveExtractionReport
import com.voyagerfiles.data.archive.FailedEntry
import com.voyagerfiles.data.archive.RenamedEntry
import com.voyagerfiles.data.model.FileItem
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** Details must stay usable however many problems an extraction reports, in either orientation. */
class ArchiveReportDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun restoreOrientation() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun aHundredProblemsInPortrait() = aHundredProblems(landscape = false)

    @Test fun aHundredProblemsInLandscape() = aHundredProblems(landscape = true)

    @Test fun tenThousandRenamesInPortrait() = tenThousandRenames(landscape = false)

    @Test fun tenThousandRenamesInLandscape() = tenThousandRenames(landscape = true)

    private fun aHundredProblems(landscape: Boolean) {
        val report = report(
            notExtracted = (1..100).map { FailedEntry("photos/2024/broken-%03d.jpg".format(it), IOException("invalid block type")) },
            renamed = (1..50).map { RenamedEntry("docs/Report-$it.pdf", "Report-$it (1).pdf") },
        )
        var open by mutableStateOf(true)
        show(landscape) { if (open) ArchiveReportDialog(report, onDismiss = { open = false }) }
        val orientation = if (landscape) "landscape" else "portrait"

        compose.onNodeWithText("Done").assertIsDisplayed()
        screenshot("details-100-$orientation-top")
        val last = "docs/Report-50.pdf → Report-50 (1).pdf"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(last))
        compose.onNodeWithText(last).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        screenshot("details-100-$orientation-end")

        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertFalse(open)
    }

    private fun tenThousandRenames(landscape: Boolean) {
        val report = report(
            notExtracted = emptyList(),
            renamed = (1..ArchiveExtractionReport.MAX_LISTED).map { RenamedEntry("files/Image-$it.png", "Image-$it (1).png") },
            renamedCount = 10_000,
        )
        show(landscape) { ArchiveReportDialog(report, onDismiss = {}) }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("and 9800 more"))
        compose.onNodeWithText("and 9800 more").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        screenshot("details-10000-${if (landscape) "landscape" else "portrait"}-end")
    }

    /** Rotating recreates the activity, so the orientation is set before the content. */
    private fun show(landscape: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        compose.waitForIdle()
        compose.setContent { MaterialTheme { content() } }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: return
        directory.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun report(
        notExtracted: List<FailedEntry>,
        renamed: List<RenamedEntry>,
        renamedCount: Int = renamed.size,
    ) = ArchiveExtractionReport(
        root = FileItem("photos_extracted", "/storage/emulated/0/Download/photos_extracted", isDirectory = true),
        extractedEntries = 900,
        extractedFiles = 900,
        totalEntries = 1_000,
        renamed = renamed,
        notExtracted = notExtracted,
        renamedCount = renamedCount,
        complete = true,
    )
}
