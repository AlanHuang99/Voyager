package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import org.junit.Assert.assertEquals
import org.junit.Test

class FileRowMenuModelTest {

    @Test
    fun localFileOffersEverySingleSelectionActionInMenuOrder() {
        assertEquals(
            listOf(
                FileRowAction.OPEN_WITH,
                FileRowAction.SHARE,
                FileRowAction.COPY,
                FileRowAction.CUT,
                FileRowAction.RENAME,
                FileRowAction.HIDE,
                FileRowAction.COMPRESS_TO_ZIP,
                FileRowAction.DETAILS,
                FileRowAction.DELETE,
            ),
            FileRowMenuModel.forFile(file("notes.txt"), isRemote = false).actions,
        )
    }

    @Test
    fun localFolderOffersFolderShortcutsButNoShareOrOpenWith() {
        assertEquals(
            listOf(
                FileRowAction.COPY,
                FileRowAction.CUT,
                FileRowAction.RENAME,
                FileRowAction.HIDE,
                FileRowAction.COMPRESS_TO_ZIP,
                FileRowAction.FOLDER_SHORTCUTS,
                FileRowAction.DETAILS,
                FileRowAction.DELETE,
            ),
            FileRowMenuModel.forFile(file("Folder", isDirectory = true), isRemote = false).actions,
        )
    }

    @Test
    fun safFolderHasNoFolderShortcuts() {
        val actions = FileRowMenuModel.forFile(
            file("Folder", isDirectory = true, source = FileSource.SAF),
            isRemote = false,
        ).actions

        assertEquals(false, FileRowAction.FOLDER_SHORTCUTS in actions)
    }

    @Test
    fun remoteFileOffersDownloadAndNoShare() {
        assertEquals(
            listOf(
                FileRowAction.DOWNLOAD,
                FileRowAction.COPY,
                FileRowAction.CUT,
                FileRowAction.RENAME,
                FileRowAction.COMPRESS_TO_ZIP,
                FileRowAction.DETAILS,
                FileRowAction.DELETE,
            ),
            FileRowMenuModel.forFile(file("notes.txt", source = FileSource.FTP), isRemote = true).actions,
        )
    }

    @Test
    fun webDavFileCanBeOpenedWith() {
        val actions = FileRowMenuModel.forFile(
            file("movie.mp4", source = FileSource.WEBDAV),
            isRemote = true,
        ).actions

        assertEquals(FileRowAction.OPEN_WITH, actions.first())
        assertEquals(true, FileRowAction.DOWNLOAD in actions)
    }

    @Test
    fun supportedArchiveOffersExtraction() {
        val actions = FileRowMenuModel.forFile(file("archive.zip"), isRemote = false).actions

        assertEquals(true, FileRowAction.EXTRACT_HERE in actions)
        assertEquals(false, FileRowAction.EXTRACTION_UNSUPPORTED in actions)
        assertEquals(true, FileRowAction.COMPRESS_TO_ZIP in actions)
    }

    @Test
    fun everyArchiveKeepsCompressionAvailable() {
        listOf("archive.zip", "archive.tar.gz", "legacy.rar", "photos.7z", "log.gz").forEach { name ->
            assertEquals(name, true, FileRowAction.COMPRESS_TO_ZIP in
                FileRowMenuModel.forFile(file(name), isRemote = false).actions)
        }
    }

    @Test
    fun rarShowsUnsupportedExtraction() {
        val actions = FileRowMenuModel.forFile(file("legacy.rar"), isRemote = false).actions

        assertEquals(false, FileRowAction.EXTRACT_HERE in actions)
        assertEquals(true, FileRowAction.EXTRACTION_UNSUPPORTED in actions)
    }

    @Test
    fun localAudioOffersTones() {
        val actions = FileRowMenuModel.forFile(file("song.mp3"), isRemote = false).actions

        assertEquals(true, FileRowAction.AUDIO_TONES in actions)
        assertEquals(
            false,
            FileRowAction.AUDIO_TONES in FileRowMenuModel.forFile(
                file("song.mp3", source = FileSource.FTP),
                isRemote = true,
            ).actions,
        )
    }

    @Test
    fun hiddenLocalItemsOfferUnhideAndOtherProvidersOfferNeither() {
        assertEquals(true, FileRowAction.UNHIDE in FileRowMenuModel.forFile(file(".notes.txt"), false).actions)
        assertEquals(false, FileRowAction.HIDE in FileRowMenuModel.forFile(file(".notes.txt"), false).actions)
        for (source in FileSource.entries.filter { it != FileSource.LOCAL }) {
            val actions = FileRowMenuModel.forFile(file("notes.txt", source = source), isRemote = true).actions
            assertEquals(source.name, false, FileRowAction.HIDE in actions || FileRowAction.UNHIDE in actions)
        }
        assertEquals(false, FileRowAction.UNHIDE in FileRowMenuModel.forFile(file("..."), false).actions)
    }

    private fun file(
        name: String,
        isDirectory: Boolean = false,
        source: FileSource = FileSource.LOCAL,
    ): FileItem = FileItem(name = name, path = "/$name", isDirectory = isDirectory, source = source)
}
