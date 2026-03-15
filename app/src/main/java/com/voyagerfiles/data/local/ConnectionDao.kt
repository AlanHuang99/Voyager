package com.voyagerfiles.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM remote_connections ORDER BY lastConnected DESC")
    fun getAllConnections(): Flow<List<RemoteConnection>>

    @Query("SELECT * FROM remote_connections WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavorites(): Flow<List<RemoteConnection>>

    @Query("SELECT * FROM remote_connections WHERE id = :id")
    suspend fun getById(id: Long): RemoteConnection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: RemoteConnection): Long

    @Update
    suspend fun update(connection: RemoteConnection)

    @Delete
    suspend fun delete(connection: RemoteConnection)

    @Query("UPDATE remote_connections SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)
}
