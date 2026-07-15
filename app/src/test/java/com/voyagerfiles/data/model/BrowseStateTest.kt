package com.voyagerfiles.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseStateTest {

    private val files = listOf(
        FileItem(name = "Photos", path = "/Photos", isDirectory = true),
        FileItem(name = "photo.JPG", path = "/photo.JPG", isDirectory = false),
        FileItem(name = "vacation.mp4", path = "/vacation.mp4", isDirectory = false),
        FileItem(name = "report.pdf", path = "/report.pdf", isDirectory = false),
        FileItem(name = "archive.zip", path = "/archive.zip", isDirectory = false),
    )

    @Test
    fun searchIsCaseInsensitiveAndCombinesWithTypeFilter() {
        val state = BrowseState(
            files = files,
            searchQuery = "PHOTO",
            fileTypeFilter = FileTypeFilter.IMAGES,
        )

        assertEquals(listOf("photo.JPG"), state.visibleFiles.map { it.name })
    }

    @Test
    fun folderAndDocumentFiltersUseFileSemantics() {
        assertEquals(
            listOf("Photos"),
            BrowseState(files = files, fileTypeFilter = FileTypeFilter.FOLDERS).visibleFiles.map { it.name },
        )
        assertEquals(
            listOf("report.pdf"),
            BrowseState(files = files, fileTypeFilter = FileTypeFilter.DOCUMENTS).visibleFiles.map { it.name },
        )
    }

    @Test
    fun selectedPathsCanBeReconciledToVisibleFiles() {
        val state = BrowseState(
            files = files,
            searchQuery = "report",
            selectedFiles = setOf("/report.pdf", "/photo.JPG", "/missing.txt"),
        )

        assertEquals(setOf("/report.pdf"), state.reconciledSelection)
    }
}
