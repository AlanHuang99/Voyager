package com.voyagerfiles.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.remote.sftp.SshKeyGenerator
import com.voyagerfiles.ui.text.asString
import com.voyagerfiles.ui.text.UiText
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
    val validationErrorRes = (validation as? FileNameValidationResult.Invalid)?.messageRes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (isDirectory) R.string.create_new_folder else R.string.create_new_file))
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.dialog_name)) },
                isError = validationErrorRes != null,
                supportingText = validationErrorRes?.let { messageRes ->
                    { Text(stringResource(messageRes)) }
                },
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
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
    val validationErrorRes = (validation as? FileNameValidationResult.Invalid)?.messageRes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.dialog_new_name)) },
                isError = validationErrorRes != null,
                supportingText = validationErrorRes?.let { messageRes ->
                    { Text(stringResource(messageRes)) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { validatedName?.let(onRename) },
                enabled = validatedName != null && validatedName != currentName,
            ) { Text(stringResource(R.string.action_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        title = { Text(model.title.asString()) },
        text = { Text(model.message.asString()) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(model.confirmLabel.asString()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
    var keyGenerationMessage by remember { mutableStateOf<UiText?>(null) }
    var generatedPublicKey by remember { mutableStateOf<String?>(null) }
    var generatedPublicKeyFileName by remember { mutableStateOf("id_voyager_key.pub") }
    var pendingPublicKeySave by remember { mutableStateOf<String?>(null) }
    var isGeneratingKey by remember { mutableStateOf(false) }
    var remotePath by remember { mutableStateOf(existingConnection?.remotePath ?: "/") }
    var shareName by remember { mutableStateOf(existingConnection?.shareName ?: "") }
    var domain by remember { mutableStateOf(existingConnection?.domain ?: "") }
    var protocolExpanded by remember { mutableStateOf(false) }
    var useTls by remember { mutableStateOf(existingConnection?.useTls ?: true) }
    var showCleartextConfirmation by remember { mutableStateOf(false) }
    val validation = ConnectionFormValidator.validate(protocol, host, port, shareName)
    val transportWarning = connectionTransportWarning(protocol, useTls)
    val publicKeySaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val publicKey = pendingPublicKeySave
        pendingPublicKeySave = null
        if (uri != null && publicKey != null) {
            coroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "w")) {
                            context.getString(R.string.connection_selected_document_open_failed)
                        }.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(publicKey)
                            writer.newLine()
                        }
                    }
                }.fold(
                    onSuccess = {
                        keyGenerationMessage = UiText.Resource(R.string.connection_public_key_saved)
                    },
                    onFailure = { error ->
                        keyGenerationMessage = UiText.Resource(
                            R.string.connection_public_key_save_failed,
                            listOf(
                                error.message?.let(UiText::Dynamic)
                                    ?: UiText.Resource(R.string.unknown_error),
                            ),
                        )
                    },
                )
            }
        }
    }

    fun connectionFromFields(): RemoteConnection = RemoteConnection(
        id = existingConnection?.id ?: 0,
        name = name.trim().ifBlank { "${host.trim()} (${context.getString(protocol.displayNameRes)})" },
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
        title = {
            Text(
                stringResource(
                    if (existingConnection != null) R.string.dialog_edit_connection
                    else R.string.dialog_new_connection,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.connection_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = protocolExpanded,
                    onExpandedChange = { protocolExpanded = !protocolExpanded },
                ) {
                    OutlinedTextField(
                        value = stringResource(protocol.displayNameRes),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.connection_protocol)) },
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
                                text = { Text(stringResource(proto.displayNameRes)) },
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
                    label = { Text(stringResource(R.string.connection_host)) },
                    isError = validation.hostErrorRes != null,
                    supportingText = validation.hostErrorRes?.let { messageRes ->
                        { Text(stringResource(messageRes)) }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (protocol == ConnectionProtocol.FTP) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.connection_ftp_warning),
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
                            Text(stringResource(R.string.connection_use_https))
                            Text(
                                stringResource(
                                    if (useTls) R.string.connection_webdav_encrypted
                                    else R.string.connection_webdav_http_warning,
                                ),
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
                    label = { Text(stringResource(R.string.connection_port)) },
                    isError = validation.portErrorRes != null,
                    supportingText = validation.portErrorRes?.let { messageRes ->
                        { Text(stringResource(messageRes)) }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.connection_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.connection_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (protocol == ConnectionProtocol.SFTP) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = privateKeyPath,
                        onValueChange = { privateKeyPath = it },
                        label = { Text(stringResource(R.string.connection_private_key_path)) },
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
                                        generatedPublicKey = generated.publicKey
                                        generatedPublicKeyFileName = generated.publicKeyFile.name
                                        keyGenerationMessage = UiText.Resource(R.string.connection_private_key_created)
                                    },
                                    onFailure = { error ->
                                        keyGenerationMessage = UiText.Resource(
                                            R.string.connection_key_generation_failed,
                                            listOf(
                                                error.message?.let(UiText::Dynamic)
                                                    ?: UiText.Resource(R.string.unknown_error),
                                            ),
                                        )
                                    },
                                )
                                isGeneratingKey = false
                            }
                        },
                        enabled = !isGeneratingKey,
                    ) {
                        Icon(Icons.Filled.VpnKey, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (isGeneratingKey) R.string.connection_generating_key
                                else R.string.connection_generate_key,
                            ),
                        )
                    }
                    keyGenerationMessage?.let { message ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(message.asString())
                    }
                }

                if (protocol == ConnectionProtocol.SMB) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = shareName,
                        onValueChange = { shareName = it },
                        label = { Text(stringResource(R.string.connection_share_name)) },
                        isError = validation.shareNameErrorRes != null,
                        supportingText = {
                            Text(
                                stringResource(
                                    validation.shareNameErrorRes ?: R.string.smb_share_supporting,
                                ),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text(stringResource(R.string.connection_domain)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text(stringResource(R.string.connection_remote_path)) },
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
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (showCleartextConfirmation) {
        val warning = checkNotNull(transportWarning)
        AlertDialog(
            onDismissRequest = { showCleartextConfirmation = false },
            title = { Text(warning.title.asString()) },
            text = { Text(warning.message.asString()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleartextConfirmation = false
                        onSave(connectionFromFields())
                    },
                ) { Text(warning.confirmLabel.asString()) }
            },
            dismissButton = {
                TextButton(onClick = { showCleartextConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    generatedPublicKey?.let { publicKey ->
        GeneratedPublicKeyDialog(
            publicKey = publicKey,
            onCopy = {
                runCatching {
                    val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            context.getString(R.string.connection_public_key_clip_label),
                            publicKey,
                        ),
                    )
                }.fold(
                    onSuccess = {
                        keyGenerationMessage = UiText.Resource(R.string.connection_public_key_copied)
                    },
                    onFailure = { error ->
                        keyGenerationMessage = UiText.Resource(
                            R.string.connection_public_key_copy_failed,
                            listOf(
                                error.message?.let(UiText::Dynamic)
                                    ?: UiText.Resource(R.string.unknown_error),
                            ),
                        )
                    },
                )
            },
            onSave = {
                pendingPublicKeySave = publicKey
                publicKeySaveLauncher.launch(generatedPublicKeyFileName)
            },
            onDismiss = { generatedPublicKey = null },
        )
    }
}

@Composable
internal fun GeneratedPublicKeyDialog(
    publicKey: String,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_install_public_key)) },
        text = {
            Column {
                Text(stringResource(R.string.dialog_public_key_instructions))
                Spacer(modifier = Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        text = publicKey,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.action_copy)) }
                TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}
