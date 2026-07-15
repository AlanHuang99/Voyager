package com.voyagerfiles.data.repository

import com.voyagerfiles.data.local.ConnectionDao
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.security.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ConnectionRepository(
    private val connectionDao: ConnectionDao,
    private val credentialCipher: CredentialCipher,
) {
    val connections: Flow<List<RemoteConnection>> = connectionDao.getAllConnections().map { rows ->
        rows.map(::revealCredential)
    }

    suspend fun save(connection: RemoteConnection): Long = withContext(Dispatchers.IO) {
        val protectedConnection = protectCredential(connection)
        if (protectedConnection.id == 0L) {
            connectionDao.insert(protectedConnection)
        } else {
            connectionDao.update(protectedConnection)
            protectedConnection.id
        }
    }

    suspend fun delete(connection: RemoteConnection) = withContext(Dispatchers.IO) {
        connectionDao.delete(connection)
    }

    suspend fun updateLastConnected(id: Long, timestamp: Long) = withContext(Dispatchers.IO) {
        connectionDao.updateLastConnected(id, timestamp)
    }

    suspend fun migratePlaintextCredentials() = withContext(Dispatchers.IO) {
        connectionDao.getAllConnections().first().forEach { connection ->
            val plaintext = connection.password
            if (plaintext.isEmpty() || credentialCipher.isEncrypted(plaintext)) return@forEach
            connectionDao.replacePassword(
                id = connection.id,
                expected = plaintext,
                replacement = credentialCipher.encrypt(plaintext),
            )
        }
    }

    private fun protectCredential(connection: RemoteConnection): RemoteConnection {
        val password = connection.password
        if (password.isEmpty() || credentialCipher.isEncrypted(password)) return connection
        return connection.copy(password = credentialCipher.encrypt(password))
    }

    private fun revealCredential(connection: RemoteConnection): RemoteConnection {
        val storedPassword = connection.password
        if (storedPassword.isEmpty() || !credentialCipher.isEncrypted(storedPassword)) return connection
        return connection.copy(
            password = runCatching { credentialCipher.decrypt(storedPassword) }.getOrDefault("")
        )
    }
}
