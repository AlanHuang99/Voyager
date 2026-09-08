package com.voyagerfiles.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.data.index.StorageCategory
import com.voyagerfiles.R
import com.voyagerfiles.util.FolderShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Browser : Screen("browser/{path}") {
        fun createRoute(path: String): String =
            "browser/${URLEncoder.encode(path, "UTF-8")}"
    }
    data object Duplicates : Screen("duplicates/{path}") {
        fun createRoute(path: String): String = "duplicates/${Uri.encode(path)}"
    }
    data object Category : Screen("category/{category}") {
        fun createRoute(category: StorageCategory): String = "category/${category.name}"
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
    requestedFolder: String? = null,
    folderRequestGeneration: Long = 0,
    onFolderRequestConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var shortcutFailed by remember { mutableStateOf(false) }
    var confirmRoot by remember { mutableStateOf(false) }
    val sessionClosureGeneration by viewModel.sessionClosureGeneration.collectAsState()

    LaunchedEffect(sessionClosureGeneration) {
        if (
            sessionClosureGeneration > 0L &&
            viewModel.sessions.value.isEmpty() &&
            navController.currentDestination?.route in setOf(Screen.Browser.route, Screen.Duplicates.route)
        ) {
            navController.navigateHome()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            val direction = if (targetState.destination.route == Screen.Home.route) {
                AnimatedContentTransitionScope.SlideDirection.End
            } else {
                AnimatedContentTransitionScope.SlideDirection.Start
            }
            slideIntoContainer(direction, tween(300, easing = FastOutSlowInEasing)) { it / 8 } +
                fadeIn(tween(220))
        },
        exitTransition = {
            val direction = if (targetState.destination.route == Screen.Home.route) {
                AnimatedContentTransitionScope.SlideDirection.End
            } else {
                AnimatedContentTransitionScope.SlideDirection.Start
            }
            slideOutOfContainer(direction, tween(300, easing = FastOutSlowInEasing)) { it / 8 } +
                fadeOut(tween(180))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(300, easing = FastOutSlowInEasing),
            ) { it / 8 } + fadeIn(tween(220))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(300, easing = FastOutSlowInEasing),
            ) { it / 8 } + fadeOut(tween(180))
        },
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
                onNavigateToCategory = { navController.navigate(Screen.Category.createRoute(it)) },
            )
        }

        composable(
            route = Screen.Browser.route,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) {
            BrowserScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.navigateHome() },
                onFindDuplicates = { path -> navController.navigate(Screen.Duplicates.createRoute(path)) },
            )
        }

        composable(Screen.Duplicates.route, arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
            val sourceSessionId by rememberSaveable { mutableStateOf(viewModel.activeSession.value?.id) }
            DuplicatesScreen(checkNotNull(entry.arguments?.getString("path")), onNavigateBack = {
                if (viewModel.sessions.value.any { it.id == sourceSessionId }) {
                    viewModel.refresh()
                    navController.popBackStack()
                } else {
                    navController.navigateHome()
                }
            })
        }

        composable(
            route = Screen.Category.route,
            arguments = listOf(navArgument("category") { type = NavType.StringType }),
        ) { entry ->
            val category = StorageCategory.entries.firstOrNull { it.name == entry.arguments?.getString("category") }
            val browseState by viewModel.browseState.collectAsState()
            if (category != null) {
                CategoryScreen(
                    category = category,
                    showHidden = browseState.showHidden,
                    hasAllFilesAccess = hasAllFilesAccess,
                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
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
                onOpenRoot = { confirmRoot = true },
            )
        }
    }
    if (confirmRoot) {
        AlertDialog(
            onDismissRequest = { confirmRoot = false },
            title = { Text(stringResource(R.string.root_title)) },
            text = { Text(stringResource(R.string.root_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoot = false
                    viewModel.openRootSession()
                    navController.navigate(Screen.Browser.createRoute("/"))
                }) { Text(stringResource(R.string.root_open)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRoot = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    LaunchedEffect(requestedFolder, folderRequestGeneration, hasAllFilesAccess) {
        if (requestedFolder != null && hasAllFilesAccess) {
            val folder = try {
                withContext(Dispatchers.IO) { FolderShortcuts.resolve(context, requestedFolder) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                shortcutFailed = true
                onFolderRequestConsumed()
                return@LaunchedEffect
            }
            withContext(Dispatchers.Main.immediate) {
                viewModel.openLocalRoot(folder.path)
                if (navController.currentDestination?.route != Screen.Browser.route) {
                    navController.navigate(Screen.Browser.createRoute(folder.path)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                }
                shortcutFailed = false
                onFolderRequestConsumed()
            }
        }
    }
    if (shortcutFailed) {
        AlertDialog(
            onDismissRequest = { shortcutFailed = false },
            text = { Text(stringResource(R.string.shortcut_folder_unavailable)) },
            confirmButton = {
                TextButton(onClick = { shortcutFailed = false }) { Text(stringResource(R.string.action_done)) }
            },
        )
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
