package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.BrowseState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * DataStore re-emits every mapped preference flow on any edit. The browser must not reload the
 * directory when a preference it does not depend on changes; that reload was visible as a flash
 * behind the Sessions sheet and cost a listing round trip on remote sessions.
 */
class PreferenceWriteReloadTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val preferences = PreferencesManager(application)
    private val store = ViewModelStore()
    private lateinit var root: File
    private lateinit var viewModel: FileBrowserViewModel
    private var originalAutoClose = false

    @Before fun setUp() {
        root = File(application.cacheDir, "preference-write-reload").apply {
            deleteRecursively()
            mkdirs()
        }
        originalAutoClose = runBlocking { preferences.autoCloseSessions.first() }
        onMain {
            viewModel = FileBrowserViewModel(application)
            store.put("browser", viewModel)
        }
    }

    @After fun tearDown() {
        onMain { store.clear() }
        runBlocking { preferences.setAutoCloseSessions(originalAutoClose) }
        root.deleteRecursively()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun awaitState(predicate: (BrowseState) -> Boolean) = runBlocking {
        withTimeout(10_000) { viewModel.browseState.first(predicate) }
    }

    @Test fun unrelatedPreferenceChangeDoesNotReloadTheDirectory() {
        onMain { viewModel.openLocalRoot(root.path) }
        awaitState { it.currentPath == root.path && !it.isLoading }
        // A file created after the load can only show up if something reloads the directory.
        File(root, "late.txt").writeText("late")

        runBlocking {
            preferences.setAutoCloseSessions(!originalAutoClose)
            preferences.setAutoCloseSessions(originalAutoClose)
            delay(1_000)
        }
        awaitState { !it.isLoading }
        assertFalse(viewModel.browseState.value.files.any { it.name == "late.txt" })

        // Sanity check that the file would have appeared on a real reload.
        onMain { viewModel.refresh() }
        awaitState { state -> state.files.any { it.name == "late.txt" } }
    }
}
