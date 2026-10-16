package com.voyagerfiles.data.shizuku

import android.os.ParcelFileDescriptor
import java.util.concurrent.TimeUnit

internal class ShizukuCommandProcess(private val command: IShizukuCommand) : Process() {
    private val stdin = ParcelFileDescriptor.AutoCloseOutputStream(command.output())
    private val stdout = ParcelFileDescriptor.AutoCloseInputStream(command.input())
    private val stderr = ParcelFileDescriptor.AutoCloseInputStream(command.error())
    override fun getOutputStream() = stdin
    override fun getInputStream() = stdout
    override fun getErrorStream() = stderr
    override fun waitFor(): Int {
        while (!command.awaitExit(1000)) { if (Thread.interrupted()) throw InterruptedException() }
        return command.exitValue()
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = command.awaitExit(unit.toMillis(timeout))
    override fun exitValue(): Int = command.exitValue()
    override fun isAlive(): Boolean = try { !command.awaitExit(0) } catch (_: android.os.RemoteException) { false }
    override fun destroy() {
        runCatching { stdin.close() }
        runCatching { command.terminate() }
        runCatching { stdout.close() }
        runCatching { stderr.close() }
    }
    override fun destroyForcibly(): Process { destroy(); return this }
}
