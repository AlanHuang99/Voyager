package com.voyagerfiles.data.remote.smb

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPathIdentityTest {
    private val connection = RemoteConnection(
        name = "SMB identity test", protocol = ConnectionProtocol.SMB,
        host = "server", port = 445, username = "user", shareName = "media",
    )

    @Test
    fun resolvesBothNamespacesWithoutUsingInitialFolderOrCredentials() {
        val direct = SmbFileProvider(connection.copy(remotePath = "/initial-folder"))
        val discovery = SmbFileProvider(connection.copy(shareName = null, username = "another-user"))
        assertTrue(direct.isSamePath("/report.txt", discovery, "/media/report.txt"))
        assertTrue(discovery.isSamePath("/media/report.txt", direct, "/report.txt"))
        assertFalse(direct.isSamePath("/report.txt", discovery, "/documents/report.txt"))
        assertFalse(discovery.isSamePath("/documents/report.txt", direct, "/report.txt"))
        assertFalse(direct.isSamePath("/report.txt", discovery, "/media/other.txt"))
    }

    @Test
    fun comparesShareNamesAndPathSegmentsConservativelyIgnoringCase() {
        val direct = SmbFileProvider(connection.copy(shareName = " MEDIA "))
        val discovery = SmbFileProvider(connection.copy(host = "SERVER", shareName = ""))
        assertTrue(direct.isSamePath("/folder/../REPORT.txt", discovery, "/media/report.txt"))
        assertTrue(discovery.isSamePath("/MEDIA/folder/report.txt", direct, "\\folder\\report.txt"))
        assertTrue(direct.isSamePath("/", discovery, "/media"))
        assertFalse(direct.isSamePath("/", discovery, "/"))
    }

    @Test
    fun distinguishesServersPortsAndShares() {
        val direct = SmbFileProvider(connection)
        for (other in listOf(
            connection.copy(host = "another-server"),
            connection.copy(port = 1445),
            connection.copy(shareName = "documents"),
        )) {
            assertFalse(direct.isSamePath("/report.txt", SmbFileProvider(other), "/report.txt"))
        }
    }

    @Test
    fun ancestryUsesResolvedSegmentsInBothDirections() = runBlocking {
        val direct = SmbFileProvider(connection)
        val discovery = SmbFileProvider(connection.copy(shareName = null))
        assertTrue(direct.isDescendantPath("/folder", discovery, "/media/folder/child"))
        assertTrue(discovery.isDescendantPath("/media/folder", direct, "/folder/child"))
        assertTrue(direct.isDescendantPath("/", discovery, "/media/folder"))
        assertTrue(discovery.isDescendantPath("/media", direct, "/folder"))
        assertFalse(direct.isDescendantPath("/folder", discovery, "/media/folder-other"))
        assertFalse(discovery.isDescendantPath("/media/folder", direct, "/folder-other"))
        assertFalse(direct.isDescendantPath("/", discovery, "/documents/folder"))
    }
}
