package com.voyagerfiles.ui.screens

import androidx.annotation.StringRes
import com.voyagerfiles.R
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.util.StorageVolumeInfo
import com.voyagerfiles.viewmodel.BrowserNavigationBounds
import com.voyagerfiles.viewmodel.BrowserSession

/** Location groups that can grow long enough to need a recent-entries cut and a full list. */
internal enum class LocationGroup(@StringRes val labelRes: Int) {
    CONNECTIONS(R.string.browser_locations_connections),
    BOOKMARKS(R.string.home_bookmarks_title),
}

/**
 * Locations the browser's Sessions sheet can open as a new session without returning Home.
 *
 * Entries whose root already has an open session are omitted, because that session is listed
 * directly above them. Tapping a listed location therefore always creates a session. Local
 * entries follow the same all-files-access rule as Home. Connections are ordered by last
 * connection and bookmarks by last use, so the overview can show the few most recent entries
 * and leave the rest to the full list.
 */
data class BrowserLocationsModel(
    val connections: List<RemoteConnection>,
    val bookmarks: List<Bookmark>,
    val storageVolumes: List<StorageVolumeInfo>,
) {
    val isEmpty: Boolean
        get() = connections.isEmpty() && bookmarks.isEmpty() && storageVolumes.isEmpty()

    val recentConnections: List<RemoteConnection>
        get() = connections.take(RECENT_LIMIT)

    val hasMoreConnections: Boolean
        get() = connections.size > RECENT_LIMIT

    val recentBookmarks: List<Bookmark>
        get() = bookmarks.take(RECENT_LIMIT)

    val hasMoreBookmarks: Boolean
        get() = bookmarks.size > RECENT_LIMIT

    companion object {
        /** How many entries of a group the overview shows before deferring to Show all. */
        const val RECENT_LIMIT = 3

        fun forState(
            sessions: List<BrowserSession>,
            connections: List<RemoteConnection>,
            bookmarks: List<Bookmark>,
            storageVolumes: List<StorageVolumeInfo>,
            hasAllFilesAccess: Boolean,
        ): BrowserLocationsModel {
            val openConnectionIds = sessions.mapNotNull { it.connectionId }.toSet()
            val openLocalRoots = sessions
                .filter { it.source == FileSource.LOCAL }
                .map { BrowserNavigationBounds.normalizePath(it.rootPath) }
                .toSet()
            return BrowserLocationsModel(
                connections = connections
                    .filterNot { it.id in openConnectionIds }
                    .sortedByDescending { it.lastConnected },
                bookmarks = if (hasAllFilesAccess) {
                    bookmarks
                        .filter { bookmark ->
                            bookmark.source == FileSource.LOCAL &&
                                BrowserNavigationBounds.normalizePath(bookmark.path) !in openLocalRoots
                        }
                        .sortedWith(compareByDescending<Bookmark> { it.lastUsedAt }.thenByDescending { it.createdAt })
                } else {
                    emptyList()
                },
                storageVolumes = if (hasAllFilesAccess) {
                    storageVolumes.filter { volume ->
                        val path = volume.path
                        volume.isAvailable &&
                            path != null &&
                            BrowserNavigationBounds.normalizePath(path) !in openLocalRoots
                    }
                } else {
                    emptyList()
                },
            )
        }
    }
}
