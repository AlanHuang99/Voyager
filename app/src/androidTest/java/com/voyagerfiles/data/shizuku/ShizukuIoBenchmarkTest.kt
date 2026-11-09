package com.voyagerfiles.data.shizuku

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.RootFileProvider
import com.voyagerfiles.data.repository.RootShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Same-process, same-session comparison of the original shell backend and service I/O. */
class ShizukuIoBenchmarkTest {
    @Test fun compareShellAndServiceIo() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with shizuku=true and shizukuBenchmark=true", arguments.getString("shizuku") == "true" && arguments.getString("shizukuBenchmark") == "true")
        val repetitions = (arguments.getString("benchmarkRepetitions")?.toInt() ?: 3).also { require(it in 1..10) }
        val byteCount = (arguments.getString("benchmarkBytes")?.toInt() ?: 131_072).also { require(it in 4096..4_194_304) }
        val smallFiles = (arguments.getString("benchmarkSmallFiles")?.toInt() ?: 3).also { require(it in 1..20) }
        val payload = ByteArray(byteCount) { (it * 37 + 11).toByte() }
        val connection = ShizukuConnection.connect(ApplicationProvider.getApplicationContext())
        val shell = RootShell(startProcess = connection::start, requireRoot = false, accessName = "Shizuku", externalInputPipe = true, temporaryDirectory = "/data/local/tmp")
        val baseline = RootFileProvider(shell, FileSource.SHIZUKU)
        val candidate = ShizukuFileProvider(connection)
        val parent = "/storage/emulated/0/Android/data/com.voyagerfiles.debug.test/files"
        val scratch = "$parent/voyager-io-benchmark-${UUID.randomUUID()}"
        try {
            shell.execute("mkdir -p -- '${scratch}'")
            Log.i(TAG, "uid=${connection.uid} bytes=$byteCount repetitions=$repetitions smallFiles=$smallFiles")
            repeat(repetitions) { repetition ->
                // Alternate ordering to avoid assigning all cold-start work to one backend.
                val backends = listOf("shell" to baseline, "service" to candidate).let { if (repetition % 2 == 0) it else it.reversed() }
                for ((mode, provider) in backends) {
                    val directory = provider.createDirectory(scratch, "$mode-$repetition").getOrThrow().path
                    val path = provider.createFile(directory, "large").getOrThrow().path
                    measure(mode, repetition, "write", byteCount.toLong()) {
                        provider.getOutputStream(path).getOrThrow().use { it.write(payload) }
                    }
                    var actual = byteArrayOf()
                    measure(mode, repetition, "read", byteCount.toLong()) {
                        actual = provider.getInputStream(path).getOrThrow().use { it.readBytes() }
                    }
                    assertArrayEquals(payload, actual)
                    val tiny = ByteArray(4096) { (it * 19).toByte() }
                    val started = System.nanoTime()
                    repeat(smallFiles) { number ->
                        val small = provider.createFile(directory, "small-$number").getOrThrow().path
                        provider.getOutputStream(small).getOrThrow().use { it.write(tiny) }
                    }
                    record(mode, repetition, "small-files", smallFiles.toLong(), System.nanoTime() - started)
                    repeat(smallFiles) { number ->
                        assertArrayEquals(tiny, provider.getInputStream("$directory/small-$number").getOrThrow().use { it.readBytes() })
                    }
                    assertEquals(smallFiles + 1, provider.listFiles(directory).getOrThrow().size)
                }
            }
        } finally {
            runCatching { shell.execute("rm -rf -- '$scratch'") }
            baseline.close()
            candidate.close()
        }
    }

    private inline fun measure(mode: String, repetition: Int, operation: String, units: Long, block: () -> Unit) {
        val started = System.nanoTime()
        block()
        record(mode, repetition, operation, units, System.nanoTime() - started)
    }

    private fun record(mode: String, repetition: Int, operation: String, units: Long, elapsed: Long) {
        Log.i(TAG, "mode=$mode repetition=$repetition operation=$operation units=$units elapsedNs=$elapsed")
    }

    private companion object { const val TAG = "VoyagerShizukuBenchmark" }
}
