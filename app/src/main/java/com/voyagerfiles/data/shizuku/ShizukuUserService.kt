package com.voyagerfiles.data.shizuku

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import androidx.annotation.Keep
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Commands use the same bounded supervision protocol as root sessions. File operations run here in
 * Java ([ShizukuFileOperations]) and move data through pipes, so no shell process is started per chunk.
 */
@Keep
class ShizukuUserService @Keep constructor(context: Context) : IShizukuFiles.Stub() {
    private val clientUid = context.applicationInfo.uid
    private val commands = ConcurrentHashMap.newKeySet<Command>()
    /** Lists, reads and writes in flight; they share the operation limit with commands. */
    private val transfers = ConcurrentHashMap.newKeySet<Any>()
    private val writes = ConcurrentHashMap.newKeySet<ServiceWrite>()
    private val operations = ShizukuFileOperations(Process.myUid())

    private fun checkCaller() {
        check(Binder.getCallingUid() == clientUid || Binder.getCallingUid() == Process.myUid()) { "Caller is not the owning application" }
    }

    override fun uid(): Int { checkCaller(); return Process.myUid() }

    override fun start(script: String): IShizukuCommand {
        checkCaller()
        require(script.length <= 256 * 1024) { "Command exceeds the allowed limit" }
        synchronized(commands) {
            check(commands.size + transfers.size < MAX_OPERATIONS) { "Too many active Shizuku operations" }
            val commandPipe = ParcelFileDescriptor.createPipe()
            try {
                val pipePath = "/proc/${Process.myPid()}/fd/${commandPipe[0].fd}"
                val hook = "input=@VOYAGER_SHIZUKU_PIPE@;"
                require(hook in script) { "Missing command input pipe" }
                val prepared = script.replaceFirst(hook, "input=$pipePath;")
                val process = ProcessBuilder("/system/bin/sh", "-c", prepared).start()
                try { return Command(process, commandPipe) }
                catch (error: Exception) { process.destroy(); throw error }
            } catch (error: Exception) {
                commandPipe.forEach { runCatching { it.close() } }
                throw error
            }
        }
    }

    override fun stat(path: String): ByteArray = guard { operations.stat(path) }
    override fun exists(path: String): Boolean = guard { operations.exists(path) }
    override fun fingerprint(path: String): String = guard { operations.fingerprint(path) }
    override fun mkdir(path: String) = guard { operations.mkdir(path) }
    override fun createFile(path: String) = guard { operations.createFile(path) }

    override fun openRead(path: String): IShizukuRead = guard {
        val file = operations.openRead(path)
        try {
            read { output ->
                file.use { source ->
                    FileInputStream(source.fd).let { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } catch (error: Throwable) { file.close(); throw error }
    }

    override fun openWrite(path: String, expectedFingerprint: String, client: IBinder): IShizukuWrite = guard {
        var write: ServiceWrite? = null
        val slot = admit { synchronized(writes) { write?.let(writes::remove) } }
        val staged = try { operations.openWrite(path, expectedFingerprint.ifEmpty { null }) }
        catch (error: Throwable) { slot.release(); throw error }
        try {
            synchronized(writes) {
                ServiceWrite(slot, staged, client).also { write = it; writes.add(it) }
            }
        } catch (error: Throwable) {
            staged.discard(); staged.close(); slot.release()
            throw error
        }
    }

    override fun destroy() {
        checkCaller()
        commands.toList().forEach { it.terminateProcess() }
        writes.toList().forEach { it.terminate() }
        kotlin.system.exitProcess(0)
    }

    private fun read(produce: (java.io.OutputStream) -> Unit): IShizukuRead {
        val slot = admit()
        return try { ServiceRead(slot, produce) } catch (error: Throwable) { slot.release(); throw error }
    }

    /** Reserves an operation slot; the returned hooks release it once. */
    private fun admit(onRelease: () -> Unit = {}): TransferHooks {
        val key = Any()
        synchronized(commands) {
            check(commands.size + transfers.size < MAX_OPERATIONS) { "Too many active Shizuku operations" }
            transfers.add(key)
        }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return TransferHooks(::checkCaller, ::describe) {
            if (released.compareAndSet(false, true)) {
                transfers.remove(key)
                onRelease()
            }
        }
    }

    /**
     * Binder only carries some runtime exceptions to the client, and a checked ErrnoException escaping a
     * transaction would end the whole service. Every file call maps failures to a message here.
     */
    private inline fun <T> guard(action: () -> T): T {
        checkCaller()
        try {
            return action()
        } catch (error: SecurityException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(describe(error))
        } catch (error: Throwable) {
            throw IllegalStateException(describe(error))
        }
    }

    private fun describe(error: Throwable): String {
        val message = when (error) {
            is ErrnoException -> if (error.errno == android.system.OsConstants.ELOOP) "Folder changed or is a symbolic link" else Os.strerror(error.errno)
            else -> error.message ?: error.javaClass.simpleName
        }
        // Descriptor paths mean nothing to the user.
        return message.replace(Regex("/proc/self/fd/[0-9]+/?"), "")
    }

    private inner class Command(
        private val process: java.lang.Process,
        private val commandPipe: Array<ParcelFileDescriptor>,
    ) : IShizukuCommand.Stub() {
        private val stdin = ParcelFileDescriptor.createPipe()
        private val stdout = ParcelFileDescriptor.createPipe()
        private val stderr = ParcelFileDescriptor.createPipe()
        private var inputTaken = false
        private var outputTaken = false
        private var errorTaken = false

        init {
            commands.add(this)
            pump(ParcelFileDescriptor.AutoCloseInputStream(stdin[0]), process.outputStream)
            pump(process.inputStream, ParcelFileDescriptor.AutoCloseOutputStream(stdout[1]))
            pumpErrors(process.errorStream, ParcelFileDescriptor.AutoCloseOutputStream(stderr[1]))
            thread(name = "shizuku-command-exit", isDaemon = true) {
                process.waitFor()
                commandPipe.forEach { runCatching { it.close() } }
                commands.remove(this)
            }
        }

        @Synchronized override fun input(): ParcelFileDescriptor {
            checkCaller(); check(!inputTaken); inputTaken = true
            return transfer(stdout[0])
        }
        @Synchronized override fun output(): ParcelFileDescriptor {
            checkCaller(); check(!outputTaken); outputTaken = true
            return transfer(stdin[1])
        }
        @Synchronized override fun error(): ParcelFileDescriptor {
            checkCaller(); check(!errorTaken); errorTaken = true
            return transfer(stderr[0])
        }
        override fun awaitExit(timeoutMillis: Long): Boolean {
            checkCaller()
            return process.waitFor(timeoutMillis.coerceIn(0, 31_000), TimeUnit.MILLISECONDS)
        }
        override fun exitValue(): Int { checkCaller(); return process.exitValue() }
        override fun terminate() { checkCaller(); terminateProcess() }
        fun terminateProcess() {
            process.destroy()
            commandPipe.forEach { runCatching { it.close() } }
            listOf(stdin[1], stdout[0], stderr[0]).forEach { runCatching { it.close() } }
        }
        private fun transfer(descriptor: ParcelFileDescriptor): ParcelFileDescriptor =
            ParcelFileDescriptor.dup(descriptor.fileDescriptor).also { descriptor.close() }

        private fun pumpErrors(input: InputStream, output: OutputStream) {
            thread(name = "shizuku-command-errors", isDaemon = true) {
                try {
                    input.use { source ->
                        output.use { target ->
                            val line = ByteArrayOutputStream(64)
                            val buffer = ByteArray(8192)
                            var overflow = false
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                for (index in 0 until count) {
                                    val byte = buffer[index].toInt()
                                    if (byte == 10) {
                                        // The controller and worker now own their pipe ends.
                                        if (!overflow && line.toString("US-ASCII").matches(WORKER_MARKER)) {
                                            commandPipe.forEach { runCatching { it.close() } }
                                        }
                                        line.reset()
                                        overflow = false
                                    } else if (line.size() < 64) line.write(byte)
                                    else overflow = true
                                }
                                target.write(buffer, 0, count)
                                target.flush()
                            }
                        }
                    }
                } catch (_: java.io.IOException) { runCatching { output.close() } }
            }
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        thread(name = "shizuku-command-stream", isDaemon = true) {
            try {
                input.use { source ->
                    output.use { target ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                            target.flush()
                        }
                    }
                }
            }
            catch (_: java.io.IOException) { runCatching { (input as Closeable).close() }; runCatching { output.close() } }
        }
    }

    private companion object {
        const val MAX_OPERATIONS = 8
        val WORKER_MARKER = Regex("[0-9a-f-]{36}:WORKER")
    }
}
