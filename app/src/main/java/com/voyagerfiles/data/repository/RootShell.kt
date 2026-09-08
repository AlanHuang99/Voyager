package com.voyagerfiles.data.repository

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only explicitly requested privileged commands. File data is streamed through pipes. */
class RootShell internal constructor(
    private val startProcess: (String) -> Process = { ProcessBuilder("su", "-c", it).start() },
    private val requireRoot: Boolean = true,
    private val timeoutMillis: Long = 30_000,
) : Closeable {
    private val commands = ConcurrentHashMap.newKeySet<Command>()
    private var closed = false

    @Synchronized private fun start(script: String): Command {
        if (closed) throw IOException("Root session is closed")
        if (commands.size >= 8) throw IOException("Too many active root operations")
        val guard = if (requireRoot) "[ \"\$(id -u)\" = 0 ] || { echo 'Root access denied' >&2; exit 1; }; " else ""
        val process = try {
            startProcess(guard + script)
        } catch (error: IOException) {
            throw IOException("Root access unavailable. Install or authorize a compatible su manager.", error)
        }
        return Command(process).also { commands.add(it) }
    }

    fun execute(script: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray = input(script).use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > maxBytes) throw IOException("Root command output exceeds the allowed limit")
            bytes.write(buffer, 0, count)
        }
        bytes.toByteArray()
    }

    fun input(script: String): InputStream {
        val command = start(script)
        command.process.outputStream.close()
        return object : InputStream(), TransferAbortable {
            override fun read(): Int = ByteArray(1).let { if (read(it) < 0) -1 else it[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                command.touch()
                val count = command.process.inputStream.read(bytes, offset, length)
                command.touch()
                if (count < 0) command.finish()
                return count
            }
            override fun close() = command.abort()
            override fun abortTransfer() = command.abort()
        }
    }

    fun output(script: String): OutputStream {
        val command = start(script)
        return object : OutputStream(), TransferAbortable {
            private var finished = false
            override fun write(byte: Int) = write(byteArrayOf(byte.toByte()))
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                command.touch()
                command.process.outputStream.write(bytes, offset, length)
                command.touch()
            }
            override fun close() {
                if (finished) return
                finished = true
                try {
                    command.process.outputStream.close()
                    command.finish()
                } finally {
                    command.abort()
                }
            }
            override fun abortTransfer() { finished = true; command.abort() }
        }
    }

    @Synchronized override fun close() {
        closed = true
        commands.toList().forEach { it.abort() }
    }

    private inner class Command(val process: Process) {
        private val ended = AtomicBoolean(false)
        @Volatile private var lastActivity = System.nanoTime()
        @Volatile private var timedOut = false
        private val errors = ByteArrayOutputStream()
        private val stderrThread = Thread({
            runCatching {
                process.errorStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(errors) { errors.write(buffer, 0, count.coerceAtMost((16_384 - errors.size()).coerceAtLeast(0))) }
                    }
                }
            }
        }, "root-stderr").apply { isDaemon = true; start() }
        private val watchdog = timer.scheduleAtFixedRate({
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivity) >= timeoutMillis) {
                timedOut = true
                process.destroyForcibly()
            }
        }, timeoutMillis, timeoutMillis.coerceAtLeast(100), TimeUnit.MILLISECONDS)

        fun touch() { lastActivity = System.nanoTime() }
        fun finish() {
            try {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    timedOut = true
                    process.destroyForcibly()
                }
                stderrThread.join(1000)
                if (timedOut) throw IOException("Root operation timed out. Check the root authorization prompt and try again.")
                if (process.exitValue() != 0) {
                    val detail = synchronized(errors) { errors.toString("UTF-8").trim() }
                    throw IOException(detail.ifEmpty { "Root operation failed (exit ${process.exitValue()})" })
                }
            } finally { abort() }
        }
        fun abort() {
            if (!ended.compareAndSet(false, true)) return
            watchdog.cancel(false)
            process.destroyForcibly()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            commands.remove(this)
        }
    }

    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "root-timeout").apply { isDaemon = true }
        }
        internal fun quote(value: String): String {
            require('\u0000' !in value) { "A path cannot contain NUL" }
            return "'" + value.replace("'", "'\\''") + "'"
        }
    }
}
