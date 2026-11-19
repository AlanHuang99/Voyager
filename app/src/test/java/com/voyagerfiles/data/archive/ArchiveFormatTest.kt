package com.voyagerfiles.data.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFormatTest {

    @Test
    fun detectsSupportedFormatsCaseInsensitivelyAndPrefersCompoundSuffixes() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect("photos.ZIP"))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.detect("backup.tar"))
        assertEquals(ArchiveFormat.TAR_GZIP, ArchiveFormat.detect("backup.TAR.GZ"))
        assertEquals(ArchiveFormat.TAR_GZIP, ArchiveFormat.detect("backup.tgz"))
        assertEquals(ArchiveFormat.TAR_BZIP2, ArchiveFormat.detect("backup.TAR.BZ2"))
        assertEquals(ArchiveFormat.TAR_BZIP2, ArchiveFormat.detect("backup.tbz2"))
        assertEquals(ArchiveFormat.GZIP, ArchiveFormat.detect("records.csv.gz"))
        assertEquals(ArchiveFormat.BZIP2, ArchiveFormat.detect("records.csv.bz2"))
    }

    @Test
    fun detectsXzLz4AndZstdWithTarPairsPreferred() {
        assertEquals(ArchiveFormat.TAR_XZ, ArchiveFormat.detect("backup.tar.xz"))
        assertEquals(ArchiveFormat.TAR_XZ, ArchiveFormat.detect("backup.TXZ"))
        assertEquals(ArchiveFormat.XZ, ArchiveFormat.detect("records.csv.xz"))
        assertEquals(ArchiveFormat.TAR_ZSTD, ArchiveFormat.detect("backup.tar.zst"))
        assertEquals(ArchiveFormat.TAR_ZSTD, ArchiveFormat.detect("backup.tar.zstd"))
        assertEquals(ArchiveFormat.TAR_ZSTD, ArchiveFormat.detect("backup.tzst"))
        assertEquals(ArchiveFormat.ZSTD, ArchiveFormat.detect("records.csv.zst"))
        assertEquals(ArchiveFormat.TAR_LZ4, ArchiveFormat.detect("backup.tar.lz4"))
        assertEquals(ArchiveFormat.LZ4, ArchiveFormat.detect("records.csv.lz4"))
    }

    @Test
    fun recognizesRarWithoutClaimingExtractionSupport() {
        val format = ArchiveFormat.detect("legacy.RAR")

        assertEquals(ArchiveFormat.RAR_UNSUPPORTED, format)
        assertFalse(format!!.canExtract)
        assertTrue(format.isRecognizedArchive)
    }

    @Test
    fun derivesArchiveStemsUsingTheDetectedSuffix() {
        assertEquals("photos", ArchiveFormat.ZIP.stem("photos.zip"))
        assertEquals("backup", ArchiveFormat.TAR_GZIP.stem("backup.tar.gz"))
        assertEquals("backup", ArchiveFormat.TAR_GZIP.stem("backup.tgz"))
        assertEquals("records.csv", ArchiveFormat.GZIP.stem("records.csv.gz"))
        assertEquals("archive", ArchiveFormat.ZIP.stem(".zip"))
        assertEquals("backup", ArchiveFormat.TAR_XZ.stem("backup.tar.xz"))
        assertEquals("backup", ArchiveFormat.TAR_ZSTD.stem("backup.tar.zst"))
        assertEquals("backup", ArchiveFormat.TAR_LZ4.stem("backup.tar.lz4"))
        assertEquals("records.csv", ArchiveFormat.XZ.stem("records.csv.xz"))
        assertEquals("records.csv", ArchiveFormat.ZSTD.stem("records.csv.zst"))
        assertEquals("records.csv", ArchiveFormat.LZ4.stem("records.csv.lz4"))
    }

    @Test
    fun rejectsNamesWithoutARecognizedFinalSuffix() {
        assertNull(ArchiveFormat.detect("archive"))
        assertNull(ArchiveFormat.detect("archive.zip.txt"))
        assertNull(ArchiveFormat.detect("folder.rar.backup"))
    }
}
