package com.voyagerfiles.data.shizuku

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class ShizukuCommandProcess(private val command: IShizukuCommand) : Process() {
    private val stdin: ParcelFileDescriptor.AutoCloseOutputStream
    private val stdout: ParcelFileDescriptor.AutoCloseInputStream
    private val stderr: ParcelFileDescriptor.AutoCloseInputStream
    @Volatile private var disconnected = false

    init {
        val opened = mutableListOf<Closeable>()
        try {
            stdin = ParcelFileDescriptor.AutoCloseOutputStream(command.output()).also(opened::add)
            stdout = ParcelFileDescriptor.AutoCloseInputStream(command.input()).also(opened::add)
            stderr = ParcelFileDescriptor.AutoCloseInputStream(command.error()).also(opened::add)
        } catch (error: Exception) {
            opened.asReversed().forEach { runCatching { it.close() } }
            runCatching { command.terminate() }
            throw IOException("Shizuku disconnected while opening command streams.", error)
        }
    }

    override fun getOutputStream() = stdin
    override fun getInputStream() = stdout
    override fun getErrorStream() = stderr
    override fun waitFor(): Int {
        while (!waitFor(1000, TimeUnit.MILLISECONDS)) { if (Thread.interrupted()) throw InterruptedException() }
        return exitValue()
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = remote(true) { command.awaitExit(unit.toMillis(timeout)) }
    override fun exitValue(): Int = remote(-1) { command.exitValue() }
    override fun isAlive(): Boolean = remote(false) { !command.awaitExit(0) }
    override fun destroy() {
        runCatching { stdin.close() }
        runCatching { command.terminate() }
        disconnected = true
        closeStreams()
    }
    override fun destroyForcibly(): Process { destroy(); return this }

    private fun <T> remote(whenDisconnected: T, action: () -> T): T {
        if (disconnected) return whenDisconnected
        return try { action() }
        catch (_: RemoteException) {
            disconnected = true
            closeStreams()
            whenDisconnected
        }
    }

    private fun closeStreams() {
        listOf(stdin, stdout, stderr).forEach { runCatching { it.close() } }
    }
}
