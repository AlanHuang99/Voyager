package com.voyagerfiles.ui.components

import com.voyagerfiles.data.model.ConnectionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormValidatorTest {

    @Test
    fun rejectsEmbeddedSchemeAndPathInHost() {
        val scheme = ConnectionFormValidator.validate(ConnectionProtocol.SFTP, "ssh://server.example", "22", "")
        val path = ConnectionFormValidator.validate(ConnectionProtocol.WEBDAV, "server.example/dav", "443", "")

        assertEquals("Enter a host without a scheme or path", scheme.hostError)
        assertEquals("Enter a host without a scheme or path", path.hostError)
    }

    @Test
    fun rejectsInvalidPortInsteadOfSilentlyUsingDefault() {
        assertEquals("Enter a port from 1 to 65535", validatePort("not-a-port").portError)
        assertEquals("Enter a port from 1 to 65535", validatePort("0").portError)
        assertEquals("Enter a port from 1 to 65535", validatePort("65536").portError)
    }

    @Test
    fun requiresShareNameForSmb() {
        val result = ConnectionFormValidator.validate(ConnectionProtocol.SMB, "server.example", "445", "")

        assertFalse(result.isValid)
        assertEquals("Share name is required for SMB", result.shareNameError)
    }

    @Test
    fun acceptsValidConnectionFields() {
        val result = ConnectionFormValidator.validate(ConnectionProtocol.SFTP, "server.example", "22", "")

        assertTrue(result.isValid)
        assertNull(result.hostError)
        assertNull(result.portError)
    }

    @Test
    fun cleartextWarningCoversFtpAndHttpWebDavOnly() {
        assertEquals("Use unencrypted FTP?", connectionTransportWarning(ConnectionProtocol.FTP, useTls = false)?.title)
        assertEquals("Use unencrypted HTTP?", connectionTransportWarning(ConnectionProtocol.WEBDAV, useTls = false)?.title)
        assertNull(connectionTransportWarning(ConnectionProtocol.WEBDAV, useTls = true))
        assertNull(connectionTransportWarning(ConnectionProtocol.SFTP, useTls = false))
    }

    private fun validatePort(port: String): ConnectionFormValidation =
        ConnectionFormValidator.validate(ConnectionProtocol.SFTP, "server.example", port, "")
}
