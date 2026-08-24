package com.voyagerfiles.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

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

    @Test
    fun defaultResourceNamesAreUniqueAndValuesAreNonempty() {
        val document = defaultResourcesDocument()
        val elements = resourceElements(document.documentElement)
        val names = elements.map { it.getAttribute("name") }
        val duplicateNames = names.groupingBy(String::toString).eachCount().filterValues { it > 1 }.keys
        val emptyValues = elements.flatMap { element ->
            when (element.tagName) {
                "string" -> listOfNotNull(
                    element.getAttribute("name").takeIf {
                        element.getAttribute("translatable") != "false" && element.textContent.trim().isEmpty()
                    },
                )
                "plurals" -> childElements(element).mapNotNull { item ->
                    "${element.getAttribute("name")}:${item.getAttribute("quantity")}".takeIf {
                        item.textContent.trim().isEmpty()
                    }
                }
                else -> emptyList()
            }
        }

        assertTrue("Resource names must be unique: ${duplicateNames.joinToString()}", duplicateNames.isEmpty())
        assertTrue("Translatable resource values must be nonempty: ${emptyValues.joinToString()}", emptyValues.isEmpty())
    }

    @Test
    fun pluralsAndFormatArgumentsAreTranslationSafe() {
        val document = defaultResourcesDocument()
        val elements = resourceElements(document.documentElement)
        val incompletePlurals = elements.filter { it.tagName == "plurals" }.mapNotNull { plural ->
            val quantities = childElements(plural).map { it.getAttribute("quantity") }.toSet()
            plural.getAttribute("name").takeUnless { quantities.containsAll(setOf("one", "other")) }
        }
        val unsafeFormats = elements.flatMap { element ->
            val values = if (element.tagName == "plurals") childElements(element) else listOf(element)
            values.mapNotNull { value ->
                val placeholders = formatPlaceholder.findAll(value.textContent).toList()
                val multipleArguments = placeholders.size > 1
                val allIndexed = placeholders.all { it.groupValues[1].isNotEmpty() }
                "${element.getAttribute("name")}:${value.getAttribute("quantity")}".takeIf {
                    multipleArguments && !allIndexed
                }
            }
        }

        assertTrue("Plurals must define one and other: ${incompletePlurals.joinToString()}", incompletePlurals.isEmpty())
        assertTrue("Multi-argument formats must use indexed placeholders: ${unsafeFormats.joinToString()}", unsafeFormats.isEmpty())
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

    private fun defaultResourcesDocument() = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(repositoryRoot().resolve("app/src/main/res/values/strings.xml"))

    private fun resourceElements(root: Element): List<Element> = childElements(root)
        .filter { it.tagName == "string" || it.tagName == "plurals" }

    private fun childElements(element: Element): List<Element> = buildList {
        val children = element.childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let(::add)
        }
    }

    private companion object {
        val forbidden = listOf(
            Regex("\\bText\\([^\\n]*\\\"[A-Za-z]"),
            Regex("\\bIcon\\([^\\n]*,\\s*\\\"[A-Za-z]"),
            Regex("contentDescription\\s*=\\s*\\\""),
            Regex("placeholder\\s*=\\s*\\{\\s*Text\\(\\s*\\\""),
            Regex("showSnackbar\\(\\s*\\\""),
            Regex("SnackbarHostState\\(\\).*showSnackbar\\(\\s*\\\""),
            Regex("Intent\\.createChooser\\([^\\n]*,\\s*\\\""),
            Regex("FileNameValidationResult\\.Invalid\\(\\s*\\\""),
        )
        val formatPlaceholder = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[a-zA-Z]")
    }
}
