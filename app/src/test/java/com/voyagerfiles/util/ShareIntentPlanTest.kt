package com.voyagerfiles.util

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareIntentPlanTest {

    @Test
    fun plansSingleAndMultipleShares() {
        assertEquals(
            ShareIntentPlan(ShareIntentKind.SINGLE, "image/jpeg"),
            ShareIntentPlan.forFiles(listOf(localFile("one.jpg"))),
        )
        assertEquals(
            ShareIntentPlan(ShareIntentKind.MULTIPLE, "image/*"),
            ShareIntentPlan.forFiles(listOf(localFile("one.jpg"), localFile("two.png"))),
        )
    }

    @Test
    fun rejectsDirectoriesAndRemoteFiles() {
        assertNull(ShareIntentPlan.forFiles(listOf(localDirectory("Folder"))))
        assertNull(ShareIntentPlan.forFiles(listOf(remoteFile("report.pdf"))))
    }

    @Test
    fun rejectsAnEmptySelection() {
        assertNull(ShareIntentPlan.forFiles(emptyList()))
    }

    @Test
    fun unrelatedMimeFamiliesUseWildcard() {
        assertEquals(
            "*/*",
            ShareIntentPlan.forFiles(listOf(localFile("one.jpg"), localFile("notes.txt")))?.mimeType,
        )
    }

    private fun localFile(name: String) = FileItem(
        name = name,
        path = "/storage/emulated/0/$name",
        isDirectory = false,
        source = FileSource.LOCAL,
    )

    private fun localDirectory(name: String) = FileItem(
        name = name,
        path = "/storage/emulated/0/$name",
        isDirectory = true,
        source = FileSource.LOCAL,
    )

    private fun remoteFile(name: String) = FileItem(
        name = name,
        path = "/$name",
        isDirectory = false,
        source = FileSource.SFTP,
    )
}
