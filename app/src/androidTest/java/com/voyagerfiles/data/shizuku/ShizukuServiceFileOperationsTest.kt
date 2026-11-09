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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.voyagerfiles.app.ShizukuDeathTokenService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test fun failuresReachTheCallerAndTheServiceKeepsRunning() = session { provider, shell, scratch ->
        assertTrue(provider.getInputStream("$scratch/missing").isFailure)
        assertTrue(provider.createDirectory("$scratch/missing", "child").isFailure)
        assertTrue(provider.delete("$scratch/missing").isFailure)
        if (shell.text("id -u").trim() == "2000") assertTrue(provider.listFiles("/data/data").isFailure)
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

    @Test fun aRealClientProcessDeathDiscardsTheStageAndFreesItsSlot() = runBlocking<Unit> {
        enabled()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connected = CountDownLatch(1)
        var token: IBinder? = null
        val tokenConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { token = service; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val testContext = InstrumentationRegistry.getInstrumentation().context
        assertTrue(context.bindService(Intent(testContext, ShizukuDeathTokenService::class.java), tokenConnection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Separate client process must bind", connected.await(10, TimeUnit.SECONDS))
            val client = checkNotNull(token)
            val death = CountDownLatch(1)
            client.linkToDeath({ death.countDown() }, 0)
            val connection = ShizukuConnection.connect(context)
            val shell = RootShell(startProcess = connection::start, requireRoot = false, externalInputPipe = true, temporaryDirectory = "/data/local/tmp")
            val scratch = "/data/local/tmp/voyager-client-death-${UUID.randomUUID()}"
            var output: java.io.OutputStream? = null
            try {
                shell.execute("mkdir -- '$scratch'; printf original > '$scratch/file'")
                val write = connection.files { it.openWrite("$scratch/file", "", client) }
                output = ParcelFileDescriptor.AutoCloseOutputStream(connection.remote { write.input() })
                output.write("incomplete".toByteArray())
                val data = Parcel.obtain()
                try { client.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY) } finally { data.recycle() }
                assertTrue("Client process must actually die", death.await(10, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var listing = ""
                do {
                    listing = shell.text("ls -A '$scratch'").trim()
                    if (listing == "file") break
                    Thread.sleep(25)
                } while (System.nanoTime() < deadline)
                assertEquals("file", listing)
                assertEquals("original", shell.text("cat '$scratch/file'"))
                assertTrue(runCatching { connection.remote { write.commit() } }.isFailure)
                // Eight simultaneous new writes establish that the dead client's slot was released.
                var admitted = 0
                do {
                    val replacements = mutableListOf<IShizukuWrite>()
                    try {
                        runCatching { repeat(8) { replacements += connection.files { it.openWrite("$scratch/file", "", android.os.Binder()) } } }
                        admitted = replacements.size
                    } finally { replacements.forEach { runCatching { it.abort() } } }
                    if (admitted == 8) break
                    Thread.sleep(50)
                } while (System.nanoTime() < deadline)
                assertEquals("Dead client must release its operation slot", 8, admitted)
            } finally {
                runCatching { output?.close() }
                runCatching { shell.execute("rm -rf -- '$scratch'") }
                shell.close(); connection.close()
            }
        } finally { context.unbindService(tokenConnection) }
    }

    @Test fun closingASessionDiscardsItsOutstandingWrite() = session { _, shell, scratch ->
        shell.execute("printf original > '$scratch/file'")
        val separate = ShizukuFileProvider(ShizukuConnection.connect(ApplicationProvider.getApplicationContext()))
        val output = separate.getOutputStream("$scratch/file").getOrThrow()
        try {
            output.write("incomplete".toByteArray())
            separate.close()
            assertTrue(runCatching { output.close() }.isFailure)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var listing = ""
            do {
                listing = shell.text("ls -A '$scratch'").trim()
                if (listing == "file") break
                Thread.sleep(25)
            } while (System.nanoTime() < deadline)
            assertEquals("file", listing)
            assertEquals("original", shell.text("cat '$scratch/file'"))
        } finally { separate.close(); runCatching { (output as TransferAbortable).abortTransfer() } }
    }
}
