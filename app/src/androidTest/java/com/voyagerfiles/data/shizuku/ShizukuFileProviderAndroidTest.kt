package com.voyagerfiles.data.shizuku

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.LocalFileProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ShizukuFileProviderAndroidTest {
    private fun enabled() {
        assumeTrue("Opt in with -e shizuku true after granting Shizuku access", InstrumentationRegistry.getArguments().getString("shizuku") == "true")
    }

    @Test fun realShellOperationsPreserveBytesAndNames() = runBlocking<Unit> {
        enabled()
        val connection = ShizukuConnection.connect(ApplicationProvider.getApplicationContext())
        assertTrue("Shizuku must execute as shell or root", connection.uid == 2000 || connection.uid == 0)
        ShizukuFileProvider(connection).use { provider ->
            val scratch = provider.createDirectory("/data/local/tmp", "voyager-shizuku-${UUID.randomUUID()}").getOrThrow()
            try {
                assertEquals(FileSource.SHIZUKU, scratch.source)
                assertNull("Ordinary app access must not list the shell fixture", File(scratch.path).listFiles())
                val file = provider.createFile(scratch.path, "quote'\n @VOYAGER_SHIZUKU_PIPE@.txt").getOrThrow()
                val bytes = ByteArray(350_000) { (it * 17).toByte() }
                provider.getOutputStream(file.path).getOrThrow().use { it.write(bytes) }
                assertArrayEquals(bytes, provider.getInputStream(file.path).getOrThrow().use { it.readBytes() })
                assertEquals(listOf(file.name), provider.listFiles(scratch.path).getOrThrow().map { it.name })
                val renamed = provider.rename(file.path, "renamed.txt").getOrThrow()
                val copy = provider.createDirectory(scratch.path, "copy").getOrThrow()
                provider.copy(renamed.path, copy.path).getOrThrow()
                assertArrayEquals(bytes, provider.getInputStream("${copy.path}/renamed.txt").getOrThrow().use { it.readBytes() })
                val move = provider.createDirectory(scratch.path, "moved").getOrThrow()
                provider.move(renamed.path, move.path).getOrThrow()
                assertFalse(provider.exists(renamed.path))
                val local = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "shizuku-${UUID.randomUUID()}")
                try {
                    local.createNewFile()
                    provider.getInputStream("${move.path}/renamed.txt").getOrThrow().use { source ->
                        LocalFileProvider().writeStream(local.path, source, renamed.path, bytes.size.toLong()).getOrThrow()
                    }
                    assertArrayEquals(bytes, local.readBytes())
                    val upload = provider.createFile(scratch.path, "uploaded.bin").getOrThrow()
                    local.inputStream().use { provider.writeStream(upload.path, it, local.path, bytes.size.toLong()).getOrThrow() }
                    assertArrayEquals(bytes, provider.getInputStream(upload.path).getOrThrow().use { it.readBytes() })
                } finally { local.delete() }
            } finally { provider.delete(scratch.path).getOrThrow() }
        }
    }

    @Test fun androidDataAndObbAreBrowsableButAppPrivateFilesRemainDenied() = runBlocking<Unit> {
        enabled()
        val connection = ShizukuConnection.connect(ApplicationProvider.getApplicationContext())
        val isShell = connection.uid == 2000
        ShizukuFileProvider(connection).use { provider ->
            assertTrue(provider.listFiles("/storage/emulated/0/Android/data").getOrThrow().isNotEmpty())
            provider.listFiles("/storage/emulated/0/Android/obb").getOrThrow()
            if (isShell) assertTrue(provider.listFiles("/data/user/0/com.android.settings").isFailure)
        }
    }

    @Test fun dataAndObbOperationsUseShellAccessAndPreserveBytes() = runBlocking<Unit> {
        enabled()
        ShizukuFileProvider(ShizukuConnection.connect(ApplicationProvider.getApplicationContext())).use { provider ->
            for (parent in listOf(
                "/storage/emulated/0/Android/data/com.voyagerfiles.debug.test/files",
                "/storage/emulated/0/Android/obb/com.voyagerfiles.debug.test",
            )) {
                var path = ""
                for (part in parent.removePrefix("/").split('/')) {
                    val next = "$path/$part"
                    if (!provider.exists(next)) provider.createDirectory(path.ifEmpty { "/" }, part).getOrThrow()
                    path = next
                }
                val scratch = provider.createDirectory(parent, "voyager-shizuku-${UUID.randomUUID()}").getOrThrow()
                try {
                    val file = provider.createFile(scratch.path, "probe.txt").getOrThrow()
                    provider.getOutputStream(file.path).getOrThrow().use { it.write("restricted directory".toByteArray()) }
                    assertEquals("restricted directory", provider.getInputStream(file.path).getOrThrow().bufferedReader().use { it.readText() })
                    val renamed = provider.rename(file.path, "renamed.txt").getOrThrow()
                    assertEquals(listOf("renamed.txt"), provider.listFiles(scratch.path).getOrThrow().map { it.name })
                    provider.delete(renamed.path).getOrThrow()
                } finally { provider.delete(scratch.path).getOrThrow() }
            }
        }
    }

    @Test fun overlappingConnectionsCloseIndependently() = runBlocking<Unit> {
        enabled()
        val first = ShizukuFileProvider(ShizukuConnection.connect(ApplicationProvider.getApplicationContext()))
        val second = ShizukuFileProvider(ShizukuConnection.connect(ApplicationProvider.getApplicationContext()))
        try {
            first.close()
            second.listFiles("/storage/emulated/0/Android/data").getOrThrow()
        } finally { first.close(); second.close() }
    }

    @Test fun cancelledReaderAndWriterStopDelayedMutations() = runBlocking<Unit> {
        enabled()
        val connection = ShizukuConnection.connect(ApplicationProvider.getApplicationContext())
        val scratch = "/data/local/tmp/voyager-shizuku-cancel-${UUID.randomUUID()}"
        val shell = com.voyagerfiles.data.repository.RootShell(
            startProcess = connection::start, requireRoot = false, externalInputPipe = true,
            temporaryDirectory = "/data/local/tmp", timeoutMillis = 2000,
        )
        try {
            shell.execute("mkdir -- '$scratch'")
            val input = shell.input("printf x; sleep 3; touch '$scratch/reader'")
            assertEquals('x'.code, input.read())
            input.close()
            val output = shell.output("sleep 3; cat > '$scratch/writer'")
            (output as com.voyagerfiles.data.repository.TransferAbortable).abortTransfer()
            Thread.sleep(3500)
            assertEquals("", shell.execute("ls -A '$scratch'").toString(Charsets.UTF_8))
        } finally {
            runCatching { shell.execute("rm -rf -- '$scratch'") }
            shell.close()
            connection.close()
        }
    }

    @Test fun closingSessionRejectsOperationsAndNewSessionReconnects() = runBlocking<Unit> {
        enabled()
        val first = ShizukuFileProvider(ShizukuConnection.connect(ApplicationProvider.getApplicationContext()))
        first.close()
        assertTrue(first.listFiles("/storage/emulated/0/Android/data").isFailure)
        ShizukuFileProvider(ShizukuConnection.connect(ApplicationProvider.getApplicationContext())).use { next ->
            next.listFiles("/storage/emulated/0/Android/data").getOrThrow()
        }
    }
}
