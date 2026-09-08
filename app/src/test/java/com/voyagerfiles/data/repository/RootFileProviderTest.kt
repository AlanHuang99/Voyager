package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RootFileProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun shell() = RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false)

    @Test fun quotedAndNewlineNamesAreListedAndMutatedLiterally() = runBlocking {
        val directory = temporary.newFolder()
        RootFileProvider(shell()).use { provider ->
            val name = "quote'\n\$(touch INJECTED); space.txt"
            val file = provider.createFile(directory.path, name).getOrThrow()
            assertEquals(FileSource.ROOT, file.source)
            provider.getOutputStream(file.path).getOrThrow().use { it.write("hello".toByteArray()) }
            assertEquals(listOf(name), provider.listFiles(directory.path).getOrThrow().map { it.name })
            assertEquals("hello", provider.getInputStream(file.path).getOrThrow().bufferedReader().use { it.readText() })
            val renamed = provider.rename(file.path, "new'\n.txt").getOrThrow()
            assertEquals("hello", File(renamed.path).readText())
            provider.delete(renamed.path).getOrThrow()
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun textSaveChecksOriginalAndPreservesFileOnFailure() = runBlocking {
        val file = temporary.newFile("config")
        file.writeText("original\n")
        RootFileProvider(shell()).use { provider ->
            val original = provider.readText(file.path).getOrThrow()
            file.writeText("external edit\n")
            assertTrue(provider.saveText(original, "replacement").isFailure)
            assertEquals("external edit\n", file.readText())
            val current = provider.readText(file.path).getOrThrow()
            provider.saveText(current, "saved\n").getOrThrow()
            assertEquals("saved\n", file.readText())
            assertEquals(listOf("config"), file.parentFile!!.list()!!.toList())
        }
    }

    @Test fun editorRejectsBinaryLargeAndSymlinkFiles() = runBlocking {
        val file = temporary.newFile("content")
        RootFileProvider(shell()).use { provider ->
            file.writeBytes(byteArrayOf(0, 1, 2))
            assertTrue(provider.readText(file.path).isFailure)
            file.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
            assertTrue(provider.readText(file.path).isFailure)
            file.writeBytes(ByteArray(300_000) { 65 })
            assertTrue(provider.readText(file.path).isFailure)
            val link = File(file.parent, "link")
            java.nio.file.Files.createSymbolicLink(link.toPath(), file.toPath())
            assertTrue(provider.readText(link.path).isFailure)
        }
    }

    @Test fun listingRejectsUndecodableNamesInsteadOfChangingTheirIdentity() = runBlocking {
        val directory = temporary.newFolder()
        val shell = shell()
        shell.execute("touch -- ${RootShell.quote(directory.path)}/\"\$(printf '\\377')\"")
        RootFileProvider(shell).use { root -> assertTrue(root.listFiles(directory.path).isFailure) }
    }

    @Test fun missingFilesAndDeniedRootRemainErrors() = runBlocking {
        RootFileProvider(shell()).use { provider ->
            assertTrue(provider.listFiles("${temporary.root}/missing").isFailure)
            assertFalse(provider.exists("${temporary.root}/missing"))
            assertTrue(provider.delete("/").isFailure)
            assertTrue(provider.createFile(temporary.root.path, "../escape").isFailure)
        }
        RootFileProvider(RootShell(startProcess = {
            ProcessBuilder("sh", "-c", "echo 'Permission denied' >&2; exit 1").start()
        })).use { provider ->
            val error = provider.listFiles("/").exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message!!.contains("Permission denied"))
        }
    }

    @Test fun shellBoundsOutputAndReportsNonzeroExitAtEof() {
        shell().use { shell ->
            assertThrows(IOException::class.java) { shell.execute("head -c 10000 /dev/zero", maxBytes = 100) }
            assertThrows(IOException::class.java) {
                shell.input("printf partial; echo 'Read-only file system' >&2; exit 1").use { it.readBytes() }
            }
        }
    }

    @Test fun fileStreamsExceedMetadataOutputLimitWithoutBufferingWholeFile() = runBlocking {
        val file = temporary.newFile("large")
        RootFileProvider(shell()).use { root ->
            val chunk = ByteArray(32 * 1024) { (it % 251).toByte() }
            root.getOutputStream(file.path).getOrThrow().use { output -> repeat(160) { output.write(chunk) } }
            var total = 0L
            root.getInputStream(file.path).getOrThrow().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) assertEquals(chunk[((total + index) % chunk.size).toInt()], buffer[index])
                    total += count
                }
            }
            assertEquals(5L * 1024 * 1024, total)
        }
    }

    @Test fun sessionCloseTerminatesOutstandingStreamsAndDisallowsNewCommands() {
        val shell = shell()
        val input = shell.input("exec cat /dev/zero")
        assertEquals(0, input.read())
        shell.close()
        assertThrows(IOException::class.java) { shell.execute("printf unexpected") }
        input.close()
    }

    @Test fun commandTimeoutTerminatesBlockedReads() {
        RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false, timeoutMillis = 150).use { shell ->
            assertThrows(IOException::class.java) { shell.execute("exec sleep 10") }
        }
    }

    @Test fun rootAndLocalAliasesAreTheSameFile() = runBlocking {
        val directory = temporary.newFolder()
        val file = File(directory, "file").apply { writeText("keep") }
        val alias = File(temporary.root, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), directory.toPath())
        RootFileProvider(shell()).use { root ->
            val local = LocalFileProvider()
            assertTrue(root.isSamePath(file.path, local, "${alias.path}/file"))
            assertTrue(local.isSamePath("${alias.path}/file", root, file.path))
            assertTrue(root.isDescendantPath(directory.path, "${alias.path}/child"))
            assertTrue(root.copy(directory.path, alias.path).isFailure)
            assertEquals(listOf("file"), directory.list()!!.toList())
        }
    }

    @Test fun streamCloseRejectsConcurrentReplacement() = runBlocking {
        val file = temporary.newFile("file").apply { writeText("original") }
        RootFileProvider(shell()).use { root ->
            val output = root.getOutputStream(file.path).getOrThrow()
            output.write("my edit".toByteArray())
            file.writeText("other edit")
            assertThrows(IOException::class.java) { output.close() }
            assertEquals("other edit", file.readText())
        }
    }

    @Test fun failedTransferAndAbortedOutputPreserveExistingContents() = runBlocking {
        val file = temporary.newFile("original").apply { writeText("keep") }
        RootFileProvider(shell()).use { root ->
            val output = root.getOutputStream(file.path).getOrThrow()
            output.write("partial".toByteArray())
            (output as TransferAbortable).abortTransfer()
            output.close()
            assertEquals("keep", file.readText())
            val failingInput = object : java.io.InputStream() {
                var first = true
                override fun read(): Int { if (first) { first = false; return 65 }; throw IOException("input failed") }
            }
            assertTrue(root.writeStream(file.path, failingInput, "input", null).isFailure)
            assertEquals("keep", file.readText())
            assertEquals(listOf("original"), temporary.root.list()!!.toList())
        }
    }

    @Test fun savePreservesModeAndRejectsChangedPermissionsAndHardLinks() = runBlocking {
        val file = temporary.newFile("config").apply { writeText("old") }
        val mode = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----")
        java.nio.file.Files.setPosixFilePermissions(file.toPath(), mode)
        RootFileProvider(shell()).use { root ->
            root.saveText(root.readText(file.path).getOrThrow(), "new").getOrThrow()
            assertEquals(mode, java.nio.file.Files.getPosixFilePermissions(file.toPath()))
            val document = root.readText(file.path).getOrThrow()
            file.setExecutable(true)
            assertTrue(root.saveText(document, "changed").isFailure)
            java.nio.file.Files.createLink(File(temporary.root, "hardlink").toPath(), file.toPath())
            assertTrue(root.saveText(root.readText(file.path).getOrThrow(), "changed").isFailure)
            assertEquals("new", file.readText())
        }
    }
}
