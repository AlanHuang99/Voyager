package com.voyagerfiles.duplicates

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.ui.screens.Screen
import com.voyagerfiles.viewmodel.TransferOperationController
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.ui.screens.DuplicatesScreen
import com.voyagerfiles.viewmodel.DuplicateViewModel
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DuplicatesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val root = File(app.cacheDir, "duplicates-${UUID.randomUUID()}").apply { mkdirs() }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun explicitSelectionKeepsOneCopyAndPermanentRemovalRechecksIt() {
        val a = root.resolve("a.txt").apply { writeText("same") }
        val b = root.resolve("b.txt").apply { writeText("same") }
        root.resolve("different.txt").writeText("diff")
        val vm = DuplicateViewModel(app)
        compose.setContent { MaterialTheme { DuplicatesScreen(root.path, {}, vm) } }
        compose.onNodeWithText("Remove selected (0)").assertIsNotEnabled()
        compose.onNodeWithText("Scan folder").performClick()
        compose.waitUntil(10_000) { vm.state.value.result != null }
        assertTrue(vm.state.value.selected.isEmpty())
        compose.onNodeWithText("b.txt").performClick()
        compose.onNodeWithText("a.txt").performClick()
        assertEquals(setOf(b.canonicalPath), vm.state.value.selected)
        compose.onNodeWithText("Remove selected (1)").performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        compose.waitUntil(10_000) { !vm.state.value.removing && !b.exists() }
        assertEquals("same", a.readText())
        assertEquals("diff", root.resolve("different.txt").readText())
        assertTrue(vm.state.value.result!!.groups.isEmpty())
    }

    @Test fun missingKeeperPreventsRemovalOnTheDevice() {
        val a = root.resolve("a.txt").apply { writeText("same") }
        val b = root.resolve("b.txt").apply { writeText("same") }
        val vm = DuplicateViewModel(app)
        compose.setContent { MaterialTheme { DuplicatesScreen(root.path, {}, vm) } }
        compose.onNodeWithText("Scan folder").performClick()
        compose.waitUntil(10_000) { vm.state.value.result != null }
        compose.onNodeWithText("b.txt").performClick()
        a.delete()
        compose.onNodeWithText("Remove selected (1)").performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        compose.waitUntil(10_000) { !vm.state.value.removing && vm.state.value.message != null }
        assertEquals("same", b.readText())
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test fun pathWithSpacesAndPlusRoundTripsThroughNavigationAndSystemBackRefreshes() {
        val folder = root.resolve("My Files+more").apply { mkdirs() }
        var returned = false
        var receivedPath: String? = null
        compose.setContent {
            val nav = rememberNavController()
            MaterialTheme {
                NavHost(navController = nav, startDestination = "start") {
                    composable("start") {
                        Text("Browser fixture")
                        LaunchedEffect(Unit) { if (!returned) nav.navigate(Screen.Duplicates.createRoute(folder.path)) }
                    }
                    composable(Screen.Duplicates.route, arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                        receivedPath = entry.arguments!!.getString("path")!!
                        DuplicatesScreen(receivedPath!!, { returned = true; nav.popBackStack() })
                    }
                }
            }
        }
        compose.waitUntil(5_000) { receivedPath != null }
        assertEquals(folder.path, receivedPath)
        compose.onNodeWithText("Scan folder").assertExists()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5_000) { returned }
        compose.onNodeWithText("Browser fixture").assertExists()
    }

    @Test fun foregroundStartFailureAllowsBackAndRetryWithoutRemovingFiles() {
        val a = root.resolve("a.txt").apply { writeText("same") }
        val b = root.resolve("b.txt").apply { writeText("same") }
        val vm = DuplicateViewModel(app, TransferOperationController(startForeground = { error("Foreground start failed") }))
        var returned = false
        compose.setContent { MaterialTheme { DuplicatesScreen(root.path, { returned = true }, vm) } }
        compose.onNodeWithText("Scan folder").performClick()
        compose.waitUntil(10_000) { vm.state.value.result != null }
        compose.onNodeWithText("b.txt").performClick()
        compose.onNodeWithText("Remove selected (1)").performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        compose.waitUntil(5_000) { vm.state.value.message != null }
        assertFalse(vm.state.value.removing)
        assertEquals("same", a.readText())
        assertEquals("same", b.readText())
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5_000) { returned }
    }
}
