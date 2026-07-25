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
    }

    @Test
    fun rejectsNamesWithoutARecognizedFinalSuffix() {
        assertNull(ArchiveFormat.detect("archive"))
        assertNull(ArchiveFormat.detect("archive.zip.txt"))
        assertNull(ArchiveFormat.detect("folder.rar.backup"))
    }
}
