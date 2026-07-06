package com.voyagerfiles.data.remote.sftp

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.File

object SshKeyGenerator {

    fun generateToDirectory(
        directory: File,
        baseName: String,
        comment: String,
    ): GeneratedSshKeyPair {
        directory.mkdirs()
        require(directory.isDirectory) { "Key directory is not available: ${directory.path}" }

        val keyBaseName = baseName.ifBlank { DEFAULT_BASE_NAME }
        val privateKeyFile = directory.resolve(keyBaseName)
        val publicKeyFile = directory.resolve("$keyBaseName.pub")

        val keyPair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, RSA_KEY_SIZE_BITS)
        try {
            privateKeyFile.outputStream().use { keyPair.writePrivateKey(it) }
            publicKeyFile.outputStream().use { keyPair.writePublicKey(it, comment.ifBlank { DEFAULT_COMMENT }) }
        } finally {
            keyPair.dispose()
        }

        privateKeyFile.limitToOwner()
        publicKeyFile.limitToOwner()

        return GeneratedSshKeyPair(
            privateKeyFile = privateKeyFile,
            publicKeyFile = publicKeyFile,
        )
    }

    fun safeKeyBaseName(input: String): String {
        val safeName = input
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_', '.', '-')

        return "id_voyager_${safeName.ifBlank { "key" }}"
    }

    private fun File.limitToOwner() {
        setReadable(false, false)
        setWritable(false, false)
        setExecutable(false, false)
        setReadable(true, true)
        setWritable(true, true)
    }

    private const val RSA_KEY_SIZE_BITS = 4096
    private const val DEFAULT_BASE_NAME = "id_voyager_key"
    private const val DEFAULT_COMMENT = "voyager"
}

data class GeneratedSshKeyPair(
    val privateKeyFile: File,
    val publicKeyFile: File,
)
