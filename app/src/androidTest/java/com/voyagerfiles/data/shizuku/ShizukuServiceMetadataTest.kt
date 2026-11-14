package com.voyagerfiles.data.shizuku

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.repository.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The service's listing and path resolution against the shell commands they replace, in the debug
 * app's cache (CheckJNI on, no Shizuku grant needed).
 */
class ShizukuServiceMetadataTest {
    private fun fixture(block: (File, ShizukuFileOperations) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir.canonicalFile, "shizuku-metadata-${UUID.randomUUID()}").apply { mkdir() }
        try { block(directory, ShizukuFileOperations(Process.myUid())) }
        finally { sh("rm -rf -- ${RootShell.quote(directory.path)}") }
    }

    private class Result(val status: Int, val output: ByteArray)

    private fun sh(script: String): Result {
        val process = ProcessBuilder("sh", "-c", script).start()
        val errors = Thread { process.errorStream.use { it.readBytes() } }.apply { start() }
        val output = process.inputStream.use { it.readBytes() }
        errors.join()
        return Result(process.waitFor(), output)
    }

    /** toybox `readlink -f`, or null when it fails. */
    private fun readlinkF(path: String): String? = sh("readlink -f -- ${RootShell.quote(path)}").let { result ->
        if (result.status != 0 || result.output.isEmpty()) null else result.output.toString(Charsets.UTF_8).removeSuffix("\n")
    }

    private fun serviceCanonical(operations: ShizukuFileOperations, path: String): String? =
        try { operations.canonical(path) } catch (_: IOException) { null }

    @Test fun pathResolutionMatchesReadlinkF() = fixture { directory, operations ->
        val d = RootShell.quote(directory.path)
        assertEquals(0, sh(
            "cd $d && mkdir -p real/sub && : > real/file && ln -s real dirlink && ln -s ./real/../real/sub rel && " +
                "ln -s dirlink chain && ln -s real/missing dangling && ln -s nowhere/missing dangling-dir && " +
                "ln -s loop-b loop-a && ln -s loop-a loop-b && ln -s real/file filelink && ln -s \"\$PWD/real\" absolute && " +
                // ".." after a link in a dangling target is physical: deep/.. is real, not the fixture.
                "ln -s real/sub deep && ln -s deep/../missing physical && ln -s deep/../sub physical-existing && ln -s missing/.. dotdot",
        ).status)
        val cases = listOf(
            "", "real", "real/sub", "real/file", "real/missing", "missing/child", "dirlink", "dirlink/sub", "dirlink/missing",
            "rel", "chain", "chain/sub", "dangling", "dangling-dir", "loop-a", "loop-a/x", "filelink", "filelink/x",
            "real/file/x", "absolute/sub", "real/sub/../file", "physical", "physical-existing", "dotdot",
        )
        for (case in cases) {
            val path = if (case.isEmpty()) directory.path else "${directory.path}/$case"
            val normalized = File(path).toPath().normalize().toString()
            assertEquals("canonical of $case", readlinkF(normalized), serviceCanonical(operations, normalized))
        }
        assertEquals("/", operations.canonical("/"))
        assertTrue(runCatching { operations.canonical("relative") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { operations.list("relative") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun listingMatchesTheShellListing() = fixture { directory, operations ->
        val d = RootShell.quote(directory.path)
        assertEquals(0, sh(
            "cd $d && : > plain && : > .hidden && : > '..dots' && mkdir folder && ln -s plain link && ln -s missing dangling && " +
                ": > \"quote'and space\" && : > \"\$(printf 'new\\nline')\" && : > '\uFFFD'",
        ).status)
        val shell = sh(
            "d=$d; for p in \"\$d\"/* \"\$d\"/.[!.]* \"\$d\"/..?*; do [ -e \"\$p\" ] || [ -L \"\$p\" ] || continue; " +
                "printf '%s\\0' \"\$p\"; stat -c '%f %s %Y %u:%g' -- \"\$p\" || exit 1; done",
        )
        assertEquals(0, shell.status)
        assertEquals(entries(shell.output), entries(operations.list(directory.path)))
    }

    @Test fun namesThatAreNotUtf8FailInsteadOfAliasing() = fixture { directory, operations ->
        val d = RootShell.quote(directory.path)
        assertEquals(0, sh("cd $d && : > '\uFFFD' && mkdir bad && : > bad/\"\$(printf '\\377')\" && ln -s \"\$(printf '\\377')\" bad/link && ln -s bad bad-link").status)
        // A real file named U+FFFD stays addressable on its own.
        assertTrue(entries(operations.list(directory.path)).any { it.first.endsWith("/\uFFFD") })
        assertTrue("A raw 0xFF name must fail the listing", runCatching { operations.list("${directory.path}/bad") }.exceptionOrNull() is IOException)
        assertTrue("A link to a raw 0xFF name must not resolve to U+FFFD", runCatching { operations.canonical("${directory.path}/bad/link") }.exceptionOrNull() is IOException)
        assertEquals("${directory.path}/bad", operations.canonical("${directory.path}/bad-link"))
    }

    @Test fun listingFailsOnUnsearchableAndMissingFolders() = fixture { directory, operations ->
        val folder = File(directory, "unsearchable").apply { mkdir() }
        File(folder, "file").writeText("keep")
        android.system.Os.chmod(folder.path, "400".toInt(8))
        try {
            assertTrue(runCatching { operations.list(folder.path) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { operations.list("${directory.path}/missing") }.exceptionOrNull() is IOException)
            assertTrue(runCatching { operations.list("${directory.path}/unsearchable/file") }.exceptionOrNull() is IOException)
        } finally { android.system.Os.chmod(folder.path, "700".toInt(8)) }
    }

    /** Listing records as (path, metadata) pairs, in no particular order. */
    private fun entries(bytes: ByteArray): Set<Pair<String, String>> {
        val result = mutableSetOf<Pair<String, String>>()
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset until bytes.size).first { bytes[it] == 0.toByte() }
            val line = (end + 1 until bytes.size).first { bytes[it] == 10.toByte() }
            val path = ByteArrayOutputStream().apply { write(bytes, offset, end - offset) }.toString("UTF-8")
            result.add(File(path).toPath().normalize().toString() to bytes.copyOfRange(end + 1, line).toString(Charsets.UTF_8))
            offset = line + 1
        }
        return result
    }
}
