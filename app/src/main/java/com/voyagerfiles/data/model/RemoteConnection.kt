package com.voyagerfiles.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "remote_connections")
data class RemoteConnection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: ConnectionProtocol,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val privateKeyPath: String? = null,
    val remotePath: String = "/",
    val shareName: String? = null, // SMB share name
    val domain: String? = null,    // SMB domain
    val lastConnected: Long = 0,
    val isFavorite: Boolean = false,
)

enum class ConnectionProtocol(val displayName: String, val defaultPort: Int) {
    SFTP("SFTP", 22),
    FTP("FTP", 21),
    SMB("SMB", 445),
    WEBDAV("WebDAV", 443),
}
