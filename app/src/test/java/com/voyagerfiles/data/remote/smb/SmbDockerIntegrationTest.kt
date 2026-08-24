package com.voyagerfiles.data.remote.smb

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        val media = temp.newFolder("media")
        val documents = temp.newFolder("documents")
        val containerName = "voyager-smb-test-${System.nanoTime()}"
        var provider: SmbFileProvider? = null
        var directProvider: SmbFileProvider? = null

        try {
            docker(
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
                "-u",
                "$USERNAME;$PASSWORD",
                "-s",
                "media;/media;yes;no;no;$USERNAME;;;;Media files",
                "-s",
                "documents;/documents;yes;no;no;$USERNAME;;;;Documents",
            )
            val port = docker("port", containerName, "445/tcp")
                .lineSequence()
                .first { it.isNotBlank() }
                .substringAfterLast(':')
                .trim()
                .toInt()
            provider = waitUntilReady(port)

            val rootNames = provider.listFiles("/").getOrThrow().map { it.name }
            assertTrue(rootNames.containsAll(listOf("documents", "media")))

            val mediaPayload = "SMB discovery media probe".toByteArray()
            provider.getOutputStream("/media/probe.txt").getOrThrow().use { it.write(mediaPayload) }
            assertEquals(
                mediaPayload.toList(),
                provider.getInputStream("/media/probe.txt").getOrThrow().use { it.readBytes() }.toList(),
            )
            assertTrue(provider.listFiles("/").getOrThrow().map { it.name }.contains("documents"))

            val documentPayload = "independent documents share".toByteArray()
            provider.getOutputStream("/documents/document.txt").getOrThrow().use { it.write(documentPayload) }
            assertEquals(
                documentPayload.toList(),
                provider.getInputStream("/documents/document.txt").getOrThrow().use { it.readBytes() }.toList(),
            )
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
            docker("rm", "--force", containerName, allowFailure = true)
        }
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

    private fun connection(port: Int, shareName: String?) = RemoteConnection(
        name = "Docker SMB test",
        protocol = ConnectionProtocol.SMB,
        host = "127.0.0.1",
        port = port,
        username = USERNAME,
        password = PASSWORD,
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
