package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.voyagerfiles.data.remote.sftp.SftpKnownHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun SftpHostKeysButton(host: String, port: Int?) {
    val context = LocalContext.current
    val store = remember(context) { SftpKnownHosts(File(context.filesDir, "ssh/known_hosts")) }
    var showKeys by remember(host, port) { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showKeys = true },
        enabled = host.isNotBlank() && port != null && port in 1..65535,
    ) { Text(stringResource(R.string.sftp_host_keys_title)) }
    if (showKeys && port != null) {
        SftpHostKeysDialog(host.trim(), port, store, onDismiss = { showKeys = false })
    }
}

@Composable
internal fun SftpHostKeysDialog(
    host: String,
    port: Int,
    knownHosts: SftpKnownHosts,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fingerprints by remember(host, port) { mutableStateOf<List<SftpKnownHosts.Fingerprint>>(emptyList()) }
    var busy by remember(host, port) { mutableStateOf(true) }
    var failed by remember(host, port) { mutableStateOf(false) }
    var confirmForget by remember(host, port) { mutableStateOf(false) }
    val endpoint = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    suspend fun refresh(forget: Boolean = false) {
        busy = true
        failed = false
        try {
            fingerprints = withContext(Dispatchers.IO) {
                if (forget) knownHosts.forget(host, port)
                knownHosts.fingerprints(host, port)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }

    LaunchedEffect(host, port, knownHosts) { refresh() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sftp_host_keys_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(endpoint)
                when {
                    busy -> Text(stringResource(R.string.sftp_host_keys_loading))
                    failed -> Text(stringResource(R.string.sftp_host_keys_failed), color = MaterialTheme.colorScheme.error)
                    fingerprints.isEmpty() -> Text(stringResource(R.string.sftp_host_keys_empty))
                    else -> {
                        Text(stringResource(R.string.sftp_host_keys_instructions))
                        fingerprints.forEach { fingerprint ->
                            SelectionContainer {
                                Text("${fingerprint.algorithm}\n${fingerprint.sha256}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirmForget = true },
                enabled = !busy && !failed && fingerprints.isNotEmpty(),
            ) { Text(stringResource(R.string.sftp_host_keys_forget_saved)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_done)) }
        },
    )
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.sftp_host_keys_forget_title)) },
            text = { Text(stringResource(R.string.sftp_host_keys_forget_warning, endpoint)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch { refresh(forget = true) }
                }) { Text(stringResource(R.string.sftp_host_keys_forget), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
