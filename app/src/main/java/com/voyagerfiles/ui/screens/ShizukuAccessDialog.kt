package com.voyagerfiles.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.data.shizuku.ShizukuConnection
import com.voyagerfiles.data.shizuku.ShizukuFileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ShizukuAccessDialog(onDismiss: () -> Unit, onConnected: (ShizukuFileProvider) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val unavailable = stringResource(R.string.shizuku_unavailable)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shizuku_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shizuku_description))
                if (connecting) Text(stringResource(R.string.shizuku_connecting))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent == null) error = unavailable
                    else runCatching { context.startActivity(intent) }.onFailure { error = unavailable }
                }) { Text(stringResource(R.string.shizuku_manager)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !connecting, onClick = {
                connecting = true
                error = null
                scope.launch {
                    var provider: ShizukuFileProvider? = null
                    try {
                        provider = ShizukuFileProvider(ShizukuConnection.connect(context))
                        onConnected(provider)
                        provider = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: unavailable }
                    finally { provider?.close(); connecting = false }
                }
            }) { Text(stringResource(R.string.shizuku_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
