package com.voyagerfiles.ui.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

sealed interface UiText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Dynamic(val value: String) : UiText
}

fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Resource -> resources.getString(id, *args.map { it.resolveArgument(resources) }.toTypedArray())
    is UiText.Plural -> resources.getQuantityString(
        id,
        quantity,
        *args.map { it.resolveArgument(resources) }.toTypedArray(),
    )
    is UiText.Dynamic -> value
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current.resources)

private fun Any.resolveArgument(resources: Resources): Any =
    if (this is UiText) resolve(resources) else this
