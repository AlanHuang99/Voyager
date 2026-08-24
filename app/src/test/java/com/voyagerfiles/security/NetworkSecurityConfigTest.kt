package com.voyagerfiles.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSecurityConfigTest {
    @Test
    fun manifestUsesSystemAndUserCertificateAuthorities() {
        val root = repositoryRoot()
        val manifest = root.resolve("app/src/main/AndroidManifest.xml").readText()
        val configFile = root.resolve("app/src/main/res/xml/network_security_config.xml")

        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(configFile.isFile)
        val config = configFile.readText()
        assertTrue(config.contains("<certificates src=\"system\""))
        assertTrue(config.contains("<certificates src=\"user\""))
        assertTrue(config.contains("<base-config cleartextTrafficPermitted=\"true\""))
        assertFalse(config.contains("overridePins=\"true\""))
    }

    private fun repositoryRoot(): File {
        val cwd = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(cwd, checkNotNull(cwd.parentFile))
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
    }
}
