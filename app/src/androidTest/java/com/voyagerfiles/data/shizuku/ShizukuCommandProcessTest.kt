package com.voyagerfiles.data.shizuku

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import org.junit.Assert.*
import org.junit.Test

class ShizukuCommandProcessTest {
    @Test fun setupFailureClosesAlreadyAcquiredDescriptorsAndTerminatesCommand() {
        for (failAt in listOf(2, 3)) {
            val stdin = ParcelFileDescriptor.createPipe()
            val stdout = ParcelFileDescriptor.createPipe()
            var terminated = false
            val command = object : IShizukuCommand.Stub() {
                override fun output(): ParcelFileDescriptor = stdin[1]
                override fun input(): ParcelFileDescriptor {
                    if (failAt == 2) throw RemoteException("Lost service during stdout acquisition")
                    return stdout[0]
                }
                override fun error(): ParcelFileDescriptor = throw RemoteException("Lost service during stderr acquisition")
                override fun awaitExit(timeoutMillis: Long) = true
                override fun exitValue() = 0
                override fun terminate() { terminated = true }
            }
            try {
                assertTrue(runCatching { ShizukuCommandProcess(command) }.isFailure)
                assertFalse("Acquired stdin must close after acquisition $failAt fails", stdin[1].fileDescriptor.valid())
                if (failAt == 3) assertFalse("Acquired stdout must close", stdout[0].fileDescriptor.valid())
                assertTrue("Failed bridge setup must terminate its command", terminated)
            } finally {
                (stdin.toList() + stdout.toList()).forEach { runCatching { it.close() } }
            }
        }
    }
    @Test fun deadBinderCompletesWaitAndClosesLocalStreams() {
        val stdin = ParcelFileDescriptor.createPipe()
        val stdout = ParcelFileDescriptor.createPipe()
        val stderr = ParcelFileDescriptor.createPipe()
        val command = object : IShizukuCommand.Stub() {
            override fun output() = stdin[1]
            override fun input() = stdout[0]
            override fun error() = stderr[0]
            override fun awaitExit(timeoutMillis: Long): Boolean = throw RemoteException("Service died")
            override fun exitValue(): Int = throw RemoteException("Service died")
            override fun terminate() {}
        }
        try {
            val process = ShizukuCommandProcess(command)
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertFalse(process.isAlive)
            assertEquals(-1, process.exitValue())
            assertFalse(stdin[1].fileDescriptor.valid())
            assertFalse(stdout[0].fileDescriptor.valid())
            assertFalse(stderr[0].fileDescriptor.valid())
            process.destroy()
        } finally { (stdin.toList() + stdout.toList() + stderr.toList()).forEach { runCatching { it.close() } } }
    }

}
