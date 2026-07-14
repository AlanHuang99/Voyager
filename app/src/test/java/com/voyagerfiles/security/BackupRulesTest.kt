package com.voyagerfiles.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupRulesTest {

    @Test
    fun legacyAndModernRulesExcludeCredentialsAndPrivateKeys() {
        val resourceRoot = sourceResourceRoot()
        val legacy = File(resourceRoot, "xml/backup_rules.xml").readText()
        val modern = File(resourceRoot, "xml/data_extraction_rules.xml").readText()

        listOf(legacy, modern).forEach { rules ->
            assertTrue(rules.contains("domain=\"database\""))
            assertTrue(rules.contains("path=\".\""))
            assertTrue(rules.contains("path=\"datastore/\""))
            assertTrue(rules.contains("path=\"ssh/\""))
        }
    }

    @Test
    fun manifestReferencesBothBackupRuleFormats() {
        val manifest = File(checkNotNull(sourceResourceRoot().parentFile), "AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    private fun sourceResourceRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(
            File(workingDirectory, "src/main/res"),
            File(workingDirectory, "app/src/main/res"),
        ).first { it.isDirectory }
    }
}
