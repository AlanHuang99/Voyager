package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RemoteFileTapActionTest {
    @Test fun tappingRemoteFilesDoesNotStartAnUnconfirmedDownload() {
        for (source in listOf(FileSource.SFTP, FileSource.FTP, FileSource.SMB)) {
            assertNotEquals(RemoteFileTapAction.DOWNLOAD, remoteFileTapAction(file("report.pdf", source)))
        }
    }

    @Test
    fun webDavFilesStreamAndOtherProtocolsDownload() {
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("song.mp3", FileSource.WEBDAV)))
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("movie.mp4", FileSource.WEBDAV)))
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("notes.txt", FileSource.WEBDAV)))
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("report.pdf", FileSource.WEBDAV)))
        assertEquals(RemoteFileTapAction.CONFIRM_DOWNLOAD, remoteFileTapAction(file("song.mp3", FileSource.SFTP)))
        assertEquals(
            RemoteFileTapAction.NAVIGATE,
            FileItem("Music", "/Music", isDirectory = true, source = FileSource.WEBDAV).let(::remoteFileTapAction),
        )
    }

    @Test fun usersCanKeepImmediateDownloadsWithoutChangingNavigationOrWebDavOpening() {
        assertEquals(RemoteFileTapAction.DOWNLOAD, remoteFileTapAction(file("song.mp3", FileSource.SFTP), confirmDownloads = false))
        assertEquals(RemoteFileTapAction.STREAM_WEBDAV, remoteFileTapAction(file("song.mp3", FileSource.WEBDAV), confirmDownloads = false))
        assertEquals(RemoteFileTapAction.NAVIGATE, remoteFileTapAction(FileItem("Music", "/Music", isDirectory = true, source = FileSource.SMB)))
    }

    private fun file(name: String, source: FileSource) = FileItem(
        name = name,
        path = "/$name",
        isDirectory = false,
        source = source,
    )
}
