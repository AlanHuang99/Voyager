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
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.voyagerfiles.ui.screens.AppNavigation
import com.voyagerfiles.ui.screens.PermissionScreen
import com.voyagerfiles.ui.screens.StorageAccessMode
import com.voyagerfiles.ui.screens.storageAccessMode
import com.voyagerfiles.ui.theme.VoyagerTheme
import com.voyagerfiles.viewmodel.FileBrowserViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: FileBrowserViewModel get() = (application as VoyagerApp).browserViewModel
    private val hasStoragePermission = mutableStateOf(false)

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
                    )
                }
            }
        }
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
