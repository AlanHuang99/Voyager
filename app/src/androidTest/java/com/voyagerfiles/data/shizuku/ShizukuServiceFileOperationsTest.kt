package com.voyagerfiles.data.shizuku

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.repository.RootShell
import com.voyagerfiles.data.repository.TransferAbortable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt in with -e shizuku true after granting Shizuku access. Covers the service-side file operations. */
class ShizukuServiceFileOperationsTest {
    private fun enabled() {
        assumeTrue("Opt in with -e shizuku true after granting Shizuku access", InstrumentationRegistry.getArguments().getString("shizuku") == "true")
    }

    private fun session(block: suspend (ShizukuFileProvider, RootShell, String) -> Unit) = runBlocking<Unit> {
        enabled()
        val connection = ShizukuConnection.connect(ApplicationProvider.getApplicationContext())
        val shell = RootShell(startProcess = connection::start, requireRoot = false, accessName = "Shizuku", externalInputPipe = true, temporaryDirectory = "/data/local/tmp")
        val scratch = "/data/local/tmp/voyager-service-${UUID.randomUUID()}"
        ShizukuFileProvider(connection).use { provider ->
            try {
                shell.execute("mkdir -- '$scratch'")
                block(provider, shell, scratch)
            } finally {
                runCatching { shell.execute("rm -rf -- '$scratch'") }
                shell.close()
            }
        }
    }

    private fun RootShell.text(script: String) = execute(script).toString(Charsets.UTF_8)
    private suspend fun ShizukuFileProvider.names(path: String) = listFiles(path).getOrThrow().map { it.name }.sorted()

    @Test fun replacingAFileKeepsItsModeAndContext() = session { provider, shell, scratch ->
        shell.execute("printf old > '$scratch/file'; chmod 640 '$scratch/file'")
        val before = shell.text("stat -c '%a %u:%g' '$scratch/file'; ls -Zd '$scratch/file' | cut -d ' ' -f 1")
        provider.getOutputStream("$scratch/file").getOrThrow().use { it.write("new".toByteArray()) }
        assertEquals("new", shell.text("cat '$scratch/file'"))
        assertEquals(before, shell.text("stat -c '%a %u:%g' '$scratch/file'; ls -Zd '$scratch/file' | cut -d ' ' -f 1"))
        assertEquals(listOf("file"), provider.names(scratch))
    }

    @Test fun anOriginalChangedDuringTheWriteIsKept() = session { provider, shell, scratch ->
        shell.execute("printf old > '$scratch/file'")
        val output = provider.getOutputStream("$scratch/file").getOrThrow()
        output.write("new".toByteArray())
        shell.execute("printf changed > '$scratch/file'")
        assertTrue(runCatching { output.close() }.isFailure)
        assertEquals("changed", shell.text("cat '$scratch/file'"))
        assertEquals(listOf("file"), provider.names(scratch))
    }

    @Test fun anAbortedWriteLeavesNoStageAndKeepsTheOriginal() = session { provider, shell, scratch ->
        shell.execute("printf old > '$scratch/file'")
        val output = provider.getOutputStream("$scratch/file").getOrThrow()
        output.write(ByteArray(300_000))
        (output as TransferAbortable).abortTransfer()
        assertEquals("old", shell.text("cat '$scratch/file'"))
        assertEquals(listOf("file"), provider.names(scratch))
    }

    @Test fun failuresReachTheCallerAndTheServiceKeepsRunning() = session { provider, _, scratch ->
        assertTrue(provider.getInputStream("$scratch/missing").isFailure)
        assertTrue(provider.createDirectory("$scratch/missing", "child").isFailure)
        assertTrue(provider.delete("$scratch/missing").isFailure)
        assertTrue(provider.listFiles("/data/data").isFailure)
        assertEquals(emptyList<String>(), provider.names(scratch))
    }

    @Test fun specialFilesAndLinksAreNotOpened() = session { provider, shell, scratch ->
        shell.execute("mkfifo '$scratch/fifo'; printf keep > '$scratch/target'; ln -s target '$scratch/link'")
        withTimeout(10_000) {
            assertTrue(provider.getInputStream("$scratch/fifo").isFailure)
            assertTrue(provider.getInputStream("$scratch/link").isFailure)
            assertTrue(provider.getOutputStream("$scratch/link").isFailure)
            assertTrue(provider.getOutputStream("$scratch/fifo").isFailure)
        }
        assertEquals("keep", shell.text("cat '$scratch/target'"))
    }

    @Test fun deletingAFolderRemovesLinksWithoutFollowingThem() = session { provider, shell, scratch ->
        shell.execute("mkdir -p '$scratch/victim' '$scratch/tree/sub'; printf keep > '$scratch/victim/keep'; " +
            "ln -s ../victim '$scratch/tree/dirlink'; ln -s ../../victim/keep '$scratch/tree/sub/filelink'; printf x > '$scratch/tree/sub/file'")
        provider.delete("$scratch/tree").getOrThrow()
        assertFalse(provider.exists("$scratch/tree"))
        assertEquals("keep", shell.text("cat '$scratch/victim/keep'"))
    }

    @Test fun foldersWithNamesJavaCannotAddressAreStillDeleted() = session { provider, shell, scratch ->
        shell.execute("mkdir -p '$scratch/tree/sub'; : > \"$scratch/tree/sub/\$(printf 'latin\\351')\"; printf x > '$scratch/tree/plain'")
        assertTrue("A name Java can't address must not be hidden", provider.listFiles("$scratch/tree/sub").isFailure)
        provider.delete("$scratch/tree").getOrThrow()
        assertFalse(provider.exists("$scratch/tree"))
    }

    @Test fun movesRenameAndRefuseExistingTargets() = session { provider, shell, scratch ->
        shell.execute("printf a > '$scratch/a'; printf b > '$scratch/b'; mkdir '$scratch/dir'")
        assertTrue(provider.rename("$scratch/a", "b").isFailure)
        provider.move("$scratch/a", "$scratch/dir").getOrThrow()
        assertEquals("a", shell.text("cat '$scratch/dir/a'"))
        assertEquals("b", shell.text("cat '$scratch/b'"))
    }

    @Test fun largeListingsAndReadsArriveComplete() = session { provider, shell, scratch ->
        val prefix = "n".repeat(180)
        shell.execute("cd '$scratch' && i=0; while [ \$i -lt 6000 ]; do : > \"$prefix\$i\"; i=\$((i+1)); done", 64)
        assertEquals(6000, provider.listFiles(scratch).getOrThrow().size)
        val bytes = ByteArray(5_000_000) { (it * 31).toByte() }
        val file = provider.createFile(scratch, "big.bin").getOrThrow()
        provider.getOutputStream(file.path).getOrThrow().use { it.write(bytes) }
        assertArrayEquals(bytes, provider.getInputStream(file.path).getOrThrow().use { it.readBytes() })
    }

    @Test fun textEditorRoundTripUsesServiceFingerprints() = session { provider, shell, scratch ->
        shell.execute("printf 'hello' > '$scratch/note.txt'")
        val document = provider.readText("$scratch/note.txt").getOrThrow()
        assertEquals("hello", document.text)
        provider.saveText(document, "changed").getOrThrow()
        assertEquals("changed", shell.text("cat '$scratch/note.txt'"))
        assertTrue("A stale document must not overwrite", provider.saveText(document, "stale").isFailure)
    }
}
