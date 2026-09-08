package com.voyagerfiles.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE path = :path AND source = :source")
    suspend fun deleteByPath(path: String, source: FileSource)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE path = :path AND source = :source LIMIT 1)")
    suspend fun isBookmarked(path: String, source: FileSource): Boolean

    @Transaction
    suspend fun insertIfAbsent(bookmark: Bookmark) {
        if (!isBookmarked(bookmark.path, bookmark.source)) insert(bookmark)
    }
}
