package com.voyagerfiles.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class AndroidCredentialCipherTest {

    private val keyAlias = "voyager-credential-test-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(keyAlias)
        }
    }

    @Test
    fun encryptsWithRandomIvAndDecryptsWithKeystoreKey() {
        val cipher = AndroidCredentialCipher(keyAlias)

        val first = cipher.encrypt("daily-use secret")
        val second = cipher.encrypt("daily-use secret")

        assertTrue(cipher.isEncrypted(first))
        assertNotEquals(first, second)
        assertEquals("daily-use secret", cipher.decrypt(first))
        assertEquals("daily-use secret", cipher.decrypt(second))
    }
}
