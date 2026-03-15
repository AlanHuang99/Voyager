package com.voyagerfiles.ui.theme

/**
 * All available app themes.
 */
enum class AppTheme(val displayName: String) {
    SYSTEM("System"),
    BLACK("Black"),
    WHITE("White"),
    DARK("Dark"),
    OCEAN("Ocean"),
    PURPLE("Purple"),
    FOREST("Forest"),
    MOCHA("Mocha"),
    MACCHIATO("Macchiato"),
    FRAPPE("Frappé"),
    LATTE("Latte"),
    CUSTOM("Custom");

    companion object {
        fun fromName(name: String): AppTheme =
            entries.find { it.name == name } ?: SYSTEM
    }
}
