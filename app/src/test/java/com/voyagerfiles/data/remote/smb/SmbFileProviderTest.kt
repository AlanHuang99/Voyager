package com.voyagerfiles.data.remote.smb

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class SmbFileProviderTest {

    private var provider: SmbFileProvider? = null
    private lateinit var rootPath: String

    @Before
    fun setUp() {
        val host = env("VOYAGER_SMB_HOST")
        val port = env("VOYAGER_SMB_PORT")
        val share = env("VOYAGER_SMB_SHARE")
        val username = env("VOYAGER_SMB_USERNAME")
        val password = env("VOYAGER_SMB_PASSWORD")

        assumeTrue(
            "Set VOYAGER_SMB_HOST, VOYAGER_SMB_PORT, VOYAGER_SMB_SHARE, VOYAGER_SMB_USERNAME, and VOYAGER_SMB_PASSWORD to run SMB integration tests",
            listOf(host, port, share, username, password).all { !it.isNullOrBlank() },
        )

        rootPath = "/voyager-smb-test-${UUID.randomUUID()}"
        provider = SmbFileProvider(
            RemoteConnection(
                name = "Local test SMB",
                protocol = ConnectionProtocol.SMB,
                host = host!!,
                port = port!!.toInt(),
                username = username!!,
                password = password!!,
                shareName = share!!,
                domain = env("VOYAGER_SMB_DOMAIN"),
            )
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            provider?.delete(rootPath)
            provider?.disconnect()
        }
    }

    @Test
    fun createListCopyMoveAndDeleteFiles() = runBlocking {
        val smb = provider ?: return@runBlocking
        smb.createDirectory("/", rootPath.substringAfterLast("/")).getOrThrow()
        smb.createDirectory(rootPath, "source").getOrThrow()
        smb.createDirectory(rootPath, "target").getOrThrow()

        smb.getOutputStream("$rootPath/source/file.txt").getOrThrow().use { stream ->
            stream.write("smb".toByteArray())
        }

        val sourceFiles = smb.listFiles("$rootPath/source").getOrThrow()
        assertEquals(listOf("file.txt"), sourceFiles.map { it.name })

        smb.copy("$rootPath/source/file.txt", "$rootPath/target").getOrThrow()
        smb.move("$rootPath/target/file.txt", rootPath).getOrThrow()
        smb.delete("$rootPath/source").getOrThrow()

        val rootFiles = smb.listFiles(rootPath).getOrThrow().map { it.name }.sorted()
        assertEquals(listOf("file.txt", "target"), rootFiles)
        assertTrue(smb.exists("$rootPath/file.txt"))
    }

    @Test
    fun copyDirectoryRecursively() = runBlocking {
        val smb = provider ?: return@runBlocking
        smb.createDirectory("/", rootPath.substringAfterLast("/")).getOrThrow()
        smb.createDirectory(rootPath, "source").getOrThrow()
        smb.createDirectory("$rootPath/source", "nested").getOrThrow()
        smb.createDirectory(rootPath, "target").getOrThrow()

        smb.getOutputStream("$rootPath/source/nested/file.txt").getOrThrow().use { stream ->
            stream.write("recursive".toByteArray())
        }

        smb.copy("$rootPath/source", "$rootPath/target").getOrThrow()

        assertTrue(smb.exists("$rootPath/target/source/nested/file.txt"))
    }

    private fun env(name: String): String? = System.getenv(name)
}
