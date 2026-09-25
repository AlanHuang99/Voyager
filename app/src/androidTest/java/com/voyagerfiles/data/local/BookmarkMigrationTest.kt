package com.voyagerfiles.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookmarkMigrationTest {
    @Test fun versionTwoUpgradePreservesBookmarksAndConnectionsAndTracksUse() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bookmark-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE remote_connections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, protocol TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, useTls INTEGER NOT NULL DEFAULT 1, username TEXT NOT NULL, password TEXT NOT NULL, privateKeyPath TEXT, remotePath TEXT NOT NULL, shareName TEXT, domain TEXT, lastConnected INTEGER NOT NULL, isFavorite INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL, source TEXT NOT NULL, connectionId INTEGER, createdAt INTEGER NOT NULL)")
            db.execSQL("INSERT INTO bookmarks VALUES (1, 'Saved folder', '/storage/emulated/0/Saved', 'LOCAL', NULL, 1234)")
            db.execSQL("INSERT INTO remote_connections VALUES (7, 'Saved server', 'SFTP', 'example.invalid', 22, 0, 'test', 'test-value', NULL, '/', NULL, NULL, 5678, 1)")
            db.version = 2
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_2_3).build()
        try {
            val bookmark = db.bookmarkDao().getAllBookmarks().first().single()
            assertEquals("Saved folder", bookmark.name)
            assertEquals(1234L, bookmark.createdAt)
            assertEquals(0L, bookmark.lastUsedAt)
            val connection = db.connectionDao().getById(7)!!
            assertEquals("Saved server", connection.name)
            assertEquals("test-value", connection.password)
            assertEquals(5678L, connection.lastConnected)
            db.bookmarkDao().markUsed(bookmark.path, FileSource.LOCAL, 9876)
            assertEquals(9876L, db.bookmarkDao().getAllBookmarks().first().single().lastUsedAt)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
