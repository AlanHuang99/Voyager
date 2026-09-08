package com.voyagerfiles.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RootStagingSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun substitutedStagingDirectoryIsNeitherWrittenNorCleaned() = runBlocking {
        val original = temporary.newFile("original").apply { writeText("original bytes") }
        var substituted: File? = null
        val shell = RootShell(startProcess = { script ->
            if (script.contains("cat >&3")) {
                val stage = temporary.root.listFiles()!!.single { it.name.startsWith(".voyager-root-") }
                Files.move(stage.toPath(), File(temporary.root, "owned-stage").toPath())
                check(stage.mkdir())
                substituted = File(stage, "payload").apply { writeText("unrelated payload") }
            }
            ProcessBuilder("sh", "-c", script).start()
        }, requireRoot = false)
        RootFileProvider(shell).use { root ->
            val document = root.readText(original.path).getOrThrow()
            assertTrue(root.saveText(document, "replacement bytes").isFailure)
            assertNotNull("The staging directory must actually be replaced", substituted)
            assertEquals("original bytes", original.readText())
            assertEquals("unrelated payload", substituted!!.readText())
        }
    }

    @Test fun substitutedParentDirectoryCannotRedirectTheSave() = runBlocking {
        val directory = temporary.newFolder("parent")
        val original = File(directory, "original").apply { writeText("original bytes") }
        val movedParent = File(temporary.root, "owned-parent")
        var substituted = false
        val shell = RootShell(startProcess = { script ->
            if (script.contains("cat >&3")) {
                Files.move(directory.toPath(), movedParent.toPath())
                check(directory.mkdir())
                File(directory, "original").writeText("unrelated victim")
                substituted = true
            }
            ProcessBuilder("sh", "-c", script).start()
        }, requireRoot = false)
        RootFileProvider(shell).use { root ->
            val document = root.readText(original.path).getOrThrow()
            assertTrue(root.saveText(document, "replacement bytes").isFailure)
            assertTrue("The parent directory must actually be replaced", substituted)
            assertEquals("unrelated victim", original.readText())
            assertEquals("original bytes", File(movedParent, "original").readText())
        }
    }
}
