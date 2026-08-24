package com.voyagerfiles.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import java.io.File
import java.util.Date
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApkThumbnailLoaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var validApk: File
    private lateinit var corruptApk: File

    @Before
    fun setUp() {
        validApk = context.cacheDir.resolve("apk-thumbnail-fixture.apk")
        File(context.applicationInfo.sourceDir).copyTo(validApk, overwrite = true)
        corruptApk = context.cacheDir.resolve("apk-thumbnail-corrupt.apk").apply {
            writeText("not an apk")
        }
        ApkThumbnailLoader.clear()
    }

    @After
    fun tearDown() {
        ApkThumbnailLoader.clear()
        validApk.delete()
        corruptApk.delete()
    }

    @Test
    fun readableLocalApkReturnsBoundedApplicationIcon() {
        val bitmap = ApkThumbnailLoader.load(context, localItem(validApk), 96, 96).getOrThrow()

        assertTrue(bitmap.width in 1..96)
        assertTrue(bitmap.height in 1..96)
    }

    @Test
    fun corruptApkReturnsFailure() {
        assertTrue(ApkThumbnailLoader.load(context, localItem(corruptApk), 96, 96).isFailure)
    }

    @Test
    fun safApkReturnsFailure() {
        assertTrue(ApkThumbnailLoader.load(context, localItem(validApk, FileSource.SAF), 96, 96).isFailure)
    }

    @Test
    fun webDavApkReturnsFailure() {
        assertTrue(ApkThumbnailLoader.load(context, localItem(validApk, FileSource.WEBDAV), 96, 96).isFailure)
    }

    private fun localItem(file: File, source: FileSource = FileSource.LOCAL) = FileItem(
        name = file.name,
        path = file.absolutePath,
        isDirectory = false,
        size = file.length(),
        lastModified = Date(file.lastModified()),
        source = source,
    )
}
