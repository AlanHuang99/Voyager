package com.voyagerfiles.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadSourceFactoryTest {

    @Test
    fun preservesZeroLengthDocumentSize() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

        val source = UploadSourceFactory.fromUri(resolver, ZERO_URI)

        assertEquals("empty.txt", source.name)
        assertEquals(0L, source.size)
    }

    @Test
    fun preservesUnknownDocumentSizeAsNull() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

        val source = UploadSourceFactory.fromUri(resolver, UNKNOWN_URI)

        assertEquals("unknown.bin", source.name)
        assertNull(source.size)
    }

    private companion object {
        const val TEST_AUTHORITY = "com.voyagerfiles.test.upload"
        val ZERO_URI: Uri = Uri.parse("content://$TEST_AUTHORITY/document/zero")
        val UNKNOWN_URI: Uri = Uri.parse("content://$TEST_AUTHORITY/document/unknown")
    }
}
