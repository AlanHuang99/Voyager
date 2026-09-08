package com.voyagerfiles.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.voyagerfiles.viewmodel.OperationState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.voyagerfiles.ui.screens.AppNavigation
import com.voyagerfiles.ui.screens.PermissionScreen
import com.voyagerfiles.ui.screens.StorageAccessMode
import com.voyagerfiles.ui.screens.storageAccessMode
import com.voyagerfiles.ui.theme.VoyagerTheme
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.R
import com.voyagerfiles.util.FolderShortcuts

class MainActivity : ComponentActivity() {

    private val viewModel: FileBrowserViewModel get() = (application as VoyagerApp).browserViewModel
    private val hasStoragePermission = mutableStateOf(false)
    private var folderShortcutPath by mutableStateOf<String?>(null)
    private var folderShortcutGeneration by mutableLongStateOf(0L)

    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val requestLegacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasStoragePermission.value = permissions.values.all { it }
    }

    private val requestManageStorage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasStoragePermission.value = checkStoragePermission()
        folderShortcutPath = if (savedInstanceState?.containsKey("folder_shortcut") == true) {
            savedInstanceState.getString("folder_shortcut")
        } else {
            FolderShortcuts.requestedPath(intent)
        }

        setContent {
            val operation by viewModel.operationState.collectAsState()
            LaunchedEffect(operation is OperationState.Running) {
                if (operation is OperationState.Running && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    val permissionPrefs = getSharedPreferences("notification_permission", MODE_PRIVATE)
                    if (!permissionPrefs.getBoolean("requested", false)) {
                        permissionPrefs.edit().putBoolean("requested", true).apply()
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            val theme by viewModel.theme.collectAsState()
            val permissionGranted by hasStoragePermission
            val limitedAccessAccepted by viewModel.limitedAccessAccepted.collectAsState()

            VoyagerTheme(appTheme = theme) {
                when (storageAccessMode(permissionGranted, limitedAccessAccepted)) {
                    StorageAccessMode.NEEDS_DECISION -> PermissionScreen(
                        onRequestPermission = ::requestStoragePermission,
                        onContinueLimited = { viewModel.setLimitedAccessAccepted(true) },
                    )
                    StorageAccessMode.FULL, StorageAccessMode.LIMITED -> AppNavigation(
                        viewModel = viewModel,
                        hasAllFilesAccess = permissionGranted,
                        onRequestAllFilesAccess = ::requestStoragePermission,
                        requestedFolder = folderShortcutPath,
                        folderRequestGeneration = folderShortcutGeneration,
                        onFolderRequestConsumed = { folderShortcutPath = null },
                    )
                }
                if (folderShortcutPath != null && !permissionGranted) {
                    AlertDialog(
                        onDismissRequest = { folderShortcutPath = null },
                        title = { Text(stringResource(R.string.shortcut_pin_folder)) },
                        text = { Text(stringResource(R.string.shortcut_permission_required)) },
                        confirmButton = {
                            TextButton(onClick = ::requestStoragePermission) {
                                Text(stringResource(R.string.permission_grant_full_access))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { folderShortcutPath = null }) { Text(stringResource(R.string.action_cancel)) }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        folderShortcutPath = FolderShortcuts.requestedPath(intent)
        folderShortcutGeneration++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folder_shortcut", folderShortcutPath)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        hasStoragePermission.value = checkStoragePermission()
        viewModel.onAppForegrounded(SystemClock.elapsedRealtime())
    }

    override fun onStop() {
        viewModel.onAppBackgrounded(SystemClock.elapsedRealtime())
        super.onStop()
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching { requestManageStorage.launch(intent) }
                .onFailure {
                    requestManageStorage.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        } else {
            requestLegacyPermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        }
    }
}
