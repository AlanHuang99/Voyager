package com.voyagerfiles.ui.components

import com.voyagerfiles.data.model.ConnectionProtocol

data class ConnectionFormValidation(
    val hostError: String? = null,
    val portError: String? = null,
    val shareNameError: String? = null,
) {
    val isValid: Boolean
        get() = hostError == null && portError == null && shareNameError == null
}

object ConnectionFormValidator {
    fun validate(
        protocol: ConnectionProtocol,
        host: String,
        port: String,
        shareName: String,
    ): ConnectionFormValidation {
        val normalizedHost = host.trim()
        val hostError = when {
            normalizedHost.isEmpty() -> "Host is required"
            "://" in normalizedHost || '/' in normalizedHost || normalizedHost.any(Char::isWhitespace) ->
                "Enter a host without a scheme or path"
            else -> null
        }
        val parsedPort = port.toIntOrNull()
        val portError = if (parsedPort == null || parsedPort !in 1..65535) {
            "Enter a port from 1 to 65535"
        } else {
            null
        }
        val shareNameError = if (protocol == ConnectionProtocol.SMB && shareName.isBlank()) {
            "Share name is required for SMB"
        } else {
            null
        }
        return ConnectionFormValidation(hostError, portError, shareNameError)
    }
}

data class ConnectionTransportWarning(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

fun connectionTransportWarning(
    protocol: ConnectionProtocol,
    useTls: Boolean,
): ConnectionTransportWarning? = when {
    protocol == ConnectionProtocol.FTP -> ConnectionTransportWarning(
        title = "Use unencrypted FTP?",
        message = "FTP sends the username, password, and files without transport encryption. Use it only on an isolated trusted network.",
        confirmLabel = "Use FTP",
    )
    protocol == ConnectionProtocol.WEBDAV && !useTls -> ConnectionTransportWarning(
        title = "Use unencrypted HTTP?",
        message = "The WebDAV username, password, and files can be exposed on the network. Use HTTPS unless this is an isolated trusted network.",
        confirmLabel = "Use HTTP",
    )
    else -> null
}
