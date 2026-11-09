package com.voyagerfiles.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val first = Uri.parse("content://com.voyagerfiles.test.upload/document/zero")
    private val second = Uri.parse("content://com.voyagerfiles.test.upload/document/unknown")

    @Test fun receivesSingleStream() {
        assertEquals(listOf(first), IncomingShare.files(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)))
    }

    @Test fun receivesMultipleStreamsWithoutDuplicates() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
        assertEquals(listOf(first, second), IncomingShare.files(context, intent))
    }

    @Test fun acceptsClipDataFallback() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            clipData = ClipData.newRawUri("files", first).apply { addItem(ClipData.Item(second)) }
        }
        assertEquals(listOf(first, second), IncomingShare.files(context, intent))
    }

    @Test fun ignoresNonShareIntents() {
        assertEquals(emptyList<Uri>(), IncomingShare.files(context, Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, first)))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyShare() {
        IncomingShare.files(context, Intent(Intent.ACTION_SEND))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsFileUris() {
        IncomingShare.files(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/private.txt")))
    }

    @Test fun rejectsUserQualifiedAndEncodedOwnedAuthorities() {
        listOf(
            "content://0@${context.packageName}.fileprovider/cache/private.txt",
            "content://0%40${context.packageName}.webdavplayback/private",
            "content://${context.packageName.uppercase()}.fileprovider/cache/private.txt",
            "content://10@external.provider/document/file",
            "content://${context.packageName}.fileprovider:42/cache/private.txt",
        ).forEach { value ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                IncomingShare.files(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse(value)))
            }
        }
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOwnProvider() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/cache/private.txt")
        IncomingShare.files(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri))
    }
}
