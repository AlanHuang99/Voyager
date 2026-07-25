package com.voyagerfiles.data.remote.sftp

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class SftpDockerIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun generatedKeyAuthenticatesAgainstContainerizedSftpServer() = runBlocking {
        assumeTrue(System.getenv("VOYAGER_RUN_DOCKER_TESTS") == "true")
        val generated = SshKeyGenerator.generateToDirectory(
            directory = temp.newFolder("keys"),
            baseName = "id_voyager_docker",
            comment = "voyager-docker-test",
        )
        val containerName = "voyager-sftp-test-${System.nanoTime()}"
        var provider: SftpFileProvider? = null

        try {
            docker(
                "run",
                "--detach",
                "--name",
                containerName,
                "--publish",
                "127.0.0.1::22",
                "--mount",
                "type=bind,src=${generated.publicKeyFile.absolutePath},dst=/home/voyager/.ssh/keys/id_rsa.pub,readonly",
                "atmoz/sftp:alpine",
                "$USERNAME::1001:1001:upload",
            )
            val port = docker("port", containerName, "22/tcp")
                .lineSequence()
                .first { it.isNotBlank() }
                .substringAfterLast(':')
                .trim()
                .toInt()
            provider = SftpFileProvider(
                connection = RemoteConnection(
                    name = "Docker key-auth test",
                    protocol = ConnectionProtocol.SFTP,
                    host = "127.0.0.1",
                    port = port,
                    username = USERNAME,
                    password = "",
                    privateKeyPath = generated.privateKeyFile.absolutePath,
                ),
                knownHostsFile = temp.root.resolve("known_hosts"),
            )

            waitUntilReady(provider)
            val payload = "authenticated with Voyager's generated key".toByteArray()
            provider.getOutputStream("/upload/probe.txt").getOrThrow().use { output ->
                output.write(payload)
            }

            val downloaded = provider.getInputStream("/upload/probe.txt").getOrThrow().use { input ->
                input.readBytes()
            }

            assertEquals(payload.toList(), downloaded.toList())
            assertEquals(
                listOf("probe.txt"),
                provider.listFiles("/upload").getOrThrow().map { it.name },
            )
        } finally {
            provider?.disconnect()
            docker("rm", "--force", containerName, allowFailure = true)
        }
    }

    private suspend fun waitUntilReady(provider: SftpFileProvider) {
        var lastFailure: Throwable? = null
        repeat(30) {
            val result = provider.listFiles("/upload")
            if (result.isSuccess) return
            lastFailure = result.exceptionOrNull()
            delay(500)
        }
        throw AssertionError("The containerized SFTP server did not become ready", lastFailure)
    }

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
        const val DOCKER_TIMEOUT_SECONDS = 60L
    }
}
