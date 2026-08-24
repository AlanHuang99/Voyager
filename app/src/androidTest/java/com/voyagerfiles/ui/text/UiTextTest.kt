package com.voyagerfiles.ui.text

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test
    fun resourceResolutionResolvesNestedArgumentsAtTheBoundary() {
        val resources = resourcesFor(Locale.US)
        val message = UiText.Resource(
            R.string.operation_failed,
            listOf(UiText.Resource(R.string.operation_download), UiText.Dynamic("disk full")),
        )

        assertEquals("Could not download: disk full", message.resolve(resources))
    }

    @Test
    fun resourceResolutionUsesTheCurrentFormattingLocale() {
        val message = UiText.Resource(R.string.localized_integer, listOf(12_345))

        assertEquals("12,345", message.resolve(resourcesFor(Locale.US)))
        assertEquals("12.345", message.resolve(resourcesFor(Locale.GERMANY)))
    }

    @Test
    fun pluralResolutionPreservesZeroOneAndOtherQuantities() {
        val resources = resourcesFor(Locale.US)

        assertEquals("0 selected", UiText.Plural(R.plurals.items_selected, 0, listOf(0)).resolve(resources))
        assertEquals("1 selected", UiText.Plural(R.plurals.items_selected, 1, listOf(1)).resolve(resources))
        assertEquals("2 selected", UiText.Plural(R.plurals.items_selected, 2, listOf(2)).resolve(resources))
        assertEquals("0 items", UiText.Plural(R.plurals.items_count, 0, listOf(0)).resolve(resources))
        assertEquals("1 item", UiText.Plural(R.plurals.items_count, 1, listOf(1)).resolve(resources))
        assertEquals("2 items", UiText.Plural(R.plurals.items_count, 2, listOf(2)).resolve(resources))
    }

    @Test
    fun dynamicTextRemainsUnchanged() {
        assertEquals("report.pdf", UiText.Dynamic("report.pdf").resolve(resourcesFor(Locale.US)))
    }

    @Test
    fun representativeUiFormatsResolveUnderUsAndGermanLocales() {
        val us = resourcesFor(Locale.US)
        val german = resourcesFor(Locale.GERMANY)
        val filename = UiText.Resource(
            R.string.dialog_delete_named_title,
            listOf(UiText.Dynamic("report.pdf")),
        )
        val count = UiText.Plural(R.plurals.items_count, 2, listOf(2))
        val percentage = UiText.Resource(R.string.localized_percentage, listOf(12.5))
        val host = UiText.Resource(
            R.string.connection_summary,
            listOf(UiText.Resource(R.string.protocol_webdav), UiText.Dynamic("files.example"), 8443),
        )
        val error = UiText.Resource(
            R.string.operation_failed,
            listOf(UiText.Resource(R.string.operation_download), UiText.Dynamic("disk full")),
        )

        assertEquals("Delete \"report.pdf\"?", filename.resolve(us))
        assertEquals("Delete \"report.pdf\"?", filename.resolve(german))
        assertEquals("2 items", count.resolve(us))
        assertEquals("2 items", count.resolve(german))
        assertEquals("12.5%", percentage.resolve(us))
        assertEquals("12,5%", percentage.resolve(german))
        assertEquals("WebDAV • files.example:8443", host.resolve(us))
        assertEquals("WebDAV • files.example:8443", host.resolve(german))
        assertEquals("Could not download: disk full", error.resolve(us))
        assertEquals("Could not download: disk full", error.resolve(german))
    }

    private fun resourcesFor(locale: Locale) = ApplicationProvider.getApplicationContext<Context>()
        .createConfigurationContext(
            Configuration().apply {
                setLocale(locale)
            },
        )
        .resources
}
