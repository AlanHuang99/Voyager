package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource

internal enum class RemoteFileTapAction {
    NAVIGATE,
    STREAM_WEBDAV,
    CONFIRM_DOWNLOAD,
    DOWNLOAD,
}

internal fun remoteFileTapAction(file: FileItem, confirmDownloads: Boolean = true): RemoteFileTapAction = when {
    file.isDirectory -> RemoteFileTapAction.NAVIGATE
    file.source == FileSource.WEBDAV -> RemoteFileTapAction.STREAM_WEBDAV
    confirmDownloads -> RemoteFileTapAction.CONFIRM_DOWNLOAD
    else -> RemoteFileTapAction.DOWNLOAD
}
