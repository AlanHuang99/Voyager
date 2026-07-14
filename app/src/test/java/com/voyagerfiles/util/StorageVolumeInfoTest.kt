package com.voyagerfiles.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageVolumeInfoTest {

    @Test
    fun mountedReadOnlyVolumeIsAvailableAndLabeled() {
        val volume = StorageVolumeInfo(
            description = "USB drive",
            path = "/storage/ABCD-1234",
            isPrimary = false,
            isRemovable = true,
            state = StorageVolumeInfo.STATE_MOUNTED_READ_ONLY,
        )

        assertTrue(volume.isAvailable)
        assertTrue(volume.isReadOnly)
        assertEquals("Read only", volume.statusLabel)
    }

    @Test
    fun missingOrUnmountedVolumeIsUnavailable() {
        val volume = StorageVolumeInfo(
            description = "SD card",
            path = null,
            isPrimary = false,
            isRemovable = true,
            state = "unmounted",
        )

        assertFalse(volume.isAvailable)
        assertEquals("Unavailable", volume.statusLabel)
    }

    @Test
    fun mergePrefersPlatformDescriptionAndDeduplicatesFallbackPaths() {
        val platform = listOf(
            StorageVolumeInfo(
                description = "Internal storage",
                path = "/storage/emulated/0",
                isPrimary = true,
                isRemovable = false,
                state = StorageVolumeInfo.STATE_MOUNTED,
            ),
            StorageVolumeInfo(
                description = "USB drive",
                path = "/storage/ABCD-1234",
                isPrimary = false,
                isRemovable = true,
                state = StorageVolumeInfo.STATE_MOUNTED,
            ),
        )

        val merged = mergeStorageVolumes(
            platformVolumes = platform,
            fallbackPaths = listOf("/storage/emulated/0", "/storage/ABCD-1234", "/storage/ABCD-1234"),
            primaryPath = "/storage/emulated/0",
        )

        assertEquals(listOf("Internal storage", "USB drive"), merged.map { it.description })
    }
}
