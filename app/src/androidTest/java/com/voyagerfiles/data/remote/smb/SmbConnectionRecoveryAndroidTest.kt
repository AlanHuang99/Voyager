package com.voyagerfiles.data.remote.smb

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.voyagerfiles.app.MainActivity
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the disposable Samba fixture selected by the smbHost and smbPort instrumentation arguments. */
class SmbConnectionRecoveryAndroidTest {
    @Test
    fun reloadsAfterUploadInDirectAndDiscoveryModes() = runBlocking {
        for (shareName in listOf("media", null)) {
            withUploadedFile(shareName) { provider, directory, payload ->
                assertUploadedFile(provider, directory, payload)
            }
        }
    }

    @Test
    fun reloadsAfterActivityBackgroundAndForeground() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            withUploadedFile("media") { provider, directory, payload ->
                activity.moveToState(Lifecycle.State.CREATED)
                activity.moveToState(Lifecycle.State.RESUMED)
                assertUploadedFile(provider, directory, payload)
            }
        }
    }

    @Test
    fun reloadsAfterCachedShareCloses() = runBlocking {
        for (shareName in listOf("media", null)) {
            withUploadedFile(shareName) { provider, directory, payload ->
                val field = SmbFileProvider::class.java.getDeclaredField("share").apply { isAccessible = true }
                (field.get(provider) as DiskShare).close()

                assertUploadedFile(provider, directory, payload)
            }
        }
    }

    @Test
    fun reloadsAfterBrowserTransportCloses() = runBlocking {
        for (shareName in listOf("media", null)) {
            withUploadedFile(shareName) { provider, directory, payload ->
                val handleField = SmbFileProvider::class.java.getDeclaredField("sessionHandle").apply { isAccessible = true }
                val handle = checkNotNull(handleField.get(provider))
                val connectionField = handle.javaClass.getDeclaredField("connection").apply { isAccessible = true }
                (connectionField.get(handle) as Connection).close(true)

                assertUploadedFile(provider, directory, payload)
            }
        }
    }

    private suspend fun withUploadedFile(
        shareName: String?,
        block: suspend (SmbFileProvider, String, ByteArray) -> Unit,
    ) {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("smbHost")
        val port = arguments.getString("smbPort")?.toIntOrNull()
        assumeTrue(!host.isNullOrBlank() && port != null)
        val provider = SmbFileProvider(
            RemoteConnection(
                name = "SMB recovery fixture",
                protocol = ConnectionProtocol.SMB,
                host = checkNotNull(host),
                port = checkNotNull(port),
                username = "voyager",
                password = "voyager-test",
                shareName = shareName,
            ),
        )
        val root = if (shareName == null) "/media" else "/"
        var directory: String? = null
        try {
            provider.listFiles(root).getOrThrow()
            directory = provider.createDirectory(root, "recovery-${System.nanoTime()}").getOrThrow().path
            provider.createFile(directory, "probe.bin").getOrThrow()
            val payload = ByteArray(8192) { (it * 31).toByte() }
            provider.getOutputStream("$directory/probe.bin").getOrThrow().use { it.write(payload) }
            block(provider, directory, payload)
        } finally {
            provider.disconnect()
            try {
                directory?.let { path -> provider.delete(path).getOrThrow() }
            } finally {
                provider.disconnect()
            }
        }
    }

    private suspend fun assertUploadedFile(provider: SmbFileProvider, directory: String, payload: ByteArray) {
        assertEquals(listOf("probe.bin"), provider.listFiles(directory).getOrThrow().map { it.name })
        assertArrayEquals(payload, provider.getInputStream("$directory/probe.bin").getOrThrow().use { it.readBytes() })
    }
}
