package com.voyagerfiles.data.repository

import android.content.Context
import android.net.Uri
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.remote.ftp.FtpFileProvider
import com.voyagerfiles.data.remote.saf.SafFileProvider
import com.voyagerfiles.data.remote.sftp.SftpFileProvider
import com.voyagerfiles.data.remote.smb.SmbFileProvider
import com.voyagerfiles.data.remote.webdav.WebDavFileProvider

object FileProviderFactory {
    fun createLocal(): FileProvider = LocalFileProvider()

    fun createSaf(context: Context, treeUri: Uri): FileProvider =
        SafFileProvider(context.applicationContext, treeUri)

    fun createRemote(connection: RemoteConnection): FileProvider = when (connection.protocol) {
        ConnectionProtocol.SFTP -> SftpFileProvider(connection)
        ConnectionProtocol.FTP -> FtpFileProvider(connection)
        ConnectionProtocol.SMB -> SmbFileProvider(connection)
        ConnectionProtocol.WEBDAV -> WebDavFileProvider(connection)
    }
}
