package com.voyagerfiles.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileItemTest {

    @Test
    fun apkFilesUseAndroidPackageMimeType() {
        val file = FileItem(
            name = "installer.apk",
            path = "/storage/emulated/0/Download/installer.apk",
            isDirectory = false,
        )

        assertEquals("application/vnd.android.package-archive", file.mimeType)
        assertTrue(file.isApk)
    }

    @Test
    fun imageThumbnailEligibilityIsLocalOnly() {
        val localImage = FileItem(
            name = "photo.jpg",
            path = "/storage/emulated/0/Pictures/photo.jpg",
            isDirectory = false,
            source = FileSource.LOCAL,
        )
        val remoteImage = localImage.copy(source = FileSource.WEBDAV)
        val localFolder = localImage.copy(name = "Pictures", isDirectory = true)

        assertTrue(localImage.usesLocalImageThumbnail)
        assertFalse(remoteImage.usesLocalImageThumbnail)
        assertFalse(localFolder.usesLocalImageThumbnail)
    }
}
