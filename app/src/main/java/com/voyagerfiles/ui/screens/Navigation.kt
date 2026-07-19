package com.voyagerfiles.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Browser : Screen("browser/{path}") {
        fun createRoute(path: String): String =
            "browser/${URLEncoder.encode(path, "UTF-8")}"
    }
    data object Connections : Screen("connections")
    data object Trash : Screen("trash")
    data object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    viewModel: FileBrowserViewModel,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToBrowser = { path ->
                    viewModel.openLocalRoot(path)
                    navController.navigate(Screen.Browser.createRoute(path))
                },
                onNavigateToSession = { sessionId, path ->
                    viewModel.activateSession(sessionId)
                    navController.navigate(Screen.Browser.createRoute(path))
                },
                onNavigateToConnections = {
                    navController.navigate(Screen.Connections.route)
                },
                onNavigateToTrash = {
                    navController.navigate(Screen.Trash.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onOpenSafTree = { uri: Uri ->
                    viewModel.openSafRoot(uri)
                    navController.navigate(Screen.Browser.createRoute(uri.toString()))
                },
                hasAllFilesAccess = hasAllFilesAccess,
                onRequestAllFilesAccess = onRequestAllFilesAccess,
            )
        }

        composable(
            route = Screen.Browser.route,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) {
            BrowserScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.navigateHome() },
            )
        }

        composable(Screen.Connections.route) {
            ConnectionsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onConnected = {
                    navController.navigate(Screen.Browser.createRoute("/")) {
                        popUpTo(Screen.Home.route)
                    }
                },
            )
        }

        composable(Screen.Trash.route) {
            TrashScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                hasAllFilesAccess = hasAllFilesAccess,
                onRequestAllFilesAccess = onRequestAllFilesAccess,
            )
        }
    }
}

private fun NavHostController.navigateHome() {
    navigate(Screen.Home.route) {
        popUpTo(Screen.Home.route) {
            inclusive = false
        }
        launchSingleTop = true
    }
}
