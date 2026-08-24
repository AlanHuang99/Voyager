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

    private fun resourcesFor(locale: Locale) = ApplicationProvider.getApplicationContext<Context>()
        .createConfigurationContext(
            Configuration().apply {
                setLocale(locale)
            },
        )
        .resources
}
