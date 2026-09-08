package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource

internal enum class RemoteFileTapAction {
    NAVIGATE,
    STREAM_WEBDAV,
    DOWNLOAD,
}

internal fun remoteFileTapAction(file: FileItem): RemoteFileTapAction = when {
    file.isDirectory -> RemoteFileTapAction.NAVIGATE
    file.source == FileSource.WEBDAV -> RemoteFileTapAction.STREAM_WEBDAV
    else -> RemoteFileTapAction.DOWNLOAD
}
