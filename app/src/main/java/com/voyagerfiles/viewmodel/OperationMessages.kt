package com.voyagerfiles.viewmodel

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object OperationMessages {
    fun failure(action: String, error: Throwable): String = "$action failed: ${reason(error)}"

    fun partial(
        failed: Int,
        total: Int,
        action: String,
        error: Throwable,
    ): String = "$failed of $total item${if (total == 1) "" else "s"} could not be $action. ${reason(error)}"

    fun reason(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.toList()
        val conflict = causes.filterIsInstance<DestinationConflictException>().firstOrNull()
        return when {
            conflict != null -> "${conflict.message}. Rename or remove it, then try again."
            causes.any { it is SecurityException } ->
                "Permission denied. Grant access to this location and try again."
            causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
                "Server unavailable. Check the address and network connection."
            causes.any { it is SocketTimeoutException } ->
                "Connection timed out. Check the server and try again."
            causes.any { it is FileNotFoundException } ->
                "The item is no longer available. Refresh the folder and try again."
            else -> error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown error"
        }
    }
}
