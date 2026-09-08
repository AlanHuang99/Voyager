package com.voyagerfiles.data.remote.sftp

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SftpKnownHostsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun fingerprintsUseOpenSshSha256AndExactHostPort() {
        val file = temp.newFile().apply {
            writeText("server,alias ssh-ed25519 $KEY comment\n[server]:2222 ssh-ed25519 $KEY\nother ssh-ed25519 $KEY\n")
        }
        val store = SftpKnownHosts(file)
        assertEquals("SHA256:nJ+/y/Qq5ISXC09t+DPr8CTl0Lr0KT/3ftYxsH+Jjr4", store.fingerprints("server", 22).single().sha256)
        assertEquals("ssh-ed25519", store.fingerprints("server", 2222).single().algorithm)
        assertTrue(store.fingerprints("server", 2022).isEmpty())
        assertTrue(store.fingerprints("unknown", 22).isEmpty())
        assertEquals(HostKeyRepository.NOT_INCLUDED, store.check("[server]:2022", key()))
    }

    @Test fun forgetPreservesAliasesOtherPortsCommentsAndSettings() {
        val file = temp.newFile().apply {
            writeText("# keep this comment\nserver,alias ssh-ed25519 $KEY comment\n[server]:2222 ssh-ed25519 $KEY\nother ssh-ed25519 $KEY\n")
        }
        val settings = temp.newFile().apply { writeText("settings and credentials") }
        SftpKnownHosts(file).forget("server", 22)
        val restored = SftpKnownHosts(file)
        assertTrue(restored.fingerprints("server", 22).isEmpty())
        assertEquals(1, restored.fingerprints("alias", 22).size)
        assertEquals(1, restored.fingerprints("server", 2222).size)
        assertEquals(1, restored.fingerprints("other", 22).size)
        assertTrue(file.readText().contains("# keep this comment\nalias ssh-ed25519 $KEY comment\n"))
        assertEquals("settings and credentials", settings.readText())
        val before = file.readText()
        restored.forget("unknown", 22)
        assertEquals(before, file.readText())
    }

    @Test fun staleRepositoriesCannotRestoreForgottenPinsOrLoseConcurrentAdditions() {
        val file = temp.newFile()
        val first = SftpKnownHosts(file)
        val stale = SftpKnownHosts(file)
        first.add(HostKey("server", key()), null)
        stale.add(HostKey("other", key()), null)
        first.forget("server", 22)
        stale.add(HostKey("third", key()), null)
        assertTrue(SftpKnownHosts(file).fingerprints("server", 22).isEmpty())
        val executor = Executors.newFixedThreadPool(8)
        try {
            (1..24).map { index -> executor.submit { SftpKnownHosts(file).add(HostKey("[server]:${2200 + index}", key()), null) } }
                .forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertEquals(26, SftpKnownHosts(file).hostKey.size)
    }

    @Test fun racingNewKeysCannotReplaceAnExistingPin() {
        val file = temp.newFile()
        val store = SftpKnownHosts(file)
        val other = key().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertEquals(HostKeyRepository.NOT_INCLUDED, store.check("server", key()))
        store.add(HostKey("server", other), null)
        assertEquals(HostKeyRepository.CHANGED, store.check("server", key()))
        assertThrows(IllegalStateException::class.java) { store.add(HostKey("server", key()), null) }
        assertEquals(HostKeyRepository.OK, SftpKnownHosts(file).check("server", other))
        store.forget("server", 22)
        store.add(HostKey("server", key()), null)
        assertEquals(HostKeyRepository.OK, SftpKnownHosts(file).check("server", key()))
    }

    @Test fun differentKeyAlgorithmCannotBypassAnExistingPin() {
        val store = SftpKnownHosts(temp.newFile())
        store.add(HostKey("server", key()), null)
        val replacement = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        try {
            assertEquals(HostKeyRepository.CHANGED, store.check("server", replacement.publicKeyBlob))
            assertThrows(IllegalStateException::class.java) {
                store.add(HostKey("server", replacement.publicKeyBlob), null)
            }
            store.forget("server", 22)
            store.add(HostKey("server", replacement.publicKeyBlob), null)
            assertEquals(HostKeyRepository.OK, store.check("server", replacement.publicKeyBlob))
        } finally { replacement.dispose() }
    }

    @Test fun hashedOpenSshEntriesRemainScopedToTheirExactPort() {
        val file = temp.newFile().apply {
            writeText("|1|yac1U21fCK/nEKd+500xYg379jk=|zg6yRc24GD6kqFI2vTJ2sNe6744= ssh-ed25519 $KEY\n" +
                "|1|63FZ5gGQw2IFw118awyhNq+oQF0=|rcm8mVHE0WLDeFtbh2dvQPwVCgA= ssh-ed25519 $KEY\n")
        }
        val store = SftpKnownHosts(file)
        assertEquals(HostKeyRepository.OK, store.check("server", key()))
        assertEquals(HostKeyRepository.OK, store.check("[server]:2222", key()))
        assertEquals(HostKeyRepository.NOT_INCLUDED, store.check("[server]:2022", key()))
        store.forget("server", 2222)
        assertTrue(store.fingerprints("server", 2222).isEmpty())
        assertEquals(1, SftpKnownHosts(file).fingerprints("server", 22).size)
    }

    @Test fun unknownHostInspectionDoesNotCreateFilesAndFailedPersistenceDoesNotAcceptTrust() {
        val missing = File(temp.root, "missing/known_hosts")
        val parent = checkNotNull(missing.parentFile)
        val store = SftpKnownHosts(missing)
        assertTrue(store.fingerprints("server", 22).isEmpty())
        store.forget("server", 22)
        assertFalse(parent.exists())
        parent.writeText("blocked by existing file")
        assertThrows(IllegalStateException::class.java) { store.add(HostKey("server", key()), null) }
        assertEquals("blocked by existing file", parent.readText())
        assertEquals(HostKeyRepository.NOT_INCLUDED, store.check("server", key()))
        val directory = temp.newFolder()
        assertThrows(IOException::class.java) { SftpKnownHosts(directory).check("server", key()) }
    }

    private fun key() = Base64.getDecoder().decode(KEY)
    private companion object {
        const val KEY = "AAAAC3NzaC1lZDI1NTE5AAAAIEAfZB2qUvRvxwyWZabtaXITLWeg8cWfcyyZ7VPMuztD"
    }
}
