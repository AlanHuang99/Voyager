package com.voyagerfiles.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Runs the actual command scripts against Android toybox without requiring a rooted device. */
class RootFileProviderAndroidTest {
    @Test fun toyboxListingEditingAndFileOperations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "root-commands-${UUID.randomUUID()}").apply { mkdir() }
        val shell = RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false)
        try {
            RootFileProvider(shell).use { root ->
                val file = root.createFile(folder.path, "quote'\n\$(id); config").getOrThrow()
                root.getOutputStream(file.path).getOrThrow().use { it.write("original\n".toByteArray()) }
                assertEquals(file.name, root.listFiles(folder.path).getOrThrow().single().name)
                val document = root.readText(file.path).getOrThrow()
                assertEquals("original\n", document.text)
                root.saveText(document, "saved\n").getOrThrow()
                assertEquals("saved\n", File(file.path).readText())
                assertTrue(root.saveText(document, "stale").isFailure)
                val dest = root.createDirectory(folder.path, "dest").getOrThrow()
                root.copy(file.path, dest.path).getOrThrow()
                assertEquals("saved\n", File(dest.path, file.name).readText())
                val renamed = root.rename(file.path, "renamed").getOrThrow()
                root.move(renamed.path, dest.path).getOrThrow()
                assertFalse(File(renamed.path).exists())
                root.delete(dest.path).getOrThrow()
                assertTrue(root.listFiles(folder.path).getOrThrow().isEmpty())
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun toyboxSupervisorTerminatesChildTreesOnTimeoutCancelAndSessionClose() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "root-lifecycle-${UUID.randomUUID()}").apply { mkdir() }
        try {
            for (action in listOf("timeout", "cancel", "close")) {
                val victim = File(directory, "victim").apply { writeText("original") }
                val runtime = File(directory, "runtime").apply { mkdir() }
                val shell = RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false,
                    timeoutMillis = if (action == "timeout") 500 else 30_000, temporaryDirectory = runtime.path)
                val input = shell.input("printf ready; trap '' TERM; sleep 2; printf late > ${RootShell.quote(victim.path)}")
                assertEquals('r'.code, input.read())
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val reader = executor.submit<ByteArray> { input.readBytes() }
                    val started = System.nanoTime()
                    when (action) {
                        "cancel" -> (input as TransferAbortable).abortTransfer()
                        "close" -> shell.close()
                    }
                    runCatching { reader.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    assertTrue("$action must release the blocked reader", reader.isDone)
                    assertTrue("$action must return promptly", (System.nanoTime() - started) / 1_000_000 < 1500)
                    Thread.sleep(2100)
                    assertEquals("original", victim.readText())
                    assertTrue("$action must remove supervisor runtime files", runtime.list()!!.isEmpty())
                } finally { shell.close(); executor.shutdownNow() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun toyboxSupervisorAbortsABlockedWriterWithoutLateMutation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "root-writer-${UUID.randomUUID()}").apply { mkdir() }
        val victim = File(directory, "victim").apply { writeText("original") }
        val marker = File(directory, "started")
        val runtime = File(directory, "runtime").apply { mkdir() }
        val shell = RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false, temporaryDirectory = runtime.path)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val output = shell.output("trap '' TERM; printf started > ${RootShell.quote(marker.path)}; sleep 2; printf late > ${RootShell.quote(victim.path)}")
            val writer = executor.submit { output.write(ByteArray(512 * 1024)) }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
            while (marker.length() != 7L && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("started", marker.readText())
            Thread.sleep(100)
            assertFalse(writer.isDone)
            val started = System.nanoTime()
            (output as TransferAbortable).abortTransfer()
            assertTrue("Abort must return promptly", (System.nanoTime() - started) / 1_000_000 < 1500)
            runCatching { writer.get(1, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(writer.isDone)
            Thread.sleep(2100)
            assertEquals("original", victim.readText())
            assertTrue(runtime.list()!!.isEmpty())
        } finally { shell.close(); executor.shutdownNow(); directory.deleteRecursively() }
    }

    @Test fun toyboxBinaryStreamsPreserveBytesAcrossProtocolFrames() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "root-stream-${UUID.randomUUID()}").apply { mkdir() }
        try {
            RootFileProvider(RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false)).use { root ->
                val file = root.createFile(directory.path, "binary").getOrThrow()
                val bytes = ByteArray(35 * 1024 + 17) { (it % 251).toByte() }
                root.getOutputStream(file.path).getOrThrow().use { it.write(bytes) }
                assertArrayEquals(bytes, root.getInputStream(file.path).getOrThrow().use { it.readBytes() })
                assertEquals(listOf("binary"), directory.list()!!.toList())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun superuserResultIsExplicitAndLocalBrowsingStillWorks() = runBlocking {
        RootFileProvider().use { root ->
            val result = root.listFiles("/")
            result.fold(
                onSuccess = { items -> assertTrue(items.all { it.source == com.voyagerfiles.data.model.FileSource.ROOT }) },
                onFailure = { error -> assertFalse(error.message.isNullOrBlank()) },
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(LocalFileProvider().listFiles(context.cacheDir.path).isSuccess)
    }
}
