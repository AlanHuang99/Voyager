package com.voyagerfiles.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferProgressTest {

    @Test
    fun byteFractionIsDeterminateFromZeroAndClamped() {
        assertEquals(
            0f,
            TransferProgress(label = "Copying", copiedBytes = 0, totalBytes = 100).fraction,
        )
        assertEquals(
            0.25f,
            TransferProgress(label = "Copying", copiedBytes = 25, totalBytes = 100).fraction,
        )
        assertEquals(
            1f,
            TransferProgress(label = "Copying", copiedBytes = 150, totalBytes = 100).fraction,
        )
    }

    @Test
    fun completedItemsAreFallbackWhenByteTotalIsUnknownOrZero() {
        assertEquals(
            0.4f,
            TransferProgress(
                label = "Moving",
                completedItems = 2,
                totalItems = 5,
                copiedBytes = 50,
                totalBytes = null,
            ).fraction,
        )
        assertEquals(
            0.4f,
            TransferProgress(
                label = "Moving",
                completedItems = 2,
                totalItems = 5,
                copiedBytes = 0,
                totalBytes = 0,
            ).fraction,
        )
    }

    @Test
    fun fractionIsUnknownWithoutTruthfulDenominator() {
        assertNull(TransferProgress(label = "Copying").fraction)
        assertNull(TransferProgress(label = "Copying", totalBytes = 0).fraction)
        assertNull(TransferProgress(label = "Copying", totalItems = 0).fraction)
    }

    @Test
    fun exposesReaderFacingItemAndPercentageText() {
        val progress = TransferProgress(
            label = "Copying",
            completedItems = 2,
            totalItems = 5,
            currentItemName = "report.pdf",
            copiedBytes = 40,
            totalBytes = 100,
        )

        assertEquals("2 of 5", progress.itemProgressText)
        assertEquals("40%", progress.percentageText)
        assertEquals(
            "Copying, report.pdf, 2 of 5, 40 B of 100 B, 40%",
            progress.stateDescription,
        )
    }

    @Test
    fun formatsTransferredBytesAndAverageSpeedWithoutInventingTotals() {
        val known = TransferProgress(
            label = "Downloading",
            copiedBytes = 1_572_864,
            totalBytes = 3_145_728,
            elapsedNanos = 1_500_000_000,
        )
        assertEquals("1.5 MB of 3 MB", known.byteProgressText)
        assertEquals("1 MB/s", known.speedText)

        val unknown = TransferProgress(
            label = "Downloading",
            copiedBytes = 1_024,
            totalBytes = null,
            elapsedNanos = 1_000_000_000,
        )
        assertEquals("1 KB", unknown.byteProgressText)
        assertEquals("1 KB/s", unknown.speedText)
    }

    @Test
    fun rejectsNegativeCountsAndByteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferProgress(label = "Copying", completedItems = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferProgress(label = "Copying", totalItems = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferProgress(label = "Copying", copiedBytes = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferProgress(label = "Copying", totalBytes = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferProgress(label = "Copying", elapsedNanos = -1)
        }
    }
}
