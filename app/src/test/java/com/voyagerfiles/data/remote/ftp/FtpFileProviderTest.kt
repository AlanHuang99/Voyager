package com.voyagerfiles.data.remote.ftp

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class FtpFileProviderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val servers = mutableListOf<FtpServer>()
    private val providers = mutableListOf<FtpFileProvider>()

    @After
    fun tearDown() = runBlocking {
        providers.forEach { it.disconnect() }
        servers.forEach { it.stop() }
    }

    @Test
    fun listFilesWithPasswordAuthentication() = runBlocking {
        val server = startServer()
        Files.write(server.root.resolve("hello.txt"), "hello".toByteArray())
        val provider = createProvider(server.port)

        val files = provider.listFiles("/").getOrThrow()

        assertEquals(listOf("hello.txt"), files.map { it.name })
    }

    @Test
    fun outputStreamConnectsAndUploadsFile() = runBlocking {
        val server = startServer()
        val provider = createProvider(server.port)

        provider.getOutputStream("/uploaded.txt").getOrThrow().use { stream ->
            stream.write("uploaded".toByteArray())
        }

        assertTrue(Files.exists(server.root.resolve("uploaded.txt")))
        assertEquals("uploaded", String(Files.readAllBytes(server.root.resolve("uploaded.txt"))))
    }

    @Test
    fun copyDirectoryRecursively() = runBlocking {
        val server = startServer()
        Files.createDirectories(server.root.resolve("source/nested"))
        Files.write(server.root.resolve("source/nested/file.txt"), "copied".toByteArray())
        Files.createDirectory(server.root.resolve("target"))
        val provider = createProvider(server.port)

        provider.copy("/source", "/target").getOrThrow()

        assertEquals("copied", String(Files.readAllBytes(server.root.resolve("target/source/nested/file.txt"))))
    }

    @Test
    fun deleteDirectoryRecursively() = runBlocking {
        val server = startServer()
        Files.createDirectories(server.root.resolve("folder/nested"))
        Files.write(server.root.resolve("folder/nested/file.txt"), "delete".toByteArray())
        val provider = createProvider(server.port)

        provider.delete("/folder").getOrThrow()

        assertFalse(Files.exists(server.root.resolve("folder")))
    }

    private fun createProvider(port: Int): FtpFileProvider {
        val provider = FtpFileProvider(
            RemoteConnection(
                name = "Local test FTP",
                protocol = ConnectionProtocol.FTP,
                host = "127.0.0.1",
                port = port,
                username = USERNAME,
                password = PASSWORD,
            )
        )
        providers += provider
        return provider
    }

    private fun startServer(): RunningServer {
        val root = temp.newFolder("ftp-root-${servers.size}").toPath()
        val port = freePort()

        val user = BaseUser().apply {
            name = USERNAME
            password = PASSWORD
            homeDirectory = root.toAbsolutePath().toString()
            authorities = listOf(WritePermission())
        }

        val factory = FtpServerFactory().apply {
            userManager.save(user)
            addListener(
                "default",
                ListenerFactory().apply {
                    this.port = port
                    serverAddress = "127.0.0.1"
                }.createListener(),
            )
        }

        val server = factory.createServer()
        server.start()
        servers += server
        return RunningServer(root, port)
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }

    private data class RunningServer(
        val root: Path,
        val port: Int,
    )

    private companion object {
        const val USERNAME = "tester"
        const val PASSWORD = "secret"
    }
}
