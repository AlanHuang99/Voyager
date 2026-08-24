package com.voyagerfiles.data.remote.smb

import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SmbAndroidIntegrationTest {
    @Test
    fun discoversAndStreamsFromSambaOnAndroid() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("smbHost")
        val port = arguments.getString("smbPort")?.toIntOrNull()
        assumeTrue(!host.isNullOrBlank() && port != null)
        val provider = SmbFileProvider(
            RemoteConnection(
                name = "Android SMB integration",
                protocol = ConnectionProtocol.SMB,
                host = checkNotNull(host),
                port = checkNotNull(port),
                username = USERNAME,
                password = PASSWORD,
                shareName = null,
            ),
        )

        try {
            assertTrue(provider.listFiles("/").getOrThrow().map { it.name }.contains("media"))
            val payload = "SMB RPC and streaming on Android".toByteArray()
            provider.getOutputStream("/media/android-probe.txt").getOrThrow().use { it.write(payload) }
            assertEquals(
                payload.toList(),
                provider.getInputStream("/media/android-probe.txt").getOrThrow().use { it.readBytes() }.toList(),
            )
        } finally {
            provider.disconnect()
        }
    }

    private companion object {
        const val USERNAME = "voyager"
        const val PASSWORD = "voyager-test"
    }
}
