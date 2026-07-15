package com.voyagerfiles.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun isEncrypted(value: String): Boolean
    fun encrypt(plaintext: String): String
    fun decrypt(value: String): String
}

class AndroidCredentialCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialCipher {

    private val secretKey: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadOrCreateKey()
    }

    override fun isEncrypted(value: String): Boolean = value.startsWith(FORMAT_PREFIX)

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(ASSOCIATED_DATA)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return buildString {
            append(FORMAT_PREFIX)
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    override fun decrypt(value: String): String {
        if (value.isEmpty() || !isEncrypted(value)) return value
        val parts = value.removePrefix(FORMAT_PREFIX).split(':', limit = 2)
        require(parts.size == 2 && parts.all { it.isNotEmpty() }) { "Invalid encrypted credential" }
        val initializationVector = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector))
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "voyager_remote_credentials_v1"
        const val FORMAT_PREFIX = "voyager:v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        val ASSOCIATED_DATA = "Voyager remote credential v1".toByteArray(Charsets.UTF_8)
    }
}
