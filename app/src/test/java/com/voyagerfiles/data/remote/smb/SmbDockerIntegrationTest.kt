package com.voyagerfiles.data.remote.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SmbDockerIntegrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun discoversAndUsesAuthenticatedSharesAcrossReconnects() = runBlocking {
        assumeTrue(System.getenv("VOYAGER_RUN_DOCKER_TESTS") == "true")
        withSamba("smb encrypt = desired") { port ->
            var provider: SmbFileProvider? = null
            var directProvider: SmbFileProvider? = null
            try {
                provider = waitUntilReady(port)

                val rootNames = provider.listFiles("/").getOrThrow().map { it.name }
                assertTrue(rootNames.containsAll(listOf("documents", "media")))

                val mediaPayload = "SMB discovery media probe".toByteArray()
                writeAndReadExact(provider, "/media/probe.txt", mediaPayload)
                assertTrue(provider.listFiles("/").getOrThrow().map { it.name }.contains("documents"))

                val documentPayload = "independent documents share".toByteArray()
                writeAndReadExact(provider, "/documents/document.txt", documentPayload)
                provider.disconnect()
                provider = null

                repeat(3) {
                    val reconnect = SmbFileProvider(connection(port, shareName = null))
                    try {
                        assertTrue(reconnect.listFiles("/").getOrThrow().map { it.name }.contains("media"))
                    } finally {
                        reconnect.disconnect()
                    }
                }

                directProvider = SmbFileProvider(connection(port, shareName = "media"))
                assertTrue(directProvider.listFiles("/").getOrThrow().any { it.name == "probe.txt" })
            } finally {
                provider?.disconnect()
                directProvider?.disconnect()
            }
        }
    }

    @Test
    fun discoversAndUsesSharesWhenSmb3EncryptionIsRequired() = runBlocking {
        assumeTrue(System.getenv("VOYAGER_RUN_DOCKER_TESTS") == "true")
        withSamba(
            "server min protocol = SMB3_00",
            "smb encrypt = required",
        ) { port ->
            var provider: SmbFileProvider? = null
            var directProvider: SmbFileProvider? = null
            var wrongPasswordProvider: SmbFileProvider? = null
            try {
                provider = waitUntilReady(port)
                assertTrue(provider.listFiles("/").getOrThrow().map { it.name }.containsAll(listOf("documents", "media")))
                writeAndReadExact(
                    provider,
                    "/media/encrypted.bin",
                    byteArrayOf(0, 1, 2, 3, 0x7f, 0x80.toByte(), 0xff.toByte()),
                )

                directProvider = SmbFileProvider(connection(port, shareName = "documents"))
                writeAndReadExact(
                    directProvider,
                    "/direct-encrypted.bin",
                    byteArrayOf(0xff.toByte(), 0, 0x45, 0x4e, 0x43, 0x00),
                )

                wrongPasswordProvider = SmbFileProvider(
                    connection(port, shareName = null, password = "incorrect-password"),
                )
                val wrongPasswordFailure = wrongPasswordProvider.listFiles("/").exceptionOrNull()
                assertTrue(wrongPasswordFailure is SMBApiException)
                assertEquals(NtStatus.STATUS_LOGON_FAILURE, (wrongPasswordFailure as SMBApiException).status)
            } finally {
                provider?.disconnect()
                directProvider?.disconnect()
                wrongPasswordProvider?.disconnect()
            }
        }
    }

    @Test
    fun preservesDiscoveryAndDirectShareAccessWithSmb2OnlyServer() = runBlocking {
        assumeTrue(System.getenv("VOYAGER_RUN_DOCKER_TESTS") == "true")
        withSamba(
            "server min protocol = SMB2_02",
            "server max protocol = SMB2_10",
            "smb encrypt = off",
        ) { port ->
            var provider: SmbFileProvider? = null
            var directProvider: SmbFileProvider? = null
            try {
                provider = waitUntilReady(port)
                assertTrue(provider.listFiles("/").getOrThrow().map { it.name }.containsAll(listOf("documents", "media")))
                writeAndReadExact(
                    provider,
                    "/media/smb2-discovery.bin",
                    byteArrayOf(0x53, 0x4d, 0x42, 0x32, 0, 0xff.toByte()),
                )

                directProvider = SmbFileProvider(connection(port, shareName = "documents"))
                writeAndReadExact(
                    directProvider,
                    "/smb2-direct.bin",
                    byteArrayOf(0, 2, 1, 0, 2, 1, 0),
                )
            } finally {
                provider?.disconnect()
                directProvider?.disconnect()
            }
        }
    }

    private suspend fun withSamba(
        vararg globalOptions: String,
        block: suspend (port: Int) -> Unit,
    ) {
        val media = temp.newFolder("media")
        val documents = temp.newFolder("documents")
        val containerName = "voyager-smb-test-${System.nanoTime()}"

        try {
            val arguments = mutableListOf(
                "run",
                "--detach",
                "--name",
                containerName,
                "--publish",
                "127.0.0.1::445",
                "--mount",
                "type=bind,src=${media.absolutePath},dst=/media",
                "--mount",
                "type=bind,src=${documents.absolutePath},dst=/documents",
                SAMBA_IMAGE,
                "-p",
            )
            globalOptions.forEach { option ->
                arguments += listOf("-g", option)
            }
            arguments += listOf(
                "-u",
                "$USERNAME;$PASSWORD",
                "-s",
                "media;/media;yes;no;no;$USERNAME;;;;Media files",
                "-s",
                "documents;/documents;yes;no;no;$USERNAME;;;;Documents",
            )
            docker(*arguments.toTypedArray())
            val port = docker("port", containerName, "445/tcp")
                .lineSequence()
                .first { it.isNotBlank() }
                .substringAfterLast(':')
                .trim()
                .toInt()
            block(port)
        } finally {
            docker("rm", "--force", containerName, allowFailure = true)
        }
    }

    private suspend fun writeAndReadExact(
        provider: SmbFileProvider,
        path: String,
        payload: ByteArray,
    ) {
        provider.getOutputStream(path).getOrThrow().use { it.write(payload) }
        assertArrayEquals(payload, provider.getInputStream(path).getOrThrow().use { it.readBytes() })
    }

    private suspend fun waitUntilReady(port: Int): SmbFileProvider {
        var lastFailure: Throwable? = null
        repeat(40) {
            val candidate = SmbFileProvider(connection(port, shareName = null))
            val result = candidate.listFiles("/")
            if (result.isSuccess) return candidate
            lastFailure = result.exceptionOrNull()
            candidate.disconnect()
            delay(500)
        }
        throw AssertionError("The containerized Samba server did not become ready", lastFailure)
    }

    private fun connection(
        port: Int,
        shareName: String?,
        password: String = PASSWORD,
    ) = RemoteConnection(
        name = "Docker SMB test",
        protocol = ConnectionProtocol.SMB,
        host = "127.0.0.1",
        port = port,
        username = USERNAME,
        password = password,
        shareName = shareName,
    )

    private fun docker(
        vararg arguments: String,
        allowFailure: Boolean = false,
    ): String {
        val process = ProcessBuilder(listOf("docker") + arguments)
            .redirectErrorStream(true)
            .start()
        check(process.waitFor(DOCKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Docker command timed out: docker ${arguments.joinToString(" ")}"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(allowFailure || process.exitValue() == 0) {
            "Docker command failed (${process.exitValue()}): docker ${arguments.joinToString(" ")}\n$output"
        }
        return output
    }

    private companion object {
        const val USERNAME = "voyager"
        const val PASSWORD = "voyager-test"
        const val DOCKER_TIMEOUT_SECONDS = 60L
        const val SAMBA_IMAGE =
            "dperson/samba@sha256:66088b78a19810dd1457a8f39340e95e663c728083efa5fe7dc0d40b2478e869"
    }
}
