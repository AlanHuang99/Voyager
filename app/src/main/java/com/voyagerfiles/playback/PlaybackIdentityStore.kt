package com.voyagerfiles.playback

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class PlaybackResourceIdentity(
    val connectionId: Long,
    val url: String,
    val username: String,
)

internal class PlaybackIdentityStore(directory: File) {
    private val key = synchronized(keyLock) {
        val keyFile = File(directory, "webdav-playback.key")
        if (keyFile.exists()) {
            keyFile.readBytes().also { require(it.size == KEY_BYTES) { "Invalid playback identity key" } }
        } else {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create playback identity directory" }
            val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
            val temporary = File.createTempFile("webdav-playback-", ".tmp", directory)
            try {
                temporary.writeBytes(key)
                Files.move(temporary.toPath(), keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
            key
        }
    }

    fun tokenFor(identity: PlaybackResourceIdentity): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        listOf(identity.connectionId.toString(), identity.url, identity.username).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            mac.update(bytes)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal())
    }

    private companion object {
        val keyLock = Any()
        const val KEY_BYTES = 32
    }
}
