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
    NORD("Nord"),
    SOLARIZED_DARK("Solarized Dark"),
    SOLARIZED_LIGHT("Solarized Light"),
    GRUVBOX_DARK("Gruvbox Dark"),
    GRUVBOX_LIGHT("Gruvbox Light"),
    ROSE_PINE("Rosé Pine"),
    TOKYO_NIGHT("Tokyo Night"),
    HIGH_CONTRAST("High Contrast"),
    CUSTOM("Custom");

    companion object {
        fun fromName(name: String): AppTheme =
            entries.find { it.name == name } ?: SYSTEM
    }
}
