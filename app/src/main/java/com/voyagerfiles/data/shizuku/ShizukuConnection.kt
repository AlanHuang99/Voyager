package com.voyagerfiles.data.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuConnection private constructor(
    private val args: Shizuku.UserServiceArgs,
    private val connection: ServiceConnection,
    private val service: IShizukuFiles,
) : Closeable {
    private val closed = AtomicBoolean(false)
    val uid: Int get() = service.uid()

    internal fun start(script: String): Process {
        if (closed.get()) throw IOException("Shizuku session is closed. Open a new session.")
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                throw IOException("Shizuku stopped or permission was revoked. Open a new session after authorizing Shizuku.")
            }
            return ShizukuCommandProcess(service.start(script))
        } catch (error: android.os.RemoteException) {
            throw IOException("Shizuku disconnected. Start Shizuku and open a new session.", error)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    companion object {
        suspend fun connect(context: Context): ShizukuConnection = withContext(Dispatchers.Main.immediate) {
            check(Shizuku.pingBinder()) { "Start Shizuku, then try again." }
            check(!Shizuku.isPreV11() && Shizuku.getVersion() >= 13) { "Update Shizuku to version 13 or later." }
            requestPermission()
            val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuUserService::class.java))
                .daemon(false).processNameSuffix("shizuku_files").debuggable(false)
                .tag("voyager-files-${java.util.UUID.randomUUID()}").version(com.voyagerfiles.BuildConfig.VERSION_CODE)
            withTimeout(20_000) {
                suspendCancellableCoroutine { continuation ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            val result = ShizukuConnection(args, this, IShizukuFiles.Stub.asInterface(binder))
                            if (continuation.isActive) continuation.resume(result) { _, value, _ -> value.close() }
                            else result.close()
                        }
                        override fun onServiceDisconnected(name: ComponentName) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("Shizuku disconnected while opening the session."))
                        }
                    }
                    continuation.invokeOnCancellation { runCatching { Shizuku.unbindUserService(args, connection, true) } }
                    try { Shizuku.bindUserService(args, connection) }
                    catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            }
        }

        private suspend fun requestPermission() {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
            check(!Shizuku.shouldShowRequestPermissionRationale()) { "Shizuku permission denied. Allow Voyager in Shizuku, then try again." }
            suspendCancellableCoroutine<Unit> { continuation ->
                val requestCode = 144
                lateinit var listener: Shizuku.OnRequestPermissionResultListener
                listener = Shizuku.OnRequestPermissionResultListener { code, result ->
                    if (code == requestCode && continuation.isActive) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        if (result == PackageManager.PERMISSION_GRANTED) continuation.resume(Unit)
                        else continuation.resumeWithException(IOException("Shizuku permission denied. Allow Voyager in Shizuku, then try again."))
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                try { Shizuku.requestPermission(requestCode) }
                catch (error: Exception) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }
}
