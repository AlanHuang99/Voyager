package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteFileTapActionTest {
    @Test
    fun webDavFilesStreamAndOtherProtocolsDownload() {
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("song.mp3", FileSource.WEBDAV)))
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("movie.mp4", FileSource.WEBDAV)))
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("notes.txt", FileSource.WEBDAV)))
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("report.pdf", FileSource.WEBDAV)))
        assertEquals(RemoteFileTapAction.DOWNLOAD, remoteFileTapAction(file("song.mp3", FileSource.SFTP)))
        assertEquals(
            RemoteFileTapAction.NAVIGATE,
            FileItem("Music", "/Music", isDirectory = true, source = FileSource.WEBDAV).let(::remoteFileTapAction),
        )
    }

    private fun file(name: String, source: FileSource) = FileItem(
        name = name,
        path = "/$name",
        isDirectory = false,
        source = source,
    )
}
