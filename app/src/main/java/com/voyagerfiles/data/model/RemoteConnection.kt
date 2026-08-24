package com.voyagerfiles.data.model

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import androidx.annotation.StringRes
import com.voyagerfiles.R

@Entity(tableName = "remote_connections")
data class RemoteConnection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: ConnectionProtocol,
    val host: String,
    val port: Int,
    @ColumnInfo(defaultValue = "1")
    val useTls: Boolean = port == 443,
    val username: String = "",
    val password: String = "",
    val privateKeyPath: String? = null,
    val remotePath: String = "/",
    val shareName: String? = null, // SMB share name
    val domain: String? = null,    // SMB domain
    val lastConnected: Long = 0,
    val isFavorite: Boolean = false,
)

enum class ConnectionProtocol(@StringRes val displayNameRes: Int, val defaultPort: Int) {
    SFTP(R.string.protocol_sftp, 22),
    FTP(R.string.protocol_ftp, 21),
    SMB(R.string.protocol_smb, 445),
    WEBDAV(R.string.protocol_webdav, 443),
}
