package com.voyagerfiles.data.remote.ftp

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileDownloader
import com.voyagerfiles.data.repository.ForwardingOutputStream
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.viewmodel.FileOperationCoordinator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.command.Command
import org.apache.ftpserver.command.CommandFactoryFactory
import org.apache.ftpserver.ftplet.DefaultFtpReply
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.impl.FtpIoSession
import org.apache.ftpserver.impl.FtpServerContext
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

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
    fun coordinatorSameConnectionReplacementStagesAndCommitsMove() = runBlocking {
        val server = startServer()
        val payload = ByteArray(150_000) { (it % 251).toByte() }
        Files.createDirectory(server.root.resolve("source"))
        Files.createDirectory(server.root.resolve("destination"))
        Files.write(server.root.resolve("source/report.bin"), payload)
        Files.write(server.root.resolve("destination/report.bin"), "original".toByteArray())
        val provider = createProvider(server.port)
        val events = mutableListOf<com.voyagerfiles.data.repository.StreamTransferProgress>()
        val result = withTimeout(15_000) {
            com.voyagerfiles.viewmodel.FileOperationCoordinator.movePath(
                provider, provider, "/source/report.bin", "/destination",
                { com.voyagerfiles.viewmodel.ConflictDecision.REPLACE },
            ) {
                assertEquals("original", String(Files.readAllBytes(server.root.resolve("destination/report.bin"))))
                events += it
            }
        }
        assertTrue(result.isSuccess)
        assertFalse(Files.exists(server.root.resolve("source/report.bin")))
        assertTrue(payload.contentEquals(Files.readAllBytes(server.root.resolve("destination/report.bin"))))
        assertEquals(listOf("report.bin"), provider.listFiles("/destination").getOrThrow().map { it.name })
        assertEquals(payload.size.toLong(), events.last().bytesTransferred)
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
    fun reconnectsAfterServerDropsTheControlConnection() = runBlocking {
        val server = startServer()
        val provider = createProvider(server.port)
        provider.listFiles("/").getOrThrow()
        servers.removeAt(servers.lastIndex).stop()
        startServer(server.root, server.port)

        provider.getOutputStream("/reconnected.txt").getOrThrow().use { stream ->
            stream.write("reconnected".toByteArray())
        }

        assertEquals("reconnected", String(Files.readAllBytes(server.root.resolve("reconnected.txt"))))
    }

    @Test
    fun fileDownloaderSavesFileToLocalDirectory() = runBlocking {
        val server = startServer()
        Files.write(server.root.resolve("remote.txt"), "ftp download".toByteArray())
        val provider = createProvider(server.port)
        val destination = temp.newFolder("downloads").toPath()
        val item = provider.listFiles("/").getOrThrow().single()

        val result = FileDownloader.download(provider, listOf(item), destination.toFile()).getOrThrow()

        assertEquals(1, result.downloadedFiles)
        assertEquals("ftp download", String(Files.readAllBytes(destination.resolve("remote.txt"))))
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

    @Test
    fun streamsTransferLargeFilesWithoutByteArrayBuffers() = runBlocking {
        val server = startServer()
        val provider = createProvider(server.port)
        val payload = ByteArray(LARGE_TRANSFER_BYTES) { (it % 251).toByte() }

        val output = provider.getOutputStream("/large.bin").getOrThrow()
        assertFalse(output is ByteArrayOutputStream)
        assertTrue(output is ForwardingOutputStream)
        output.use { it.write(payload) }

        val input = provider.getInputStream("/large.bin").getOrThrow()
        assertFalse(input is ByteArrayInputStream)
        assertTrue(input.use { it.readBytes().contentEquals(payload) })
    }

    @Test
    fun copyLargeFileUsesBoundedMemory() = runBlocking {
        val server = startServer()
        val payload = ByteArray(LARGE_TRANSFER_BYTES) { (it % 241).toByte() }
        Files.write(server.root.resolve("source.bin"), payload)
        Files.createDirectory(server.root.resolve("target"))
        val provider = createProvider(server.port)

        provider.copy("/source.bin", "/target").getOrThrow()

        assertTrue(Files.readAllBytes(server.root.resolve("target/source.bin")).contentEquals(payload))
    }

    @Test
    fun stalledFtpReadCancellationRollsBackMoveWithoutChangingSource() = runBlocking {
        val controlServer = ServerSocket(0)
        val dataServer = ServerSocket(0)
        val sent = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val serverTask = executor.submit {
            controlServer.accept().use { control ->
                val writer = control.getOutputStream().bufferedWriter()
                fun reply(message: String) { writer.write("$message\r\n"); writer.flush() }
                reply("220 ready")
                val reader = control.getInputStream().bufferedReader()
                while (true) {
                    val command = reader.readLine() ?: break
                    when (command.substringBefore(' ')) {
                        "USER" -> reply("331 password")
                        "PASS" -> reply("230 logged in")
                        "TYPE", "NOOP" -> reply("200 OK")
                        "PASV" -> reply("227 Entering Passive Mode (127,0,0,1,${dataServer.localPort / 256},${dataServer.localPort % 256})")
                        "RETR" -> {
                            reply("150 opening data")
                            dataServer.accept().use { data ->
                                data.getOutputStream().write(ByteArray(64 * 1024) { 19 })
                                data.getOutputStream().flush()
                                sent.countDown()
                                // Keep the connection open but send no more bytes until the client aborts.
                                while (data.getInputStream().read() != -1) { }
                            }
                            break
                        }
                        else -> reply("200 OK")
                    }
                }
            }
        }
        val provider = createProvider(controlServer.localPort)
        val local = com.voyagerfiles.data.repository.LocalFileProvider()
        val source = temp.newFile("source.bin").apply { writeBytes(ByteArray(2 * 1024 * 1024) { 19 }) }
        val original = source.readBytes()
        val destination = temp.newFolder("stalled-target")
        val networkSource = object : com.voyagerfiles.data.repository.FileProvider by local {
            override suspend fun getInputStream(path: String) = provider.getInputStream("/source.bin")
        }
        val token = com.voyagerfiles.data.repository.TransferCancellation()
        try {
            val transfer = async(Dispatchers.IO + token.contextElement()) {
                com.voyagerfiles.viewmodel.FileOperationCoordinator.movePath(networkSource, local, source.path, destination.path)
            }
            assertTrue(sent.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val partial = java.io.File(destination, source.name)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (partial.length() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(partial.length() > 0L)
            val before = System.nanoTime()
            token.cancel()
            assertTrue(System.nanoTime() - before < 500_000_000L)
            assertTrue(withTimeout(3_000) { transfer.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertFalse(partial.exists())
            assertTrue(original.contentEquals(source.readBytes()))
            serverTask.get(3, java.util.concurrent.TimeUnit.SECONDS)
            Unit
        } finally {
            token.cancel()
            controlServer.close()
            dataServer.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun sameProviderCopyKeepsExistingDestination() = runBlocking {
        val server = startServer()
        Files.write(server.root.resolve("source.txt"), "source".toByteArray())
        Files.createDirectory(server.root.resolve("target"))
        Files.write(server.root.resolve("target/source.txt"), "existing".toByteArray())
        val provider = createProvider(server.port)
        assertTrue(provider.copy("/source.txt", "/target").isFailure)
        assertEquals("existing", String(Files.readAllBytes(server.root.resolve("target/source.txt"))))
    }

    @Test
    fun getFileInfoNamesFileFromRequestedPath() = runBlocking {
        for (flavor in ServerFlavor.entries) {
            val server = startServer(flavor = flavor)
            Files.createDirectories(server.root.resolve("docs"))
            Files.write(server.root.resolve("docs/report.txt"), "report".toByteArray())
            val provider = createProvider(server.port)

            val item = provider.getFileInfo("/docs/report.txt").getOrThrow()

            assertEquals(flavor.name, "report.txt", item.name)
            assertEquals(flavor.name, "/docs/report.txt", item.path)
            assertFalse(flavor.name, item.isDirectory)
            assertEquals(flavor.name, 6L, item.size)
            assertTrue(flavor.name, provider.exists("/docs/report.txt"))
            if (!flavor.advertisesMlst) assertEquals(flavor.name, 0, server.mlstRequests.get())
        }
    }

    @Test
    fun getFileInfoDescribesDirectoryRatherThanItsChildren() = runBlocking {
        for (flavor in ServerFlavor.entries) {
            val server = startServer(flavor = flavor)
            Files.createDirectories(server.root.resolve("docs/empty"))
            Files.write(server.root.resolve("docs/notes.txt"), "notes".toByteArray())
            val provider = createProvider(server.port)

            val docs = provider.getFileInfo("/docs").getOrThrow()
            val empty = provider.getFileInfo("/docs/empty").getOrThrow()

            assertEquals(flavor.name, "docs", docs.name)
            assertTrue(flavor.name, docs.isDirectory)
            assertEquals(flavor.name, "empty", empty.name)
            assertTrue(flavor.name, empty.isDirectory)
            assertTrue(flavor.name, provider.exists("/docs/empty"))
            if (!flavor.advertisesMlst) assertEquals(flavor.name, 0, server.mlstRequests.get())
        }
    }

    @Test
    fun getFileInfoFailsForMissingPath() = runBlocking {
        for (flavor in ServerFlavor.entries) {
            val server = startServer(flavor = flavor)
            Files.createDirectories(server.root.resolve("docs"))
            val provider = createProvider(server.port)

            assertTrue(flavor.name, provider.getFileInfo("/docs/missing.txt").isFailure)
            assertFalse(flavor.name, provider.exists("/docs/missing.txt"))
            assertTrue(flavor.name, provider.getFileInfo("/missing").isFailure)
            assertFalse(flavor.name, provider.exists("/missing"))
        }
    }

    @Test
    fun copyToLocalStorageKeepsFileNameWhenServerEchoesRequestedPath() = runBlocking {
        val server = startServer(flavor = ServerFlavor.ECHOES_PATHS_WITH_MLST)
        Files.createDirectories(server.root.resolve("docs"))
        Files.write(server.root.resolve("docs/report.txt"), "report".toByteArray())
        val provider = createProvider(server.port)
        val destination = temp.newFolder("local")

        FileOperationCoordinator.copyPath(
            sourceProvider = provider,
            destinationProvider = LocalFileProvider(),
            sourcePath = "/docs/report.txt",
            destinationDirectoryPath = destination.absolutePath,
        ).getOrThrow()

        assertEquals(listOf("report.txt"), destination.list().orEmpty().toList())
        assertEquals("report", File(destination, "report.txt").readText())
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
            ),
            temp.root,
        )
        providers += provider
        return provider
    }

    private fun startServer(flavor: ServerFlavor = ServerFlavor.NAMES_FILES): RunningServer {
        val root = temp.newFolder("ftp-root-${servers.size}").toPath()
        val port = freePort()
        return startServer(root, port, flavor)
    }

    private fun startServer(
        root: Path,
        port: Int,
        flavor: ServerFlavor = ServerFlavor.NAMES_FILES,
    ): RunningServer {
        val mlstRequests = AtomicInteger()
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
            if (flavor.echoesRequestedPaths) {
                fileSystem = RequestedPathEchoingFileSystem(fileSystem)
            }
            if (!flavor.advertisesMlst) {
                commandFactory = CommandFactoryFactory().apply {
                    addCommand("FEAT", ReplyCommand(211, "Extensions supported\n SIZE\n MDTM\n UTF8\nEnd"))
                    addCommand("MLST", ReplyCommand(502, "Command not implemented") { mlstRequests.incrementAndGet() })
                }.createCommandFactory()
            }
        }

        val server = factory.createServer()
        server.start()
        servers += server
        return RunningServer(root, port, mlstRequests)
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }

    private data class RunningServer(
        val root: Path,
        val port: Int,
        val mlstRequests: AtomicInteger = AtomicInteger(),
    )

    /** How the embedded server names the entry in a single-file `LIST` reply and whether it offers MLST. */
    private enum class ServerFlavor(val echoesRequestedPaths: Boolean, val advertisesMlst: Boolean) {
        /** Apache FtpServer as shipped: the file name alone. */
        NAMES_FILES(echoesRequestedPaths = false, advertisesMlst = true),

        /** Like ProFTPD and Pure-FTPd: the path exactly as the client sent it. */
        ECHOES_PATHS_WITH_MLST(echoesRequestedPaths = true, advertisesMlst = true),

        /** The same listing on a server without MLST, so only LIST and CWD are available. */
        ECHOES_PATHS_WITHOUT_MLST(echoesRequestedPaths = true, advertisesMlst = false),
    }

    /** Names a file by the path the client requested, as ProFTPD and Pure-FTPd do for `LIST file`. */
    private class RequestedPathEchoingFileSystem(
        private val delegate: FileSystemFactory,
    ) : FileSystemFactory {
        override fun createFileSystemView(user: User): FileSystemView =
            EchoingView(delegate.createFileSystemView(user))

        private class EchoingView(private val delegate: FileSystemView) : FileSystemView by delegate {
            override fun getFile(file: String): FtpFile {
                val resolved = delegate.getFile(file)
                return if (resolved.isFile) RequestedPathFile(resolved, file) else resolved
            }
        }

        private class RequestedPathFile(
            delegate: FtpFile,
            private val requestedPath: String,
        ) : FtpFile by delegate {
            override fun getName(): String = requestedPath
        }
    }

    private class ReplyCommand(
        private val code: Int,
        private val message: String,
        private val onExecute: () -> Unit = {},
    ) : Command {
        override fun execute(session: FtpIoSession, context: FtpServerContext, request: FtpRequest) {
            onExecute()
            session.write(DefaultFtpReply(code, message))
        }
    }

    private companion object {
        const val USERNAME = "tester"
        const val PASSWORD = "secret"
        const val LARGE_TRANSFER_BYTES = 2 * 1024 * 1024
    }
}
