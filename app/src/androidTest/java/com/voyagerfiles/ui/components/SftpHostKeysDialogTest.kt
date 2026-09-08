package com.voyagerfiles.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.voyagerfiles.data.remote.sftp.SftpKnownHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SftpHostKeysDialogTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun cancelKeepsPinsAndConfirmForgetsOnlyDisplayedEndpoint() {
        val file = temp.newFile().apply {
            writeText("[server]:2222 ssh-ed25519 $KEY\nserver ssh-ed25519 $KEY\nother ssh-ed25519 $KEY\n")
        }
        val store = SftpKnownHosts(file)
        compose.setContent {
            MaterialTheme {
                SftpHostKeysDialog(host = "server", port = 2222, knownHosts = store, onDismiss = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(FINGERPRINT, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("server:2222").assertExists()
        compose.onNodeWithText("Forget saved keys").performClick()
        compose.onNodeWithText("The next connection will save a new host key", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, store.fingerprints("server", 2222).size)
        compose.onNodeWithText("Forget saved keys").performClick()
        compose.onNodeWithText("Forget").performClick()
        compose.waitUntil(5_000) { store.fingerprints("server", 2222).isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No saved host keys for this host and port.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Forget saved keys").assertIsNotEnabled()
        assertEquals(1, store.fingerprints("server", 22).size)
        assertEquals(1, store.fingerprints("other", 22).size)
    }

    @Test fun unknownEndpointCannotForgetAnything() {
        val file = temp.newFile()
        val store = SftpKnownHosts(file)
        compose.setContent {
            MaterialTheme {
                SftpHostKeysDialog(host = "unknown", port = 22, knownHosts = store, onDismiss = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No saved host keys for this host and port.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Forget saved keys").assertIsNotEnabled()
        assertTrue(file.readText().isEmpty())
    }

    private companion object {
        const val KEY = "AAAAC3NzaC1lZDI1NTE5AAAAIEAfZB2qUvRvxwyWZabtaXITLWeg8cWfcyyZ7VPMuztD"
        const val FINGERPRINT = "SHA256:nJ+/y/Qq5ISXC09t+DPr8CTl0Lr0KT/3ftYxsH+Jjr4"
    }
}
