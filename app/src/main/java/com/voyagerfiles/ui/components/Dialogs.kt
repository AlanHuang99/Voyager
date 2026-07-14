package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.remote.sftp.SshKeyGenerator
import com.voyagerfiles.util.FileNameValidationResult
import com.voyagerfiles.util.FileNameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CreateItemDialog(
    isDirectory: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val validation = FileNameValidator.validate(name)
    val validationError = (validation as? FileNameValidationResult.Invalid)?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isDirectory) "New Folder" else "New File") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                isError = validationError != null,
                supportingText = validationError?.let { message -> { Text(message) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    (validation as? FileNameValidationResult.Valid)?.let { onCreate(it.name) }
                },
                enabled = validation is FileNameValidationResult.Valid,
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val validation = FileNameValidator.validate(name)
    val validatedName = (validation as? FileNameValidationResult.Valid)?.name
    val validationError = (validation as? FileNameValidationResult.Invalid)?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("New name") },
                isError = validationError != null,
                supportingText = validationError?.let { message -> { Text(message) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { validatedName?.let(onRename) },
                enabled = validatedName != null && validatedName != currentName,
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun DeleteConfirmDialog(
    model: DeleteDialogModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.title) },
        text = { Text(model.message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(model.confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDialog(
    existingConnection: RemoteConnection? = null,
    onDismiss: () -> Unit,
    onSave: (RemoteConnection) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existingConnection?.name ?: "") }
    var protocol by remember { mutableStateOf(existingConnection?.protocol ?: ConnectionProtocol.SFTP) }
    var host by remember { mutableStateOf(existingConnection?.host ?: "") }
    var port by remember { mutableStateOf(existingConnection?.port?.toString() ?: protocol.defaultPort.toString()) }
    var username by remember { mutableStateOf(existingConnection?.username ?: "") }
    var password by remember { mutableStateOf(existingConnection?.password ?: "") }
    var privateKeyPath by remember { mutableStateOf(existingConnection?.privateKeyPath ?: "") }
    var keyGenerationMessage by remember { mutableStateOf<String?>(null) }
    var isGeneratingKey by remember { mutableStateOf(false) }
    var remotePath by remember { mutableStateOf(existingConnection?.remotePath ?: "/") }
    var shareName by remember { mutableStateOf(existingConnection?.shareName ?: "") }
    var domain by remember { mutableStateOf(existingConnection?.domain ?: "") }
    var protocolExpanded by remember { mutableStateOf(false) }
    var useTls by remember { mutableStateOf(existingConnection?.useTls ?: true) }
    var showCleartextConfirmation by remember { mutableStateOf(false) }
    val validation = ConnectionFormValidator.validate(protocol, host, port, shareName)
    val transportWarning = connectionTransportWarning(protocol, useTls)

    fun connectionFromFields(): RemoteConnection = RemoteConnection(
        id = existingConnection?.id ?: 0,
        name = name.trim().ifBlank { "${host.trim()} (${protocol.displayName})" },
        protocol = protocol,
        host = host.trim(),
        port = checkNotNull(port.toIntOrNull()),
        useTls = useTls,
        username = username.trim(),
        password = password,
        privateKeyPath = privateKeyPath.trim().ifBlank { null },
        remotePath = remotePath.trim().ifBlank { "/" },
        shareName = shareName.trim().ifBlank { null },
        domain = domain.trim().ifBlank { null },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingConnection != null) "Edit Connection" else "New Connection") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = protocolExpanded,
                    onExpandedChange = { protocolExpanded = !protocolExpanded },
                ) {
                    OutlinedTextField(
                        value = protocol.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Protocol") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = protocolExpanded,
                        onDismissRequest = { protocolExpanded = false },
                    ) {
                        ConnectionProtocol.entries.forEach { proto ->
                            DropdownMenuItem(
                                text = { Text(proto.displayName) },
                                onClick = {
                                    protocol = proto
                                    port = proto.defaultPort.toString()
                                    if (proto == ConnectionProtocol.WEBDAV) useTls = true
                                    protocolExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    isError = validation.hostError != null,
                    supportingText = validation.hostError?.let { message -> { Text(message) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (protocol == ConnectionProtocol.FTP) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "FTP sends credentials and files without transport encryption",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (protocol == ConnectionProtocol.WEBDAV) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use HTTPS")
                            Text(
                                if (useTls) "Encrypted WebDAV connection" else "HTTP sends credentials without transport encryption",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (useTls) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                        Switch(checked = useTls, onCheckedChange = { useTls = it })
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    isError = validation.portError != null,
                    supportingText = validation.portError?.let { message -> { Text(message) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (protocol == ConnectionProtocol.SFTP) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = privateKeyPath,
                        onValueChange = { privateKeyPath = it },
                        label = { Text("Private key path (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isGeneratingKey = true
                                keyGenerationMessage = null
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        SshKeyGenerator.generateToDirectory(
                                            directory = File(context.filesDir, "ssh"),
                                            baseName = SshKeyGenerator.safeKeyBaseName(
                                                host.ifBlank { name }.ifBlank { username },
                                            ),
                                            comment = listOf(username, host)
                                                .filter { it.isNotBlank() }
                                                .joinToString("@")
                                                .ifBlank { "voyager" },
                                        )
                                    }
                                }.fold(
                                    onSuccess = { generated ->
                                        privateKeyPath = generated.privateKeyFile.absolutePath
                                        keyGenerationMessage = "Public key saved to ${generated.publicKeyFile.absolutePath}"
                                    },
                                    onFailure = { error ->
                                        keyGenerationMessage = "Key generation failed: ${error.message ?: "unknown error"}"
                                    },
                                )
                                isGeneratingKey = false
                            }
                        },
                        enabled = !isGeneratingKey,
                    ) {
                        Icon(Icons.Filled.VpnKey, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isGeneratingKey) "Generating..." else "Generate key")
                    }
                    keyGenerationMessage?.let { message ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(message)
                    }
                }

                if (protocol == ConnectionProtocol.SMB) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = shareName,
                        onValueChange = { shareName = it },
                        label = { Text("Share Name") },
                        isError = validation.shareNameError != null,
                        supportingText = validation.shareNameError?.let { message -> { Text(message) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("Domain (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text("Remote Path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (transportWarning != null) {
                        showCleartextConfirmation = true
                    } else {
                        onSave(connectionFromFields())
                    }
                },
                enabled = validation.isValid,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (showCleartextConfirmation) {
        val warning = checkNotNull(transportWarning)
        AlertDialog(
            onDismissRequest = { showCleartextConfirmation = false },
            title = { Text(warning.title) },
            text = { Text(warning.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleartextConfirmation = false
                        onSave(connectionFromFields())
                    },
                ) { Text(warning.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showCleartextConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}
