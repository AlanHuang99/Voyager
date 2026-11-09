package com.voyagerfiles.data.shizuku

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real descriptor pumps in the app sandbox without requesting elevated access. */
class ShizukuTransferLifecycleTest {
    private fun fixture(block: (File, ShizukuFileOperations, CountDownLatch, AtomicInteger, TransferHooks) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "shizuku-lifecycle-${UUID.randomUUID()}").apply { mkdir() }
        val releases = AtomicInteger()
        val released = CountDownLatch(1)
        val hooks = TransferHooks({}, { it.message ?: "failure" }, { releases.incrementAndGet(); released.countDown() })
        try { block(directory, ShizukuFileOperations(Process.myUid()), released, releases, hooks) }
        finally { directory.deleteRecursively() }
    }

    @Test fun interruptingACommitDiscardsItsStageAndReleasesTheSlot() = fixture { directory, operations, released, releases, hooks ->
        val file = File(directory, "file").apply { writeText("original") }
        val write = ServiceWrite(hooks, operations.openWrite(file.path, null), Binder())
        val output = ParcelFileDescriptor.AutoCloseOutputStream(write.input())
        val failed = CountDownLatch(1)
        val commit = Thread {
            try { write.commit() } catch (_: IllegalStateException) { failed.countDown() }
        }
        try {
            commit.start()
            awaitWaiting(commit)
            commit.interrupt()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue("Interrupted commit must release its operation", released.await(3, TimeUnit.SECONDS))
            assertEquals(1, releases.get())
            assertEquals("original", file.readText())
            assertEquals(listOf("file"), directory.list()!!.toList())
        } finally { write.abort(); output.close(); commit.join(3000) }
    }

    @Test fun clientDeathCancelsACommitWaitingForEof() = fixture { directory, operations, released, releases, hooks ->
        val file = File(directory, "file").apply { writeText("original") }
        val write = ServiceWrite(hooks, operations.openWrite(file.path, null), Binder())
        val output = ParcelFileDescriptor.AutoCloseOutputStream(write.input())
        output.write("replacement".toByteArray())
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val commit = executor.submit { started.countDown(); write.commit() }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            write.binderDied()
            assertTrue(released.await(3, TimeUnit.SECONDS))
            assertTrue(runCatching { commit.get(3, TimeUnit.SECONDS) }.isFailure)
            assertEquals("original", file.readText())
            assertEquals(listOf("file"), directory.list()!!.toList())
            assertEquals(1, releases.get())
        } finally { write.abort(); output.close(); executor.shutdownNow() }
    }

    @Test fun abortAndCommitHaveOneCompleteOutcome() = fixture { directory, operations, _, _, _ ->
        repeat(20) {
            val file = File(directory, "file").apply { writeText("original") }
            val releases = AtomicInteger()
            val released = CountDownLatch(1)
            val write = ServiceWrite(TransferHooks({}, { it.message ?: "failure" }, { releases.incrementAndGet(); released.countDown() }), operations.openWrite(file.path, null), Binder())
            val bytes = ByteArray(300_001) { (it * 71).toByte() }
            ParcelFileDescriptor.AutoCloseOutputStream(write.input()).use { it.write(bytes) }
            val start = CountDownLatch(1)
            val committer = Thread { start.await(); runCatching { write.commit() } }
            val aborter = Thread { start.await(); write.abort() }
            committer.start(); aborter.start(); start.countDown()
            committer.join(5000); aborter.join(5000)
            assertFalse(committer.isAlive); assertFalse(aborter.isAlive)
            assertTrue(released.await(3, TimeUnit.SECONDS))
            val actual = file.readBytes()
            assertTrue("Commit or abort must produce complete bytes", actual.contentEquals(bytes) || actual.contentEquals("original".toByteArray()))
            assertEquals(1, releases.get())
            assertEquals(listOf("file"), directory.list()!!.toList())
        }
    }

    @Test fun eofAloneNeverReplacesTheOriginal() = fixture { directory, operations, released, _, hooks ->
        val file = File(directory, "file").apply { writeText("original") }
        val write = ServiceWrite(hooks, operations.openWrite(file.path, null), Binder())
        ParcelFileDescriptor.AutoCloseOutputStream(write.input()).use { it.write("replacement".toByteArray()) }
        assertEquals("original", file.readText())
        write.abort()
        assertTrue(released.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("file"), directory.list()!!.toList())
    }

    @Test fun producerFailureDoesNotLookLikeASuccessfulShortRead() = fixture { _, _, released, releases, hooks ->
        val read = ServiceRead(hooks) { output -> output.write(byteArrayOf(1, 2)); throw IOException("source failed") }
        assertArrayEquals(byteArrayOf(1, 2), ParcelFileDescriptor.AutoCloseInputStream(read.output()).use { it.readBytes() })
        assertTrue(runCatching { read.finish() }.exceptionOrNull()?.message?.contains("source failed") == true)
        assertTrue(released.await(3, TimeUnit.SECONDS))
        assertEquals(1, releases.get())
    }

    @Test fun aLargeFileFingerprintIncludesItsContent() = fixture { directory, operations, _, _, _ ->
        val file = File(directory, "large").apply { writeBytes(ByteArray(300_000) { 1 }) }
        val fingerprint = operations.fingerprint(file.path)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue("Large-file stale-save checks must retain the content hash", fingerprint.endsWith(expected))
    }

    @Test fun aStalledTakenWriterTimesOutWithoutClosingTheClientPipe() = fixture { directory, operations, released, _, hooks ->
        val file = File(directory, "file").apply { writeText("original") }
        val write = ServiceWrite(hooks, operations.openWrite(file.path, null), Binder())
        val output = ParcelFileDescriptor.AutoCloseOutputStream(write.input())
        try {
            assertTrue("A client retaining its idle pipe must lose its operation slot", released.await(35, TimeUnit.SECONDS))
            assertEquals("original", file.readText())
            assertEquals(listOf("file"), directory.list()!!.toList())
        } finally { write.abort(); output.close() }
    }

    @Test fun replacedStagePayloadCannotOverwriteAnUnrelatedFile() = fixture { directory, operations, released, _, hooks ->
        val file = File(directory, "file").apply { writeText("original") }
        val victim = File(directory, "victim").apply { writeText("keep") }
        val write = ServiceWrite(hooks, operations.openWrite(file.path, null), Binder())
        ParcelFileDescriptor.AutoCloseOutputStream(write.input()).use { it.write("replacement".toByteArray()) }
        val stage = directory.listFiles()!!.single { it.name.startsWith(".voyager-root-") }
        val payload = File(stage, "payload")
        assertTrue(payload.delete())
        android.system.Os.symlink(victim.path, payload.path)
        assertTrue(runCatching { write.commit() }.isFailure)
        assertTrue(released.await(3, TimeUnit.SECONDS))
        assertEquals("original", file.readText())
        assertEquals("keep", victim.readText())
        assertEquals(listOf("file", "victim"), directory.list()!!.sorted())
    }

    @Test fun replacingAParentWithALinkDoesNotRedirectAWrite() = fixture { directory, operations, released, _, hooks ->
        val parent = File(directory, "parent").apply { mkdir() }
        val file = File(parent, "file").apply { writeText("original") }
        val victim = File(directory, "victim").apply { mkdir() }
        File(victim, "file").writeText("keep")
        val write = ServiceWrite(hooks, operations.openWrite(file.path, null), Binder())
        ParcelFileDescriptor.AutoCloseOutputStream(write.input()).use { it.write("replacement".toByteArray()) }
        val moved = File(directory, "moved")
        assertTrue(parent.renameTo(moved))
        android.system.Os.symlink(victim.path, parent.path)
        try {
            runCatching { write.commit() }
            assertTrue(released.await(3, TimeUnit.SECONDS))
            assertEquals("keep", File(victim, "file").readText())
            assertEquals(listOf("file"), moved.list()!!.toList())
        } finally { write.abort(); android.system.Os.remove(parent.path) }
    }

    @Test fun listingDoesNotHideMetadataPermissionErrors() = fixture { directory, operations, _, _, _ ->
        val folder = File(directory, "unsearchable").apply { mkdir() }
        File(folder, "file").writeText("keep")
        android.system.Os.chmod(folder.path, "400".toInt(8))
        try {
            assertTrue("A failed entry stat must fail the listing", runCatching { operations.list(folder.path) }.isFailure)
        } finally { android.system.Os.chmod(folder.path, "700".toInt(8)) }
    }

    @Test fun undecodableNamesCannotAliasAValidReplacementCharacterName() = fixture { directory, operations, _, _, _ ->
        File(directory, "\uFFFD").writeText("valid filename")
        val quoted = com.voyagerfiles.data.repository.RootShell.quote(directory.path)
        val process = ProcessBuilder("sh", "-c", ": > $quoted/\"\$(printf '\\377')\"").start()
        assertEquals(0, process.waitFor())
        assertEquals(2, directory.list()!!.size)
        assertTrue("Lossily decoded names must not alias a valid filename", runCatching { operations.list(directory.path) }.isFailure)
    }

    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (thread.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("Commit must reach its EOF wait", Thread.State.TIMED_WAITING, thread.state)
    }
}
