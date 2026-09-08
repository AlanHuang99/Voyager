package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.HomeLayout
import com.voyagerfiles.data.index.CategoryIndexState
import com.voyagerfiles.data.index.CategoryScanStatus
import com.voyagerfiles.data.index.CategoryVolumeCoverage
import com.voyagerfiles.data.index.StorageCategory
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.ui.theme.VoyagerTheme
import com.voyagerfiles.util.StorageVolumeInfo
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CategoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun partialCoveragePathsCancelRefreshAndOpenAreVisible() {
        val file = FileItem("notes.txt", "/storage/example/nested/notes.txt", false, 8)
        val state = mutableStateOf(CategoryIndexState(
            status = CategoryScanStatus.SCANNING,
            files = listOf(file),
            coverage = listOf(CategoryVolumeCoverage(
                StorageVolumeInfo("Test volume", "/storage/example", true, false, "mounted"),
                status = CategoryScanStatus.SCANNING,
                filesExamined = 3,
                inaccessibleEntries = 1,
                skippedEntries = 2,
            )),
        ))
        var refreshed = false
        var cancelled = false
        var opened: FileItem? = null
        compose.setContent {
            VoyagerTheme {
                CategoryContent(
                    StorageCategory.DOCUMENTS, state.value, false, remember { SnackbarHostState() },
                    onRefresh = { refreshed = true },
                    onCancel = { cancelled = true; state.value = state.value.copy(status = CategoryScanStatus.CANCELLED) },
                    onRequestAllFilesAccess = {}, onNavigateBack = {}, onOpenFile = { opened = it },
                )
            }
        }
        compose.onNodeWithText("Files checked: 3 · Inaccessible entries: 1 · Skipped entries: 2").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
        compose.onNodeWithText("Scan cancelled. Results are incomplete.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh").performClick()
        assertTrue(refreshed)
        compose.onNodeWithText("notes.txt").performScrollTo().performClick()
        assertEquals(file, opened)
        compose.onNodeWithText(file.path).assertIsDisplayed()
    }

    @Test fun deniedCategoryOffersPermissionRecovery() {
        var requested = false
        compose.setContent {
            VoyagerTheme {
                CategoryScreen(
                    category = StorageCategory.APPS,
                    showHidden = false,
                    hasAllFilesAccess = false,
                    onRequestAllFilesAccess = { requested = true },
                    onNavigateBack = {},
                )
            }
        }
        compose.onNodeWithText("Full storage access is required to scan categories.").assertIsDisplayed()
        compose.onNodeWithText("Grant full access").performClick()
        assertTrue(requested)
    }

    @Test fun homeCategoryNavigatesToCategoryPermissionRecovery() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = PreferencesManager(application)
        val previous = runBlocking { preferences.homeLayout.first() }
        try {
            runBlocking { preferences.setHomeLayout(HomeLayout.DEFAULT) }
            val browser = FileBrowserViewModel(application)
            compose.setContent {
                VoyagerTheme {
                    AppNavigation(browser, hasAllFilesAccess = false, onRequestAllFilesAccess = {})
                }
            }
            compose.waitUntil(10_000) { browser.homeLayout.value != null }
            compose.onNodeWithText("Apps").performScrollTo().performClick()
            compose.onNodeWithText("Full storage access is required to scan categories.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Quick Access").assertExists()
        } finally {
            runBlocking { preferences.setHomeLayout(previous) }
        }
    }
}
