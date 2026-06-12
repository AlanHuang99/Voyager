package com.voyagerfiles.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilsTest {

    @Test
    fun storageDirectoriesIncludeReadableExternalVolumes() {
        val directories = FileUtils.buildStorageDirectories(
            primaryExternalPath = "/storage/emulated/0",
            removableVolumePaths = listOf(
                "/storage/1234-5678",
                "/storage/1234-5678",
                "/storage/self",
                "/storage/emulated/0",
            ),
        )

        assertEquals(
            listOf(
                StorageDirectory("Internal Storage", "/storage/emulated/0"),
                StorageDirectory("External Storage", "/storage/1234-5678"),
            ),
            directories,
        )
    }

    @Test
    fun storageDirectoriesNumberMultipleExternalVolumes() {
        val directories = FileUtils.buildStorageDirectories(
            primaryExternalPath = "/storage/emulated/0",
            removableVolumePaths = listOf("/storage/1111-2222", "/storage/3333-4444"),
        )

        assertEquals(
            listOf(
                StorageDirectory("Internal Storage", "/storage/emulated/0"),
                StorageDirectory("External Storage", "/storage/1111-2222"),
                StorageDirectory("External Storage 2", "/storage/3333-4444"),
            ),
            directories,
        )
    }
}
