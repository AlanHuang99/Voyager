package com.voyagerfiles.data.repository

import com.voyagerfiles.data.local.ConnectionDao
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRepositoryTest {

    @Test
    fun connectionsExposeDecryptedPasswordsWithoutChangingStoredRows() = runBlocking {
        val dao = FakeConnectionDao(listOf(connection(id = 1, password = "sealed:secret")))
        val repository = ConnectionRepository(dao, FakeCredentialCipher())

        val visible = repository.connections.first().single()

        assertEquals("secret", visible.password)
        assertEquals("sealed:secret", dao.rows.value.single().password)
    }

    @Test
    fun unreadableEncryptedPasswordIsExposedAsBlank() = runBlocking {
        val dao = FakeConnectionDao(listOf(connection(id = 1, password = "sealed:corrupt")))
        val repository = ConnectionRepository(dao, FakeCredentialCipher())

        val visible = repository.connections.first().single()

        assertEquals("", visible.password)
    }

    @Test
    fun saveEncryptsPasswordBeforePersistence() = runBlocking {
        val dao = FakeConnectionDao()
        val repository = ConnectionRepository(dao, FakeCredentialCipher())

        repository.save(connection(password = "secret"))

        assertEquals("sealed:secret", dao.rows.value.single().password)
    }

    @Test
    fun plaintextMigrationOnlyReplacesMatchingPasswordValues() = runBlocking {
        val dao = FakeConnectionDao(
            listOf(
                connection(id = 1, password = "legacy"),
                connection(id = 2, password = "sealed:existing"),
                connection(id = 3, password = ""),
            )
        )
        val repository = ConnectionRepository(dao, FakeCredentialCipher())

        repository.migratePlaintextCredentials()

        assertEquals(
            listOf("sealed:legacy", "sealed:existing", ""),
            dao.rows.value.sortedBy { it.id }.map { it.password },
        )
        assertTrue(dao.replacedIds == listOf(1L))
    }

    private fun connection(
        id: Long = 0,
        password: String,
    ) = RemoteConnection(
        id = id,
        name = "Server",
        protocol = ConnectionProtocol.SFTP,
        host = "example.test",
        port = 22,
        username = "user",
        password = password,
    )

    private class FakeCredentialCipher : CredentialCipher {
        override fun isEncrypted(value: String): Boolean = value.startsWith("sealed:")

        override fun encrypt(plaintext: String): String = if (plaintext.isEmpty()) "" else "sealed:$plaintext"

        override fun decrypt(value: String): String = when (value) {
            "sealed:corrupt" -> throw IllegalArgumentException("Unreadable")
            else -> value.removePrefix("sealed:")
        }
    }

    private class FakeConnectionDao(
        initialRows: List<RemoteConnection> = emptyList(),
    ) : ConnectionDao {
        val rows = MutableStateFlow(initialRows)
        val replacedIds = mutableListOf<Long>()

        override fun getAllConnections(): Flow<List<RemoteConnection>> = rows

        override fun getFavorites(): Flow<List<RemoteConnection>> =
            rows.map { connections -> connections.filter { it.isFavorite } }

        override suspend fun getById(id: Long): RemoteConnection? = rows.value.firstOrNull { it.id == id }

        override suspend fun insert(connection: RemoteConnection): Long {
            val id = connection.id.takeIf { it != 0L } ?: ((rows.value.maxOfOrNull { it.id } ?: 0L) + 1L)
            rows.value += connection.copy(id = id)
            return id
        }

        override suspend fun update(connection: RemoteConnection) {
            rows.value = rows.value.map { if (it.id == connection.id) connection else it }
        }

        override suspend fun delete(connection: RemoteConnection) {
            rows.value = rows.value.filterNot { it.id == connection.id }
        }

        override suspend fun updateLastConnected(id: Long, timestamp: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(lastConnected = timestamp) else it }
        }

        override suspend fun replacePassword(id: Long, expected: String, replacement: String): Int {
            val current = rows.value.firstOrNull { it.id == id && it.password == expected } ?: return 0
            rows.value = rows.value.map { if (it.id == current.id) it.copy(password = replacement) else it }
            replacedIds += id
            return 1
        }
    }
}
