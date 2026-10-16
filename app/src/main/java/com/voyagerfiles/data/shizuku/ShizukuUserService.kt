package com.voyagerfiles.data.shizuku

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Commands use the same bounded supervision protocol as root sessions. */
@Keep
class ShizukuUserService @Keep constructor(context: Context) : IShizukuFiles.Stub() {
    private val clientUid = context.applicationInfo.uid
    private val commands = ConcurrentHashMap.newKeySet<Command>()

    private fun checkCaller() {
        check(Binder.getCallingUid() == clientUid || Binder.getCallingUid() == Process.myUid()) { "Caller is not the owning application" }
    }

    override fun uid(): Int { checkCaller(); return Process.myUid() }

    override fun start(script: String): IShizukuCommand {
        checkCaller()
        require(script.length <= 256 * 1024) { "Command exceeds the allowed limit" }
        synchronized(commands) {
            check(commands.size < 8) { "Too many active Shizuku operations" }
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

    override fun destroy() {
        checkCaller()
        commands.toList().forEach { it.terminateProcess() }
        kotlin.system.exitProcess(0)
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
        val WORKER_MARKER = Regex("[0-9a-f-]{36}:WORKER")
    }
}
