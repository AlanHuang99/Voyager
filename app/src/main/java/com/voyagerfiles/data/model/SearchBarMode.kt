package com.voyagerfiles.data.model

import androidx.annotation.StringRes
import com.voyagerfiles.R

enum class SearchBarMode(@StringRes val labelRes: Int) {
    TOP(R.string.search_bar_top),
    COMPACT(R.string.search_bar_compact),
    BOTTOM(R.string.search_bar_bottom);

    companion object {
        fun fromName(name: String?): SearchBarMode = entries.firstOrNull { it.name == name } ?: TOP
    }
}
