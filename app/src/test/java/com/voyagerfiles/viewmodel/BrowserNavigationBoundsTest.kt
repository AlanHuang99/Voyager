package com.voyagerfiles.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationBoundsTest {

    @Test
    fun normalizePathKeepsRootStable() {
        assertEquals("/", BrowserNavigationBounds.normalizePath(""))
        assertEquals("/", BrowserNavigationBounds.normalizePath("/"))
        assertEquals("/storage/emulated/0/Download", BrowserNavigationBounds.normalizePath("//storage//emulated/0/Download/"))
    }

    @Test
    fun normalizePathKeepsContentUrisStable() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"

        assertEquals(uri, BrowserNavigationBounds.normalizePath(uri))
        assertTrue(BrowserNavigationBounds.isAtSessionRoot(uri, uri))
    }

    @Test
    fun parentNavigationStopsAtSessionRoot() {
        val root = "/storage/emulated/0/Download"

        assertTrue(
            BrowserNavigationBounds.canNavigateToParent(
                currentPath = "/storage/emulated/0/Download/folder",
                parentPath = root,
                sessionRootPath = root,
            )
        )

        assertFalse(
            BrowserNavigationBounds.canNavigateToParent(
                currentPath = root,
                parentPath = "/storage/emulated/0",
                sessionRootPath = root,
            )
        )
    }

    @Test
    fun parentNavigationRejectsSiblingPrefixMatches() {
        assertFalse(
            BrowserNavigationBounds.isPathAtOrInsideRoot(
                path = "/storage/emulated/0/DownloadArchive",
                rootPath = "/storage/emulated/0/Download",
            )
        )
    }

    @Test
    fun slashRootAllowsNormalParentNavigationUntilSlash() {
        assertTrue(
            BrowserNavigationBounds.canNavigateToParent(
                currentPath = "/var/log",
                parentPath = "/var",
                sessionRootPath = "/",
            )
        )

        assertFalse(
            BrowserNavigationBounds.canNavigateToParent(
                currentPath = "/",
                parentPath = null,
                sessionRootPath = "/",
            )
        )
    }
}
