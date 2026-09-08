package com.voyagerfiles.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class RootDockerIntegrationTest {
    @Test fun privilegedOperationsAndReadOnlyMountPreserveOriginals() = runBlocking {
        assumeTrue(System.getenv("VOYAGER_RUN_DOCKER_TESTS") == "true")
        val container = "voyager-root-${UUID.randomUUID()}"
        fun docker(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("docker") + arguments).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
            return output.trim()
        }
        try {
            docker("run", "-d", "--rm", "--name", container, "--network", "none", "--read-only", "--tmpfs", "/fixtures:rw,noexec,nosuid,size=16m", "alpine@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce", "sleep", "300")
            docker("exec", container, "sh", "-c", "mkdir /fixtures/private; chmod 700 /fixtures/private")
            val denied = ProcessBuilder("docker", "exec", "-u", "65534", container, "ls", "/fixtures/private").start()
            denied.inputStream.close()
            assertTrue(denied.waitFor(10, TimeUnit.SECONDS))
            assertNotEquals(0, denied.exitValue())
            RootFileProvider(RootShell(startProcess = { script ->
                ProcessBuilder("docker", "exec", "-i", container, "sh", "-c", script).start()
            })).use { root ->
                val file = root.createFile("/fixtures/private", "quote'\n\$(id);.txt").getOrThrow()
                root.getOutputStream(file.path).getOrThrow().use { it.write("privileged".toByteArray()) }
                assertEquals("privileged", root.readText(file.path).getOrThrow().text)
                root.saveText(root.readText(file.path).getOrThrow(), "saved as root").getOrThrow()
                val renamed = root.rename(file.path, "renamed").getOrThrow()
                val dest = root.createDirectory("/fixtures/private", "dest").getOrThrow()
                root.copy(renamed.path, dest.path).getOrThrow()
                assertEquals("saved as root", root.readText("${dest.path}/renamed").getOrThrow().text)
                val readOnly = root.readText("/etc/alpine-release").getOrThrow()
                val failedSave = root.saveText(readOnly, "must not replace")
                assertTrue(failedSave.isFailure)
                assertTrue(failedSave.exceptionOrNull()!!.message!!.contains("Read-only", ignoreCase = true))
                assertEquals(readOnly.text, root.readText(readOnly.path).getOrThrow().text)
                root.delete(dest.path).getOrThrow()
                root.delete(renamed.path).getOrThrow()
                assertTrue(root.listFiles("/fixtures/private").getOrThrow().isEmpty())
            }
        } finally { runCatching { docker("rm", "-f", container) } }
    }
}
