package com.voyagerfiles.ui.components

import androidx.annotation.StringRes
import com.voyagerfiles.R
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.ui.text.UiText

data class ConnectionFormValidation(
    @StringRes val hostErrorRes: Int? = null,
    @StringRes val portErrorRes: Int? = null,
    @StringRes val shareNameErrorRes: Int? = null,
) {
    val isValid: Boolean
        get() = hostErrorRes == null && portErrorRes == null && shareNameErrorRes == null
}

object ConnectionFormValidator {
    fun validate(
        protocol: ConnectionProtocol,
        host: String,
        port: String,
        shareName: String,
    ): ConnectionFormValidation {
        val normalizedHost = host.trim()
        val hostErrorRes = when {
            normalizedHost.isEmpty() -> R.string.validation_host_required
            "://" in normalizedHost || '/' in normalizedHost || normalizedHost.any(Char::isWhitespace) ->
                R.string.validation_host_without_scheme
            else -> null
        }
        val parsedPort = port.toIntOrNull()
        val portErrorRes = if (parsedPort == null || parsedPort !in 1..65535) {
            R.string.validation_port_range
        } else {
            null
        }
        val shareNameErrorRes: Int? = null
        return ConnectionFormValidation(hostErrorRes, portErrorRes, shareNameErrorRes)
    }
}

data class ConnectionTransportWarning(
    val title: UiText,
    val message: UiText,
    val confirmLabel: UiText,
)

fun connectionTransportWarning(
    protocol: ConnectionProtocol,
    useTls: Boolean,
): ConnectionTransportWarning? = when {
    protocol == ConnectionProtocol.FTP -> ConnectionTransportWarning(
        title = UiText.Resource(R.string.warning_ftp_title),
        message = UiText.Resource(R.string.warning_ftp_message),
        confirmLabel = UiText.Resource(R.string.warning_ftp_confirm),
    )
    protocol == ConnectionProtocol.WEBDAV && !useTls -> ConnectionTransportWarning(
        title = UiText.Resource(R.string.warning_http_title),
        message = UiText.Resource(R.string.warning_http_message),
        confirmLabel = UiText.Resource(R.string.warning_http_confirm),
    )
    else -> null
}
