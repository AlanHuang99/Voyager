package com.voyagerfiles.data.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class SmbShareDiscoveryTest {
    @Test
    fun keepsDiskTreesAndRemovesSpecialFlags() {
        val input = listOf(
            RawSmbShare("Media", 0x00000000, "files"),
            RawSmbShare("Hidden$", 0x80000000.toInt(), "admin disk"),
            RawSmbShare("Printer", 0x00000001, "printer"),
            RawSmbShare("IPC$", 0x00000003, "ipc"),
        )

        assertEquals(listOf("Hidden$", "Media"), diskShares(input).map { it.name })
    }

    @Test
    fun trimsSortsAndDeduplicatesShareNamesCaseInsensitively() {
        val input = listOf(
            RawSmbShare(" videos ", 0, null),
            RawSmbShare("Documents", 0, "docs"),
            RawSmbShare("VIDEOS", 0, "duplicate"),
            RawSmbShare("  ", 0, "blank"),
        )

        assertEquals(
            listOf(
                SmbDiscoveredShare("Documents", "docs"),
                SmbDiscoveredShare("videos", null),
            ),
            diskShares(input),
        )
    }
}
