package com.voyagerfiles.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationResourceContractTest {
    @Test
    fun productionUiCopyUsesAndroidResources() {
        val sourceRoot = repositoryRoot().resolve("app/src/main/java")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                stripBlockComments(file.readLines()).mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    forbidden.firstOrNull { it.containsMatchIn(code) }
                        ?.let { "${file.relativeTo(repositoryRoot())}:${index + 1}: ${code.trim()}" }
                }.asSequence()
            }
            .toList()

        assertTrue(
            "Static user-facing copy must use Android resources:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun defaultResourcesContainLocalizationFoundation() {
        val stringsFile = repositoryRoot().resolve("app/src/main/res/values/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val stringNodes = document.getElementsByTagName("string")
        val pluralNodes = document.getElementsByTagName("plurals")
        val nonTranslatableNames = buildSet {
            for (index in 0 until stringNodes.length) {
                val element = stringNodes.item(index)
                if (element.attributes.getNamedItem("translatable")?.nodeValue == "false") {
                    add(element.attributes.getNamedItem("name").nodeValue)
                }
            }
        }

        assertTrue("strings.xml must contain localized UI copy", stringNodes.length > 1)
        assertTrue("strings.xml must contain at least one plural", pluralNodes.length > 0)
        assertTrue(
            "Brand and protocol names must be non-translatable",
            nonTranslatableNames.containsAll(
                setOf("app_name", "protocol_sftp", "protocol_ftp", "protocol_smb", "protocol_webdav"),
            ),
        )
    }

    private fun stripBlockComments(lines: List<String>): List<String> {
        var inBlockComment = false
        return lines.map { line ->
            val output = StringBuilder()
            var index = 0
            while (index < line.length) {
                when {
                    inBlockComment && line.startsWith("*/", index) -> {
                        inBlockComment = false
                        index += 2
                    }
                    inBlockComment -> index += 1
                    line.startsWith("/*", index) -> {
                        inBlockComment = true
                        index += 2
                    }
                    else -> {
                        output.append(line[index])
                        index += 1
                    }
                }
            }
            output.toString()
        }
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(workingDirectory, checkNotNull(workingDirectory.parentFile))
            .first { File(it, "app/src/main/res/values/strings.xml").isFile }
    }

    private companion object {
        val forbidden = listOf(
            Regex("\\bText\\(\\s*\\\""),
            Regex("contentDescription\\s*=\\s*\\\""),
            Regex("placeholder\\s*=\\s*\\{\\s*Text\\(\\s*\\\""),
            Regex("showSnackbar\\(\\s*\\\""),
            Regex("SnackbarHostState\\(\\).*showSnackbar\\(\\s*\\\""),
        )
    }
}
