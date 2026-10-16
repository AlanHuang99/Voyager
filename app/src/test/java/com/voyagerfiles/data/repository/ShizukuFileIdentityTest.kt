package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShizukuFileIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun shellSessionPreservesItsSourceAndLocalPathIdentity() = runBlocking {
        val shell = RootShell(startProcess = { ProcessBuilder("sh", "-c", it).start() }, requireRoot = false)
        RootFileProvider(shell, FileSource.SHIZUKU).use { provider ->
            val item = provider.createFile(temporary.root.path, "literal'\n.txt").getOrThrow()
            assertEquals(FileSource.SHIZUKU, item.source)
            assertEquals(FileSource.SHIZUKU, provider.listFiles(temporary.root.path).getOrThrow().single().source)
            assertTrue(provider.isSameStorage(LocalFileProvider()))
            assertTrue(LocalFileProvider().isSamePath(item.path, provider, item.path))
            provider.getOutputStream(item.path).getOrThrow().use { it.write("shell bytes".toByteArray()) }
            assertEquals("shell bytes", provider.getInputStream(item.path).getOrThrow().bufferedReader().use { it.readText() })
            val renamed = provider.rename(item.path, "renamed.txt").getOrThrow()
            assertEquals(FileSource.SHIZUKU, renamed.source)
            provider.delete(renamed.path).getOrThrow()
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        }
    }
}
