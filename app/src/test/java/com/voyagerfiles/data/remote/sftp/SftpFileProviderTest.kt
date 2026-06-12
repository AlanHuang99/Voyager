package com.voyagerfiles.data.remote.sftp

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileDownloader
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
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
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.math.BigInteger
import java.util.Base64

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
    fun listFilesWithPrivateKeyAuthentication() = runBlocking {
        val clientKeyPair = generateHostKeyPair()
        val privateKeyFile = temp.newFile("id_rsa").toPath()
        Files.write(privateKeyFile, privateKeyPem(clientKeyPair.private).toByteArray())
        val server = startServer(AuthMode.PUBLIC_KEY, clientKeyPair.public)
        Files.write(server.root.resolve("key.txt"), "key".toByteArray())
        val provider = createProvider(
            port = server.port,
            password = "",
            privateKeyPath = privateKeyFile.toString(),
        )

        val files = provider.listFiles("/").getOrThrow()

        assertEquals(listOf("key.txt"), files.map { it.name })
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

    @Test
    fun inputStreamDownloadsFile() = runBlocking {
        val server = startServer(AuthMode.PASSWORD)
        Files.write(server.root.resolve("download.txt"), "downloaded".toByteArray())
        val provider = createProvider(server.port)

        val contents = provider.getInputStream("/download.txt").getOrThrow().use { stream ->
            stream.readBytes()
        }

        assertEquals("downloaded", String(contents))
    }

    @Test
    fun fileDownloaderSavesFileToLocalDirectory() = runBlocking {
        val server = startServer(AuthMode.PASSWORD)
        Files.write(server.root.resolve("remote.txt"), "local copy".toByteArray())
        val provider = createProvider(server.port)
        val destination = temp.newFolder("downloads").toPath()
        val item = provider.listFiles("/").getOrThrow().single()

        val result = FileDownloader.download(provider, listOf(item), destination.toFile()).getOrThrow()

        assertEquals(1, result.downloadedFiles)
        assertEquals("local copy", String(Files.readAllBytes(destination.resolve("remote.txt"))))
    }

    @Test
    fun fileDownloaderSavesDirectoryRecursively() = runBlocking {
        val server = startServer(AuthMode.PASSWORD)
        Files.createDirectories(server.root.resolve("folder/nested"))
        Files.write(server.root.resolve("folder/nested/file.txt"), "nested".toByteArray())
        val provider = createProvider(server.port)
        val destination = temp.newFolder("recursive-downloads").toPath()
        val item = provider.listFiles("/").getOrThrow().single()

        val result = FileDownloader.download(provider, listOf(item), destination.toFile()).getOrThrow()

        assertEquals(1, result.downloadedFiles)
        assertEquals(2, result.downloadedDirectories)
        assertEquals("nested", String(Files.readAllBytes(destination.resolve("folder/nested/file.txt"))))
    }

    @Test
    fun copyDirectoryRecursively() = runBlocking {
        val server = startServer(AuthMode.PASSWORD)
        Files.createDirectories(server.root.resolve("source/nested"))
        Files.write(server.root.resolve("source/nested/file.txt"), "copied".toByteArray())
        Files.createDirectory(server.root.resolve("target"))
        val provider = createProvider(server.port)

        provider.copy("/source", "/target").getOrThrow()

        assertEquals("copied", String(Files.readAllBytes(server.root.resolve("target/source/nested/file.txt"))))
    }

    @Test
    fun deleteDirectoryRecursively() = runBlocking {
        val server = startServer(AuthMode.PASSWORD)
        Files.createDirectories(server.root.resolve("folder/nested"))
        Files.write(server.root.resolve("folder/nested/file.txt"), "delete".toByteArray())
        val provider = createProvider(server.port)

        provider.delete("/folder").getOrThrow()

        assertTrue(Files.notExists(server.root.resolve("folder")))
    }

    @Test
    fun renameFileReturnsUpdatedInfo() = runBlocking {
        val server = startServer(AuthMode.PASSWORD)
        Files.write(server.root.resolve("old.txt"), "renamed".toByteArray())
        val provider = createProvider(server.port)

        val renamed = provider.rename("/old.txt", "new.txt").getOrThrow()

        assertEquals("new.txt", renamed.name)
        assertEquals("/new.txt", renamed.path)
        assertTrue(Files.notExists(server.root.resolve("old.txt")))
        assertTrue(Files.exists(server.root.resolve("new.txt")))
    }

    private fun createProvider(
        port: Int,
        password: String = PASSWORD,
        privateKeyPath: String? = null,
    ): SftpFileProvider {
        val provider = SftpFileProvider(
            RemoteConnection(
                name = "Local test SFTP",
                protocol = ConnectionProtocol.SFTP,
                host = "127.0.0.1",
                port = port,
                username = USERNAME,
                password = password,
                privateKeyPath = privateKeyPath,
            )
        )
        providers += provider
        return provider
    }

    private fun startServer(authMode: AuthMode, acceptedPublicKey: PublicKey? = null): RunningServer {
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

                AuthMode.PUBLIC_KEY -> {
                    val expectedKey = checkNotNull(acceptedPublicKey)
                    userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
                    publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
                        username == USERNAME && key.encoded.contentEquals(expectedKey.encoded)
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

    private fun privateKeyPem(privateKey: PrivateKey): String {
        val rsa = privateKey as RSAPrivateCrtKey
        val der = derSequence(
            derInteger(BigInteger.ZERO),
            derInteger(rsa.modulus),
            derInteger(rsa.publicExponent),
            derInteger(rsa.privateExponent),
            derInteger(rsa.primeP),
            derInteger(rsa.primeQ),
            derInteger(rsa.primeExponentP),
            derInteger(rsa.primeExponentQ),
            derInteger(rsa.crtCoefficient),
        )
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN RSA PRIVATE KEY-----\n$encoded\n-----END RSA PRIVATE KEY-----\n"
    }

    private fun derSequence(vararg fields: ByteArray): ByteArray {
        val body = fields.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        return byteArrayOf(0x30) + derLength(body.size) + body
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val body = value.toByteArray()
        return byteArrayOf(0x02) + derLength(body.size) + body
    }

    private fun derLength(length: Int): ByteArray =
        when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length <= 0xff -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        }

    private data class RunningServer(
        val root: Path,
        val port: Int,
    )

    private enum class AuthMode {
        PASSWORD,
        KEYBOARD_INTERACTIVE,
        PUBLIC_KEY,
    }

    private companion object {
        const val USERNAME = "tester"
        const val PASSWORD = "secret"
    }
}
