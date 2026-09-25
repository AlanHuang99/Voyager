package com.voyagerfiles.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val path: String,
    val source: FileSource = FileSource.LOCAL,
    val connectionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Last time the bookmarked folder was opened as a session root; 0 when never used. */
    @ColumnInfo(defaultValue = "0")
    val lastUsedAt: Long = 0,
)
