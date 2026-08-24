package com.voyagerfiles.data.remote.smb

import com.hierynomus.smbj.share.DiskShare
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbDiscoveryModeTest {
    @Test
    fun virtualRootListsDiscoveredSharesAndCachesTheSessionResult() = runBlocking {
        val session = FakeSessionHandle(
            listOf(
                SmbDiscoveredShare("Documents", "documents remark"),
                SmbDiscoveredShare("Media", "media remark"),
            ),
        )
        val provider = discoveryProvider(session)

        val first = provider.listFiles("/").getOrThrow()
        val second = provider.listFiles("/").getOrThrow()

        assertEquals(listOf("/Documents", "/Media"), first.map { it.path })
        assertTrue(first.all { it.isDirectory && it.size == 0L && it.source == FileSource.SMB })
        assertEquals(first, second)
        assertEquals(1, session.discoveryCalls)
        assertTrue(provider.exists("/"))
        assertTrue(provider.exists("/Media"))
        assertFalse(provider.exists("/Missing"))
        assertEquals("/", provider.getFileInfo("/").getOrThrow().path)
        assertEquals("Media", provider.getFileInfo("/Media").getOrThrow().name)
    }

    @Test
    fun directShareModeNeverCallsDiscovery() = runBlocking {
        val session = FakeSessionHandle(listOf(SmbDiscoveredShare("Other", null)))
        val provider = provider("Media", session)

        assertFalse(provider.exists("/"))

        assertEquals(0, session.discoveryCalls)
        assertEquals(listOf("Media"), session.connectedShares)
    }

    @Test
    fun virtualServerAndShareRootsRejectFileOperations() = runBlocking {
        val provider = discoveryProvider(FakeSessionHandle(listOf(SmbDiscoveredShare("Media", null))))
        provider.listFiles("/").getOrThrow()

        val forbidden = listOf<suspend () -> Result<*>>(
            { provider.createDirectory("/", "new") },
            { provider.createFile("/", "new.txt") },
            { provider.delete("/") },
            { provider.rename("/", "renamed") },
            { provider.copy("/", "/Media") },
            { provider.move("/", "/Media") },
            { provider.getInputStream("/") },
            { provider.getOutputStream("/") },
            { provider.delete("/Media") },
            { provider.rename("/Media", "renamed") },
            { provider.getInputStream("/Media") },
            { provider.getOutputStream("/Media") },
        )

        forbidden.forEach { operation ->
            assertTrue(operation().exceptionOrNull() is SmbVirtualRootException)
        }
    }

    @Test
    fun disconnectClearsDiscoveryCacheAndClosesEachSessionOnce() = runBlocking {
        val first = FakeSessionHandle(listOf(SmbDiscoveredShare("Media", null)))
        val second = FakeSessionHandle(listOf(SmbDiscoveredShare("Documents", null)))
        val sessions = ArrayDeque(listOf(first, second))
        val provider = SmbFileProvider(
            connection = connection(null),
            shareDiscovery = SmbShareDiscovery { emptyList() },
            sessionFactory = SmbSessionHandleFactory { sessions.removeFirst() },
        )

        assertEquals(listOf("Media"), provider.listFiles("/").getOrThrow().map { it.name })
        provider.disconnect()
        provider.disconnect()
        assertEquals(listOf("Documents"), provider.listFiles("/").getOrThrow().map { it.name })

        assertEquals(1, first.closeCalls)
        assertEquals(0, second.closeCalls)
    }

    private fun discoveryProvider(session: FakeSessionHandle): SmbFileProvider = provider(null, session)

    private fun provider(shareName: String?, session: FakeSessionHandle) = SmbFileProvider(
        connection = connection(shareName),
        shareDiscovery = SmbShareDiscovery { emptyList() },
        sessionFactory = SmbSessionHandleFactory { session },
    )

    private fun connection(shareName: String?) = RemoteConnection(
        name = "SMB test",
        protocol = ConnectionProtocol.SMB,
        host = "server.example",
        port = 445,
        username = "voyager",
        password = "secret",
        shareName = shareName,
    )

    private class FakeSessionHandle(
        private val shares: List<SmbDiscoveredShare>,
    ) : SmbSessionHandle {
        var discoveryCalls = 0
        var closeCalls = 0
        val connectedShares = mutableListOf<String>()

        override fun discover(discovery: SmbShareDiscovery): List<SmbDiscoveredShare> {
            discoveryCalls += 1
            return shares
        }

        override fun connectShare(name: String): DiskShare {
            connectedShares += name
            error("No disk share should be required by this test")
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
