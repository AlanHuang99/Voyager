package com.voyagerfiles.app

import android.os.Environment
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.voyagerfiles.util.FolderShortcuts
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 30)
class FolderShortcutLaunchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<VoyagerApp>()
    private lateinit var root: File

    @Before fun setup() {
        root = File(app.getExternalFilesDir(null), "shortcut-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun opensFolderAndHandlesRepeatedShortcutLaunchAndRecreation() {
        org.junit.Assume.assumeTrue(Environment.isExternalStorageManager())
        val second = root.resolve("second").apply { mkdirs(); resolve("inside.txt").writeText("inside") }
        ActivityScenario.launch<MainActivity>(FolderShortcuts.launchIntent(app, root.path)).use { scenario ->
            waitForPath(root.path)
            scenario.onActivity { it.startActivity(FolderShortcuts.launchIntent(it, second.path)) }
            waitForPath(second.path)
            compose.onNodeWithText("inside.txt").assertExists()
            scenario.recreate()
            compose.onNodeWithText("inside.txt").assertExists()
            scenario.onActivity { it.startActivity(FolderShortcuts.launchIntent(it, root.path)) }
            waitForPath(root.path)
            compose.onNodeWithText("second").assertExists()
        }
    }

    @Test fun missingFolderShowsRecoveryMessage() {
        org.junit.Assume.assumeTrue(Environment.isExternalStorageManager())
        ActivityScenario.launch<MainActivity>(FolderShortcuts.launchIntent(app, root.resolve("removed").path)).use {
            compose.onNodeWithText("This folder is no longer available. Check that the storage is connected and the folder still exists.").assertExists()
        }
    }

    @Test fun deniedStorageAccessDoesNotOpenShortcut() {
        org.junit.Assume.assumeFalse(Environment.isExternalStorageManager())
        ActivityScenario.launch<MainActivity>(FolderShortcuts.launchIntent(app, root.path)).use {
            compose.onNodeWithText("Grant full storage access to open this folder shortcut.").assertExists()
        }
    }

    private fun waitForPath(path: String) = compose.waitUntil(10_000) {
        val state = app.browserViewModel.browseState.value
        state.currentPath == File(path).canonicalPath && !state.isLoading
    }

}
