package com.voyagerfiles.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootMountNamespaceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun privilegedLauncherRequestsTheGlobalNamespace() {
        assumeTrue(File("/bin/sh").canExecute())
        val su = temporary.newFile("su")
        su.writeText("""
            #!/bin/sh
            namespace=app
            script=
            while [ "${'$'}#" -gt 0 ]; do
                case "${'$'}1" in
                    --mount-master) namespace=global; shift ;;
                    -c) script="${'$'}2"; shift 2 ;;
                    *) exit 64 ;;
                esac
            done
            export VOYAGER_TEST_ROOT_NAMESPACE="${'$'}namespace"
            exec /bin/sh -c "${'$'}script"
        """.trimIndent() + "\n")
        check(su.setExecutable(true))
        RootShell(startProcess = { script ->
            ProcessBuilder(listOf(su.path) + rootSuCommand(script).drop(1)).start()
        }, requireRoot = false).use { shell ->
            val namespace = shell.execute("printenv VOYAGER_TEST_ROOT_NAMESPACE").toString(Charsets.UTF_8).trim()
            assertEquals("global", namespace)
        }
    }
}
