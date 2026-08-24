package com.voyagerfiles.ui.components

import com.voyagerfiles.R
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormValidatorTest {

    @Test
    fun rejectsEmbeddedSchemeAndPathInHost() {
        val scheme = ConnectionFormValidator.validate(ConnectionProtocol.SFTP, "ssh://server.example", "22", "")
        val path = ConnectionFormValidator.validate(ConnectionProtocol.WEBDAV, "server.example/dav", "443", "")

        assertEquals(R.string.validation_host_without_scheme, scheme.hostErrorRes)
        assertEquals(R.string.validation_host_without_scheme, path.hostErrorRes)
    }

    @Test
    fun rejectsInvalidPortInsteadOfSilentlyUsingDefault() {
        assertEquals(R.string.validation_port_range, validatePort("not-a-port").portErrorRes)
        assertEquals(R.string.validation_port_range, validatePort("0").portErrorRes)
        assertEquals(R.string.validation_port_range, validatePort("65536").portErrorRes)
    }

    @Test
    fun acceptsBlankShareNameForSmbDiscovery() {
        val result = ConnectionFormValidator.validate(ConnectionProtocol.SMB, "server.example", "445", "")

        assertTrue(result.isValid)
        assertNull(result.shareNameErrorRes)
    }

    @Test
    fun acceptsValidConnectionFields() {
        val result = ConnectionFormValidator.validate(ConnectionProtocol.SFTP, "server.example", "22", "")

        assertTrue(result.isValid)
        assertNull(result.hostErrorRes)
        assertNull(result.portErrorRes)
    }

    @Test
    fun cleartextWarningCoversFtpAndHttpWebDavOnly() {
        assertEquals(
            UiText.Resource(R.string.warning_ftp_title),
            connectionTransportWarning(ConnectionProtocol.FTP, useTls = false)?.title,
        )
        assertEquals(
            UiText.Resource(R.string.warning_http_title),
            connectionTransportWarning(ConnectionProtocol.WEBDAV, useTls = false)?.title,
        )
        assertNull(connectionTransportWarning(ConnectionProtocol.WEBDAV, useTls = true))
        assertNull(connectionTransportWarning(ConnectionProtocol.SFTP, useTls = false))
    }

    private fun validatePort(port: String): ConnectionFormValidation =
        ConnectionFormValidator.validate(ConnectionProtocol.SFTP, "server.example", port, "")
}
