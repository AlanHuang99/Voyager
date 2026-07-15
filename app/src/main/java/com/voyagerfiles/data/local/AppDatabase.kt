package com.voyagerfiles.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.RemoteConnection

@Database(
    entities = [RemoteConnection::class, Bookmark::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voyager.db",
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE remote_connections ADD COLUMN useTls INTEGER NOT NULL DEFAULT 1")
                database.execSQL("UPDATE remote_connections SET useTls = CASE WHEN port = 443 THEN 1 ELSE 0 END WHERE protocol = 'WEBDAV'")
            }
        }
    }
}
