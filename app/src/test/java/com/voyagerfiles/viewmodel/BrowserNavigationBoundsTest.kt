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

    @Test
    fun sameOrAncestorFollowsParentsUpFromPath() {
        val parentOf: (String) -> String? = { path ->
            if (path == "/") null else path.substringBeforeLast("/").ifEmpty { "/" }
        }

        assertTrue(BrowserNavigationBounds.isSameOrAncestor("/a/b/c", "/a/b/c", parentOf))
        assertTrue(BrowserNavigationBounds.isSameOrAncestor("/a", "/a/b/c/", parentOf))
        assertTrue(BrowserNavigationBounds.isSameOrAncestor("/", "/a/b/c", parentOf))
        assertFalse(BrowserNavigationBounds.isSameOrAncestor("/a/b/c", "/a", parentOf))
        assertFalse(BrowserNavigationBounds.isSameOrAncestor("/a/x", "/a/b/c", parentOf))
    }

    @Test
    fun sameOrAncestorUsesProviderParentsForContentUris() {
        val root = "content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3A"
        val download = "content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3ADownload"
        val nested = "content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3ADownload%2Fnested"
        val parents = mapOf(nested to download, download to root)

        assertTrue(BrowserNavigationBounds.isSameOrAncestor(download, nested, parents::get))
        assertTrue(BrowserNavigationBounds.isSameOrAncestor(root, nested, parents::get))
        assertFalse(BrowserNavigationBounds.isSameOrAncestor(nested, download, parents::get))
    }

    @Test
    fun sameOrAncestorStopsWhenProviderRepeatsPath() {
        assertFalse(BrowserNavigationBounds.isSameOrAncestor("/a", "/b", parentOf = { it }))
    }
}
