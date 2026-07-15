package com.voyagerfiles.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageAccessModeTest {

    @Test
    fun fullPermissionAlwaysUsesFullMode() {
        assertEquals(StorageAccessMode.FULL, storageAccessMode(hasAllFilesAccess = true, limitedAccessAccepted = false))
        assertEquals(StorageAccessMode.FULL, storageAccessMode(hasAllFilesAccess = true, limitedAccessAccepted = true))
    }

    @Test
    fun deniedPermissionCanUseAcceptedLimitedMode() {
        assertEquals(StorageAccessMode.LIMITED, storageAccessMode(hasAllFilesAccess = false, limitedAccessAccepted = true))
    }

    @Test
    fun firstDenialRequiresAUserDecision() {
        assertEquals(StorageAccessMode.NEEDS_DECISION, storageAccessMode(hasAllFilesAccess = false, limitedAccessAccepted = false))
    }
}
