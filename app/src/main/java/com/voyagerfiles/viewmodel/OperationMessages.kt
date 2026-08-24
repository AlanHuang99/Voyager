package com.voyagerfiles.viewmodel

import androidx.annotation.StringRes
import com.voyagerfiles.R
import com.voyagerfiles.data.archive.ArchiveConflictException
import com.voyagerfiles.ui.text.UiText
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object OperationMessages {
    fun failure(@StringRes action: Int, error: Throwable): UiText = UiText.Resource(
        R.string.operation_failed,
        listOf(UiText.Resource(action), reason(error)),
    )

    fun partial(
        failed: Int,
        total: Int,
        @StringRes action: Int,
        error: Throwable,
    ): UiText = UiText.Plural(
        R.plurals.partial_operation_failed,
        total,
        listOf(failed, total, UiText.Resource(action), reason(error)),
    )

    fun reason(error: Throwable): UiText {
        val causes = generateSequence(error) { it.cause }.toList()
        val conflict = causes.firstOrNull {
            it is DestinationConflictException || it is ArchiveConflictException
        }
        return when {
            causes.filterIsInstance<UiTextException>().firstOrNull() != null ->
                causes.filterIsInstance<UiTextException>().first().uiText
            conflict != null -> UiText.Resource(
                R.string.error_conflict,
                listOf(UiText.Dynamic(conflict.message.orEmpty())),
            )
            causes.any { it is SecurityException } ->
                UiText.Resource(R.string.error_permission_denied)
            causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
                UiText.Resource(R.string.error_server_unavailable)
            causes.any { it is SocketTimeoutException } ->
                UiText.Resource(R.string.error_connection_timeout)
            causes.any { it is FileNotFoundException } ->
                UiText.Resource(R.string.error_item_unavailable)
            else -> error.message?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(UiText::Dynamic)
                ?: UiText.Resource(R.string.unknown_error)
        }
    }
}

class UiTextException(val uiText: UiText) : IllegalArgumentException()
