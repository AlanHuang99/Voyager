package com.voyagerfiles.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.voyagerfiles.R
import com.voyagerfiles.audio.AudioTone
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource

/** Keep the permission result launcher mounted while the overflow menu is closed. */
@Composable
fun rememberAudioToneAction(
    onInstall: (FileItem, AudioTone) -> Unit,
    onPermissionDenied: () -> Unit,
): (FileItem, AudioTone) -> Unit {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val request = pending
        pending = null
        if (request != null && Settings.System.canWrite(context)) {
            onInstall(FileItem(request[1], request[0], false, source = FileSource.valueOf(request[2])), AudioTone.valueOf(request[3]))
        } else if (request != null) onPermissionDenied()
    }
    return { file, tone ->
        if (Settings.System.canWrite(context)) {
            onInstall(file, tone)
        } else {
            pending = arrayListOf(file.path, file.name, file.source.name, tone.name)
            try {
                launcher.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            } catch (_: android.content.ActivityNotFoundException) {
                pending = null
                onPermissionDenied()
            }
        }
    }
}

@Composable
fun AudioToneMenuItems(file: FileItem, onChoose: (FileItem, AudioTone) -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.audio_set_ringtone)) }, onClick = { onChoose(file, AudioTone.RINGTONE) })
    DropdownMenuItem(text = { Text(stringResource(R.string.audio_set_notification)) }, onClick = { onChoose(file, AudioTone.NOTIFICATION) })
}
