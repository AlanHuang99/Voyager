package com.voyagerfiles.data.remote.sftp

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator

class SftpFileProviderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val servers = mutableListOf<SshServer>()
    private val providers = mutableListOf<SftpFileProvider>()

    @After
    fun tearDown() = runBlocking {
        providers.forEach { it.disconnect() }
        servers.forEach { it.stop(true) }
    }

    @Test
    fun listFilesWithPasswordAuthentication() = runBlocking {
        val server = startServer(AuthMode.PASSWORD)
        Files.write(server.root.resolve("hello.txt"), "hello".toByteArray())
        val provider = createProvider(server.port)

        val files = provider.listFiles("/").getOrThrow()

        assertEquals(listOf("hello.txt"), files.map { it.name })
    }

    @Test
    fun listFilesWithKeyboardInteractiveAuthentication() = runBlocking {
        val server = startServer(AuthMode.KEYBOARD_INTERACTIVE)
        Files.write(server.root.resolve("keyboard.txt"), "keyboard".toByteArray())
        val provider = createProvider(server.port)

        val files = provider.listFiles("/").getOrThrow()

        assertEquals(listOf("keyboard.txt"), files.map { it.name })
    }

    @Test
    fun outputStreamConnectsAndUploadsFile() = runBlocking {
        val server = startServer(AuthMode.PASSWORD)
        val provider = createProvider(server.port)

        provider.getOutputStream("/uploaded.txt").getOrThrow().use { stream ->
            stream.write("uploaded".toByteArray())
        }

        assertTrue(Files.exists(server.root.resolve("uploaded.txt")))
        assertEquals("uploaded", String(Files.readAllBytes(server.root.resolve("uploaded.txt"))))
    }

    private fun createProvider(port: Int): SftpFileProvider {
        val provider = SftpFileProvider(
            RemoteConnection(
                name = "Local test SFTP",
                protocol = ConnectionProtocol.SFTP,
                host = "127.0.0.1",
                port = port,
                username = USERNAME,
                password = PASSWORD,
            )
        )
        providers += provider
        return provider
    }

    private fun startServer(authMode: AuthMode): RunningServer {
        val root = temp.newFolder("sftp-root-${servers.size}").toPath()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(generateHostKeyPair())
            fileSystemFactory = VirtualFileSystemFactory(root)
            subsystemFactories = listOf(SftpSubsystemFactory())

            when (authMode) {
                AuthMode.PASSWORD -> {
                    userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
                    passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                        username == USERNAME && password == PASSWORD
                    }
                }

                AuthMode.KEYBOARD_INTERACTIVE -> {
                    userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
                    keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                        override fun generateChallenge(
                            session: org.apache.sshd.server.session.ServerSession,
                            username: String,
                            lang: String,
                            subMethods: String,
                        ): InteractiveChallenge = InteractiveChallenge().apply {
                            interactionName = "Password"
                            interactionInstruction = ""
                            languageTag = lang
                            addPrompt("Password:", false)
                        }

                        override fun authenticate(
                            session: org.apache.sshd.server.session.ServerSession,
                            username: String,
                            responses: List<String>,
                        ): Boolean = username == USERNAME && responses.singleOrNull() == PASSWORD
                    }
                }
            }
        }

        server.start()
        servers += server

        val port = server.boundAddresses
            .filterIsInstance<InetSocketAddress>()
            .first()
            .port

        return RunningServer(root, port)
    }

    private fun generateHostKeyPair() =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private data class RunningServer(
        val root: Path,
        val port: Int,
    )

    private enum class AuthMode {
        PASSWORD,
        KEYBOARD_INTERACTIVE,
    }

    private companion object {
        const val USERNAME = "tester"
        const val PASSWORD = "secret"
    }
}
