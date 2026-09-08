package com.voyagerfiles.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Runs the actual command scripts against Android toybox without requiring a rooted device. */
class RootFileProviderAndroidTest {
    @Test fun toyboxListingEditingAndFileOperations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "root-commands-${UUID.randomUUID()}").apply { mkdir() }
        val shell = RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false)
        try {
            RootFileProvider(shell).use { root ->
                val file = root.createFile(folder.path, "quote'\n\$(id); config").getOrThrow()
                root.getOutputStream(file.path).getOrThrow().use { it.write("original\n".toByteArray()) }
                assertEquals(file.name, root.listFiles(folder.path).getOrThrow().single().name)
                val document = root.readText(file.path).getOrThrow()
                assertEquals("original\n", document.text)
                root.saveText(document, "saved\n").getOrThrow()
                assertEquals("saved\n", File(file.path).readText())
                assertTrue(root.saveText(document, "stale").isFailure)
                val dest = root.createDirectory(folder.path, "dest").getOrThrow()
                root.copy(file.path, dest.path).getOrThrow()
                assertEquals("saved\n", File(dest.path, file.name).readText())
                val renamed = root.rename(file.path, "renamed").getOrThrow()
                root.move(renamed.path, dest.path).getOrThrow()
                assertFalse(File(renamed.path).exists())
                root.delete(dest.path).getOrThrow()
                assertTrue(root.listFiles(folder.path).getOrThrow().isEmpty())
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun superuserResultIsExplicitAndLocalBrowsingStillWorks() = runBlocking {
        RootFileProvider().use { root ->
            val result = root.listFiles("/")
            result.fold(
                onSuccess = { items -> assertTrue(items.all { it.source == com.voyagerfiles.data.model.FileSource.ROOT }) },
                onFailure = { error -> assertFalse(error.message.isNullOrBlank()) },
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(LocalFileProvider().listFiles(context.cacheDir.path).isSuccess)
    }
}
