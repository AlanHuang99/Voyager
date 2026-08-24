package com.voyagerfiles.data.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbBrowsePathTest {
    @Test
    fun rootIsVirtualAndSharePathsSplitOnce() {
        assertEquals(SmbBrowsePath.VirtualRoot, SmbBrowsePath.parse("/"))
        assertEquals(SmbBrowsePath.Share("Media", ""), SmbBrowsePath.parse("/Media"))
        assertEquals(SmbBrowsePath.Share("Media", "Films\\clip.mp4"), SmbBrowsePath.parse("/Media/Films/clip.mp4"))
    }

    @Test
    fun rejectsTraversalAndEmptyInteriorSegments() {
        listOf("", "Media", "/../Media", "/Media/../secret", "/Media//file").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                SmbBrowsePath.parse(path)
            }
        }
    }

    @Test
    fun parentReturnsVirtualRootAtShareBoundary() {
        assertEquals("/", SmbBrowsePath.parentOf("/Media"))
        assertEquals("/Media", SmbBrowsePath.parentOf("/Media/Films"))
        assertNull(SmbBrowsePath.parentOf("/"))
    }
}
