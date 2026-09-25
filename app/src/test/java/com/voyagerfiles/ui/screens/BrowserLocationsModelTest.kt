package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.util.StorageVolumeInfo
import com.voyagerfiles.viewmodel.BrowserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLocationsModelTest {

    private val nas = RemoteConnection(id = 1L, name = "NAS", protocol = ConnectionProtocol.SFTP, host = "nas.local", port = 22, lastConnected = 200L)
    private val router = RemoteConnection(id = 2L, name = "Router", protocol = ConnectionProtocol.FTP, host = "fritz.box", port = 21, lastConnected = 100L)
    private val downloads = Bookmark(id = 1L, name = "Downloads", path = "/storage/emulated/0/Download", createdAt = 10L)
    private val camera = Bookmark(id = 2L, name = "Camera", path = "/storage/emulated/0/DCIM/Camera/", createdAt = 20L)
    private val remoteBookmark = Bookmark(id = 3L, name = "Share", path = "/share", source = FileSource.SFTP, connectionId = 1L)
    private val internal = StorageVolumeInfo(
        description = "Internal storage",
        path = "/storage/emulated/0",
        isPrimary = true,
        isRemovable = false,
        state = StorageVolumeInfo.STATE_MOUNTED,
    )
    private val sdCard = StorageVolumeInfo(
        description = "SD card",
        path = "/storage/1234-5678",
        isPrimary = false,
        isRemovable = true,
        state = StorageVolumeInfo.STATE_MOUNTED,
    )
    private val ejected = StorageVolumeInfo(
        description = "USB drive",
        path = null,
        isPrimary = false,
        isRemovable = true,
        state = "unmounted",
    )

    private fun localSession(rootPath: String) = BrowserSession(
        id = "local:$rootPath",
        title = rootPath.substringAfterLast('/'),
        source = FileSource.LOCAL,
        rootPath = rootPath,
        currentPath = rootPath,
    )

    private fun remoteSession(connection: RemoteConnection) = BrowserSession(
        id = "remote:${connection.id}",
        title = connection.name,
        source = FileSource.SFTP,
        rootPath = "/",
        currentPath = "/",
        connectionId = connection.id,
        host = connection.host,
    )

    private fun bookmark(id: Long, createdAt: Long, lastUsedAt: Long = 0L) =
        Bookmark(id = id, name = "b$id", path = "/storage/emulated/0/b$id", createdAt = createdAt, lastUsedAt = lastUsedAt)

    private fun connection(id: Long, lastConnected: Long) =
        RemoteConnection(id = id, name = "c$id", protocol = ConnectionProtocol.SFTP, host = "h$id", port = 22, lastConnected = lastConnected)

    @Test
    fun listsEveryLocationWhenNothingIsOpen() {
        val model = BrowserLocationsModel.forState(
            sessions = emptyList(),
            connections = listOf(nas, router),
            bookmarks = listOf(downloads, camera),
            storageVolumes = listOf(internal, sdCard),
            hasAllFilesAccess = true,
        )

        assertEquals(listOf(nas, router), model.connections)
        assertEquals(listOf(camera, downloads), model.bookmarks)
        assertEquals(listOf(internal, sdCard), model.storageVolumes)
        assertFalse(model.isEmpty)
    }

    @Test
    fun omitsConnectionsThatAlreadyHaveASession() {
        val model = BrowserLocationsModel.forState(
            sessions = listOf(remoteSession(nas)),
            connections = listOf(nas, router),
            bookmarks = emptyList(),
            storageVolumes = emptyList(),
            hasAllFilesAccess = true,
        )

        assertEquals(listOf(router), model.connections)
    }

    @Test
    fun omitsLocalRootsThatAlreadyHaveASessionAfterNormalization() {
        val model = BrowserLocationsModel.forState(
            sessions = listOf(
                localSession("/storage/emulated/0"),
                localSession("/storage/emulated/0/DCIM/Camera"),
            ),
            connections = emptyList(),
            bookmarks = listOf(downloads, camera),
            storageVolumes = listOf(internal, sdCard),
            hasAllFilesAccess = true,
        )

        // A bookmark inside an open session's tree is still a new root and stays listed.
        assertEquals(listOf(downloads), model.bookmarks)
        assertEquals(listOf(sdCard), model.storageVolumes)
    }

    @Test
    fun hidesLocalLocationsWithoutAllFilesAccessButKeepsConnections() {
        val model = BrowserLocationsModel.forState(
            sessions = emptyList(),
            connections = listOf(nas),
            bookmarks = listOf(downloads),
            storageVolumes = listOf(internal),
            hasAllFilesAccess = false,
        )

        assertEquals(listOf(nas), model.connections)
        assertTrue(model.bookmarks.isEmpty())
        assertTrue(model.storageVolumes.isEmpty())
    }

    @Test
    fun skipsUnavailableVolumesAndRemoteBookmarks() {
        val model = BrowserLocationsModel.forState(
            sessions = emptyList(),
            connections = emptyList(),
            bookmarks = listOf(remoteBookmark, downloads),
            storageVolumes = listOf(ejected, internal),
            hasAllFilesAccess = true,
        )

        assertEquals(listOf(downloads), model.bookmarks)
        assertEquals(listOf(internal), model.storageVolumes)
    }

    @Test
    fun ordersConnectionsByLastConnectedAndBookmarksByLastUseThenCreation() {
        val neverUsedNew = bookmark(id = 1L, createdAt = 300L)
        val neverUsedOld = bookmark(id = 2L, createdAt = 100L)
        val usedLongAgo = bookmark(id = 3L, createdAt = 50L, lastUsedAt = 1_000L)
        val usedRecently = bookmark(id = 4L, createdAt = 10L, lastUsedAt = 5_000L)

        val model = BrowserLocationsModel.forState(
            sessions = emptyList(),
            connections = listOf(connection(1L, lastConnected = 5L), connection(2L, lastConnected = 50L), connection(3L, lastConnected = 0L)),
            bookmarks = listOf(neverUsedOld, usedLongAgo, neverUsedNew, usedRecently),
            storageVolumes = emptyList(),
            hasAllFilesAccess = true,
        )

        assertEquals(listOf(2L, 1L, 3L), model.connections.map { it.id })
        assertEquals(listOf(usedRecently, usedLongAgo, neverUsedNew, neverUsedOld), model.bookmarks)
    }

    @Test
    fun recentListsHoldAtMostThreeEntriesAndFlagTheRemainder() {
        val model = BrowserLocationsModel.forState(
            sessions = emptyList(),
            connections = (1L..4L).map { connection(it, lastConnected = it) },
            bookmarks = (1L..3L).map { bookmark(it, createdAt = it) },
            storageVolumes = emptyList(),
            hasAllFilesAccess = true,
        )

        assertEquals(listOf(4L, 3L, 2L), model.recentConnections.map { it.id })
        assertTrue(model.hasMoreConnections)
        assertEquals(listOf(3L, 2L, 1L), model.recentBookmarks.map { it.id })
        assertFalse(model.hasMoreBookmarks)
    }

    @Test
    fun isEmptyOnlyWhenEveryGroupIsEmpty() {
        val empty = BrowserLocationsModel.forState(
            sessions = listOf(remoteSession(nas), localSession("/storage/emulated/0")),
            connections = listOf(nas),
            bookmarks = emptyList(),
            storageVolumes = listOf(internal),
            hasAllFilesAccess = true,
        )
        assertTrue(empty.isEmpty)

        val withStorage = empty.copy(storageVolumes = listOf(sdCard))
        assertFalse(withStorage.isEmpty)
    }
}
