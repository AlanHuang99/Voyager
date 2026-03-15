package com.voyagerfiles.data.model

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
)
