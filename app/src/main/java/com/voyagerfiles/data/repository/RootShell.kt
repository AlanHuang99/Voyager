package com.voyagerfiles.data.repository

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Privileged supervision owns descendant termination; closing the su client is not cancellation. */
class RootShell internal constructor(
    private val startProcess: (String) -> Process = { ProcessBuilder("su", "-c", it).start() },
    private val requireRoot: Boolean = true,
    private val timeoutMillis: Long = 30_000,
    private val temporaryDirectory: String = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp",
) : Closeable {
    private val commands = ConcurrentHashMap.newKeySet<Command>()
    private var closed = false

    private fun start(script: String): Command {
        val command = synchronized(this) {
            if (closed) throw IOException("Root session is closed")
            if (commands.size >= 8) throw IOException("Too many active root operations")
            val token = UUID.randomUUID().toString()
            val workerScript = "IFS= read -r expected || exit 125; " +
                "[ \"\$\$\" = \"\$expected\" ] && kill -0 \"-\$\$\" 2>/dev/null || { echo 'Root worker isolation failed' >&2; exit 125; }; " +
                "printf '\\n%s:WORKER\\n' ${quote(token)} >&2; exec sh -c ${quote(script)}"
            val supervisor = supervisorTemplate.replace("@TOKEN@", quote(token))
                .replace("@DURATION@", (timeoutMillis / 1000.0).toString())
                .replace("@TEMP@", quote(temporaryDirectory))
                .replace("@SCRIPT@", quote(workerScript))
            val guard = if (requireRoot) "[ \"\$(id -u)\" = 0 ] || { echo 'Root access denied' >&2; exit 1; }; " else ""
            val process = try {
                // Launch in the background with job control disabled so setsid never has to fork away from the PID retained by the waiting su shell.
                startProcess(guard + "command -v setsid >/dev/null 2>&1 || { echo 'Root access requires setsid' >&2; exit 125; }; " +
                    "set +m; exec 9<&0; setsid sh -c ${quote(supervisor)} <&9 & launcher=\$!; wait \"\$launcher\"")
            } catch (error: IOException) {
                throw IOException("Root access unavailable. Install or authorize a compatible su manager.", error)
            }
            Command(process, token).also { commands.add(it) }
        }
        try {
            command.await("READY")
            command.frame("START")
            command.awaitWorker()
            return command
        } catch (error: Throwable) {
            command.abort()
            throw error
        }
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
        return object : InputStream(), TransferAbortable {
            override fun read(): Int = ByteArray(1).let { if (read(it) < 0) -1 else it[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                command.touch()
                val count = command.process.inputStream.read(bytes, offset, length)
                command.touch()
                if (count < 0) command.finish() else command.pingIfNeeded()
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
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                var position = offset
                while (position < offset + length) {
                    val end = (position + FRAME_BYTES).coerceAtMost(offset + length)
                    command.frame("D" + Base64.getEncoder().encodeToString(bytes.copyOfRange(position, end)))
                    position = end
                }
            }
            override fun close() {
                if (finished) return
                finished = true
                try {
                    command.frame("FINISH", acknowledge = false)
                    command.finish()
                } finally { command.abort() }
            }
            override fun abortTransfer() { finished = true; command.abort(); command.awaitStopped() }
        }
    }

    @Synchronized override fun close() {
        closed = true
        commands.toList().forEach { it.abort() }
    }

    private inner class Command(val process: Process, val token: String) {
        private val ended = AtomicBoolean(false)
        @Volatile private var lastActivity = System.nanoTime()
        @Volatile private var lastFrame = System.nanoTime()
        @Volatile private var timedOut = false
        @Volatile private var done: Int? = null
        private val replies = ArrayBlockingQueue<String>(16)
        private val workerReady = CountDownLatch(1)
        @Volatile private var workerStarted = false
        private val errors = ByteArrayOutputStream()
        private val stderrThread = Thread({
            try {
                process.errorStream.use { input ->
                    val line = ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) break
                        if (byte == 10) {
                            val text = line.toString("UTF-8")
                            if (text.startsWith("$token:")) {
                                val reply = text.removePrefix("$token:")
                                if (reply.startsWith("DONE:")) done = reply.removePrefix("DONE:").toIntOrNull()
                                if (reply == "WORKER") { workerStarted = true; workerReady.countDown() }
                                else replies.offer(reply)
                            } else synchronized(errors) {
                                val bytes = (text + "\n").toByteArray()
                                errors.write(bytes, 0, bytes.size.coerceAtMost((16_384 - errors.size()).coerceAtLeast(0)))
                            }
                            line.reset()
                        } else if (line.size() < 16_384) line.write(byte)
                    }
                    if (line.size() > 0) synchronized(errors) {
                        val bytes = line.toByteArray()
                        errors.write(bytes, 0, bytes.size.coerceAtMost((16_384 - errors.size()).coerceAtLeast(0)))
                    }
                }
            } catch (_: IOException) {
                // Closing a completed process may close stderr before the drainer returns.
            } finally { replies.offer("EOF"); workerReady.countDown() }
        }, "root-stderr").apply { isDaemon = true; start() }
        private val watchdog = timer.scheduleAtFixedRate({
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivity) >= timeoutMillis) {
                timedOut = true
                abort()
            }
        }, timeoutMillis, timeoutMillis.coerceAtLeast(100), TimeUnit.MILLISECONDS)

        fun touch() { lastActivity = System.nanoTime() }
        fun await(expected: String) {
            val reply = replies.poll(timeoutMillis + 1000, TimeUnit.MILLISECONDS)
            if (reply != expected) throw failure()
        }
        fun awaitWorker() {
            if (!workerReady.await(timeoutMillis + 1000, TimeUnit.MILLISECONDS) || !workerStarted) throw failure()
        }
        fun awaitStopped() { process.waitFor(1, TimeUnit.SECONDS) }
        fun frame(text: String, acknowledge: Boolean = true) {
            if (ended.get()) throw failure()
            touch()
            // At most 2050 bytes, below Linux PIPE_BUF. Only one acknowledged frame can be in flight, so abort never contends with a blocked large write.
            process.outputStream.write((text + "\n").toByteArray(Charsets.US_ASCII))
            process.outputStream.flush()
            lastFrame = System.nanoTime()
            if (acknowledge) await("ACK")
            touch()
        }
        fun pingIfNeeded() {
            if (done == null && process.isAlive && System.nanoTime() - lastFrame > TimeUnit.MILLISECONDS.toNanos((timeoutMillis / 3).coerceAtLeast(1))) {
                try { frame("PING") } catch (error: IOException) { if (done != 0) throw error }
            }
        }
        fun finish() {
            try {
                if (!process.waitFor(timeoutMillis + 1000, TimeUnit.MILLISECONDS)) throw failure()
                stderrThread.join(1000)
                if (timedOut || ended.get() || done != 0) throw failure()
            } finally { abort() }
        }
        private fun failure(): IOException {
            val detail = synchronized(errors) { errors.toString("UTF-8").trim() }
            return IOException(if (timedOut) "Root operation timed out" else detail.ifEmpty { "Root operation failed or was cancelled" })
        }
        fun abort() {
            if (!ended.compareAndSet(false, true)) return
            watchdog.cancel(false)
            // EOF is cancellation, not successful completion. The privileged reader kills its command group before closing the child's data pipe.
            runCatching { process.outputStream.close() }
            commands.remove(this)
            cleanup.execute {
                // Never close Java's synchronized input pipe while a child owns it.
                if (process.waitFor(2, TimeUnit.SECONDS)) runCatching { process.inputStream.close() }
                else process.destroyForcibly() // Dismiss a stuck su client; the privileged supervisor owns worker termination.
            }
        }
    }

    companion object {
        private const val FRAME_BYTES = 1536
        private val supervisorTemplate = checkNotNull(RootShell::class.java.getResourceAsStream("/root-supervisor.sh"))
            .bufferedReader().use { it.readText() }
        private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "root-timeout").apply { isDaemon = true } }
        private val cleanup = Executors.newCachedThreadPool { task -> Thread(task, "root-cleanup").apply { isDaemon = true } }
        internal fun quote(value: String): String {
            require('\u0000' !in value) { "A path cannot contain NUL" }
            return "'" + value.replace("'", "'\\''") + "'"
        }
    }
}
