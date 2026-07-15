package com.voyagerfiles.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileSharingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val filesToDelete = mutableListOf<File>()

    @After
    fun cleanUp() {
        filesToDelete.forEach(File::delete)
    }

    @Test
    fun multipleLocalFilesBuildReadableSendMultipleIntent() {
        val files = listOf(createLocalFile("one.jpg"), createLocalFile("two.png"))

        val intent = FileUtils.createShareIntent(context, files).getOrThrow()

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/*", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(2, intent.clipData?.itemCount)
        assertEquals(
            2,
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.size,
        )
    }

    @Test
    fun safFileKeepsItsContentUri() {
        val uri = Uri.parse("content://documents/tree/root/document/report.pdf")

        val intent = FileUtils.createShareIntent(context, listOf(safFile(uri))).getOrThrow()

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }

    private fun createLocalFile(name: String): FileItem {
        val file = File(context.cacheDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        filesToDelete += file
        return FileItem(
            name = file.name,
            path = file.absolutePath,
            isDirectory = false,
            source = FileSource.LOCAL,
        )
    }

    private fun safFile(uri: Uri) = FileItem(
        name = "report.pdf",
        path = uri.toString(),
        isDirectory = false,
        source = FileSource.SAF,
    )
}
