package com.voyagerfiles.ui.screens

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Browser : Screen("browser/{path}") {
        fun createRoute(path: String): String =
            "browser/${URLEncoder.encode(path, "UTF-8")}"
    }
    data object Connections : Screen("connections")
    data object Settings : Screen("settings")
}

@Composable
fun AppNavigation(viewModel: FileBrowserViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToBrowser = { path ->
                    viewModel.navigateTo(path)
                    navController.navigate(Screen.Browser.createRoute(path))
                },
                onNavigateToConnections = {
                    navController.navigate(Screen.Connections.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
            )
        }

        composable(
            route = Screen.Browser.route,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { backStackEntry ->
            val path = URLDecoder.decode(
                backStackEntry.arguments?.getString("path") ?: "/storage/emulated/0",
                "UTF-8",
            )
            BrowserScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
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

        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
