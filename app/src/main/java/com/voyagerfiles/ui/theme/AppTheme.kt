package com.voyagerfiles.ui.theme

import androidx.annotation.StringRes
import com.voyagerfiles.R

/**
 * All available app themes.
 */
enum class AppTheme(@StringRes val displayNameRes: Int) {
    SYSTEM(R.string.theme_system),
    BLACK(R.string.theme_black),
    WHITE(R.string.theme_white),
    DARK(R.string.theme_dark),
    OCEAN(R.string.theme_ocean),
    PURPLE(R.string.theme_purple),
    FOREST(R.string.theme_forest),
    MOCHA(R.string.theme_mocha),
    MACCHIATO(R.string.theme_macchiato),
    FRAPPE(R.string.theme_frappe),
    LATTE(R.string.theme_latte),
    NORD(R.string.theme_nord),
    SOLARIZED_DARK(R.string.theme_solarized_dark),
    SOLARIZED_LIGHT(R.string.theme_solarized_light),
    GRUVBOX_DARK(R.string.theme_gruvbox_dark),
    GRUVBOX_LIGHT(R.string.theme_gruvbox_light),
    ROSE_PINE(R.string.theme_rose_pine),
    TOKYO_NIGHT(R.string.theme_tokyo_night),
    HIGH_CONTRAST(R.string.theme_high_contrast),
    CUSTOM(R.string.theme_custom);

    companion object {
        fun fromName(name: String): AppTheme =
            entries.find { it.name == name } ?: SYSTEM
    }
}
