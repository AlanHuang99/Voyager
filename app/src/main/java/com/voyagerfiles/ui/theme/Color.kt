package com.voyagerfiles.ui.theme

import androidx.compose.ui.graphics.Color

interface AppColorTokens {
    val primary: Color
    val onPrimary: Color
    val primaryContainer: Color
    val onPrimaryContainer: Color
    val secondary: Color
    val onSecondary: Color
    val secondaryContainer: Color
    val onSecondaryContainer: Color
    val tertiary: Color
    val onTertiary: Color
    val tertiaryContainer: Color
    val onTertiaryContainer: Color
    val background: Color
    val onBackground: Color
    val surface: Color
    val onSurface: Color
    val surfaceVariant: Color
    val onSurfaceVariant: Color
    val error: Color
    val onError: Color
    val errorContainer: Color
    val onErrorContainer: Color
    val outline: Color
    val outlineVariant: Color
    val inverseSurface: Color
    val inverseOnSurface: Color
    val inversePrimary: Color
    val surfaceTint: Color
}

// ── System (Material You dynamic defaults / fallback) ──
object SystemColors {
    val primary = Color(0xFF6750A4)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFEADDFF)
    val onPrimaryContainer = Color(0xFF21005D)
    val secondary = Color(0xFF625B71)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFE8DEF8)
    val onSecondaryContainer = Color(0xFF1D192B)
    val tertiary = Color(0xFF7D5260)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFFFD8E4)
    val onTertiaryContainer = Color(0xFF31111D)
    val background = Color(0xFFFFFBFE)
    val onBackground = Color(0xFF1C1B1F)
    val surface = Color(0xFFFFFBFE)
    val onSurface = Color(0xFF1C1B1F)
    val surfaceVariant = Color(0xFFE7E0EC)
    val onSurfaceVariant = Color(0xFF49454F)
    val error = Color(0xFFB3261E)
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFF9DEDC)
    val onErrorContainer = Color(0xFF410E0B)
    val outline = Color(0xFF79747E)
    val outlineVariant = Color(0xFFCAC4D0)
    val inverseSurface = Color(0xFF313033)
    val inverseOnSurface = Color(0xFFF4EFF4)
    val inversePrimary = Color(0xFFD0BCFF)
    val surfaceTint = Color(0xFF6750A4)
}

// ── Black (AMOLED) ──
object BlackColors {
    val primary = Color(0xFFBB86FC)
    val onPrimary = Color(0xFF000000)
    val primaryContainer = Color(0xFF3700B3)
    val onPrimaryContainer = Color(0xFFE8D5FF)
    val secondary = Color(0xFF03DAC6)
    val onSecondary = Color(0xFF000000)
    val secondaryContainer = Color(0xFF005047)
    val onSecondaryContainer = Color(0xFF70F7E9)
    val tertiary = Color(0xFFCF6679)
    val onTertiary = Color(0xFF000000)
    val tertiaryContainer = Color(0xFF6B2232)
    val onTertiaryContainer = Color(0xFFFFD9DF)
    val background = Color(0xFF000000)
    val onBackground = Color(0xFFE0E0E0)
    val surface = Color(0xFF000000)
    val onSurface = Color(0xFFE0E0E0)
    val surfaceVariant = Color(0xFF1A1A1A)
    val onSurfaceVariant = Color(0xFFCACACA)
    val error = Color(0xFFCF6679)
    val onError = Color(0xFF000000)
    val errorContainer = Color(0xFF6B2232)
    val onErrorContainer = Color(0xFFFFD9DF)
    val outline = Color(0xFF444444)
    val outlineVariant = Color(0xFF2A2A2A)
    val inverseSurface = Color(0xFFE0E0E0)
    val inverseOnSurface = Color(0xFF000000)
    val inversePrimary = Color(0xFF6200EE)
    val surfaceTint = Color(0xFFBB86FC)
}

// ── White (Clean Light) ──
object WhiteColors {
    val primary = Color(0xFF1A73E8)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFD3E3FD)
    val onPrimaryContainer = Color(0xFF041E49)
    val secondary = Color(0xFF5F6368)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFE8EAED)
    val onSecondaryContainer = Color(0xFF202124)
    val tertiary = Color(0xFF188038)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFCEEAD6)
    val onTertiaryContainer = Color(0xFF0D3B1D)
    val background = Color(0xFFFFFFFF)
    val onBackground = Color(0xFF202124)
    val surface = Color(0xFFFFFFFF)
    val onSurface = Color(0xFF202124)
    val surfaceVariant = Color(0xFFF1F3F4)
    val onSurfaceVariant = Color(0xFF5F6368)
    val error = Color(0xFFD93025)
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFFCE8E6)
    val onErrorContainer = Color(0xFF5F1412)
    val outline = Color(0xFFDADCE0)
    val outlineVariant = Color(0xFFE8EAED)
    val inverseSurface = Color(0xFF303134)
    val inverseOnSurface = Color(0xFFF1F3F4)
    val inversePrimary = Color(0xFF8AB4F8)
    val surfaceTint = Color(0xFF1A73E8)
}

// ── Dark (Material Dark) ──
object DarkColors {
    val primary = Color(0xFF90CAF9)
    val onPrimary = Color(0xFF0D293E)
    val primaryContainer = Color(0xFF1565C0)
    val onPrimaryContainer = Color(0xFFD4E8FC)
    val secondary = Color(0xFFA5D6A7)
    val onSecondary = Color(0xFF0E3311)
    val secondaryContainer = Color(0xFF2E7D32)
    val onSecondaryContainer = Color(0xFFCBEECC)
    val tertiary = Color(0xFFFFCC80)
    val onTertiary = Color(0xFF3E2800)
    val tertiaryContainer = Color(0xFFEF6C00)
    val onTertiaryContainer = Color(0xFFFFE6C4)
    val background = Color(0xFF121212)
    val onBackground = Color(0xFFE1E1E1)
    val surface = Color(0xFF1E1E1E)
    val onSurface = Color(0xFFE1E1E1)
    val surfaceVariant = Color(0xFF2C2C2C)
    val onSurfaceVariant = Color(0xFFBDBDBD)
    val error = Color(0xFFEF9A9A)
    val onError = Color(0xFF3B0A0A)
    val errorContainer = Color(0xFFC62828)
    val onErrorContainer = Color(0xFFFFCDD2)
    val outline = Color(0xFF555555)
    val outlineVariant = Color(0xFF383838)
    val inverseSurface = Color(0xFFE1E1E1)
    val inverseOnSurface = Color(0xFF1E1E1E)
    val inversePrimary = Color(0xFF1565C0)
    val surfaceTint = Color(0xFF90CAF9)
}

// ── Ocean ──
object OceanColors {
    val primary = Color(0xFF4FC3F7)
    val onPrimary = Color(0xFF003547)
    val primaryContainer = Color(0xFF006781)
    val onPrimaryContainer = Color(0xFFBDE9FF)
    val secondary = Color(0xFF80DEEA)
    val onSecondary = Color(0xFF003D44)
    val secondaryContainer = Color(0xFF00696F)
    val onSecondaryContainer = Color(0xFFA5F1F8)
    val tertiary = Color(0xFFA5D6A7)
    val onTertiary = Color(0xFF0E3311)
    val tertiaryContainer = Color(0xFF1B5E20)
    val onTertiaryContainer = Color(0xFFCBEECC)
    val background = Color(0xFF0A1929)
    val onBackground = Color(0xFFD6E4F0)
    val surface = Color(0xFF0D2137)
    val onSurface = Color(0xFFD6E4F0)
    val surfaceVariant = Color(0xFF132F4C)
    val onSurfaceVariant = Color(0xFFB2C5D6)
    val error = Color(0xFFFF8A80)
    val onError = Color(0xFF370B07)
    val errorContainer = Color(0xFFC62828)
    val onErrorContainer = Color(0xFFFFCDD2)
    val outline = Color(0xFF3E5F7A)
    val outlineVariant = Color(0xFF1E3A56)
    val inverseSurface = Color(0xFFD6E4F0)
    val inverseOnSurface = Color(0xFF0A1929)
    val inversePrimary = Color(0xFF0277BD)
    val surfaceTint = Color(0xFF4FC3F7)
}

// ── Purple ──
object PurpleColors {
    val primary = Color(0xFFCE93D8)
    val onPrimary = Color(0xFF2E0D35)
    val primaryContainer = Color(0xFF6A1B9A)
    val onPrimaryContainer = Color(0xFFF1D8F6)
    val secondary = Color(0xFFB39DDB)
    val onSecondary = Color(0xFF1F0E3D)
    val secondaryContainer = Color(0xFF512DA8)
    val onSecondaryContainer = Color(0xFFE0D1F5)
    val tertiary = Color(0xFFF48FB1)
    val onTertiary = Color(0xFF3E0D1C)
    val tertiaryContainer = Color(0xFFC2185B)
    val onTertiaryContainer = Color(0xFFFDD7E2)
    val background = Color(0xFF1A0E2E)
    val onBackground = Color(0xFFE5DCF0)
    val surface = Color(0xFF211338)
    val onSurface = Color(0xFFE5DCF0)
    val surfaceVariant = Color(0xFF2D1B47)
    val onSurfaceVariant = Color(0xFFCBB8DE)
    val error = Color(0xFFFF8A80)
    val onError = Color(0xFF370B07)
    val errorContainer = Color(0xFFC62828)
    val onErrorContainer = Color(0xFFFFCDD2)
    val outline = Color(0xFF5B4577)
    val outlineVariant = Color(0xFF3A2558)
    val inverseSurface = Color(0xFFE5DCF0)
    val inverseOnSurface = Color(0xFF1A0E2E)
    val inversePrimary = Color(0xFF7B1FA2)
    val surfaceTint = Color(0xFFCE93D8)
}

// ── Forest ──
object ForestColors {
    val primary = Color(0xFFA5D6A7)
    val onPrimary = Color(0xFF0E3311)
    val primaryContainer = Color(0xFF2E7D32)
    val onPrimaryContainer = Color(0xFFCBEECC)
    val secondary = Color(0xFFC8E6C9)
    val onSecondary = Color(0xFF1B3A1D)
    val secondaryContainer = Color(0xFF388E3C)
    val onSecondaryContainer = Color(0xFFDFF2E0)
    val tertiary = Color(0xFFFFCC80)
    val onTertiary = Color(0xFF3E2800)
    val tertiaryContainer = Color(0xFFE65100)
    val onTertiaryContainer = Color(0xFFFFE0B2)
    val background = Color(0xFF0D1F12)
    val onBackground = Color(0xFFD5E8D4)
    val surface = Color(0xFF122917)
    val onSurface = Color(0xFFD5E8D4)
    val surfaceVariant = Color(0xFF1A371F)
    val onSurfaceVariant = Color(0xFFB5CDB7)
    val error = Color(0xFFFF8A80)
    val onError = Color(0xFF370B07)
    val errorContainer = Color(0xFFC62828)
    val onErrorContainer = Color(0xFFFFCDD2)
    val outline = Color(0xFF3E6B43)
    val outlineVariant = Color(0xFF254D2A)
    val inverseSurface = Color(0xFFD5E8D4)
    val inverseOnSurface = Color(0xFF0D1F12)
    val inversePrimary = Color(0xFF1B5E20)
    val surfaceTint = Color(0xFFA5D6A7)
}

// ── Catppuccin Mocha ──
object MochaColors {
    val primary = Color(0xFFB4BEFE)       // Lavender
    val onPrimary = Color(0xFF1E1E2E)
    val primaryContainer = Color(0xFF585B70) // Surface2
    val onPrimaryContainer = Color(0xFFCDD6F4) // Text
    val secondary = Color(0xFFA6E3A1)     // Green
    val onSecondary = Color(0xFF1E1E2E)
    val secondaryContainer = Color(0xFF45475A) // Surface1
    val onSecondaryContainer = Color(0xFFCDD6F4)
    val tertiary = Color(0xFFF5C2E7)      // Pink
    val onTertiary = Color(0xFF1E1E2E)
    val tertiaryContainer = Color(0xFF45475A)
    val onTertiaryContainer = Color(0xFFCDD6F4)
    val background = Color(0xFF1E1E2E)    // Base
    val onBackground = Color(0xFFCDD6F4)  // Text
    val surface = Color(0xFF181825)       // Mantle
    val onSurface = Color(0xFFCDD6F4)
    val surfaceVariant = Color(0xFF313244) // Surface0
    val onSurfaceVariant = Color(0xFFBAC2DE) // Subtext1
    val error = Color(0xFFF38BA8)         // Red
    val onError = Color(0xFF1E1E2E)
    val errorContainer = Color(0xFF45475A)
    val onErrorContainer = Color(0xFFF38BA8)
    val outline = Color(0xFF6C7086)       // Overlay1
    val outlineVariant = Color(0xFF585B70)
    val inverseSurface = Color(0xFFCDD6F4)
    val inverseOnSurface = Color(0xFF1E1E2E)
    val inversePrimary = Color(0xFF7287FD) // Blue (Latte)
    val surfaceTint = Color(0xFFB4BEFE)
}

// ── Catppuccin Macchiato ──
object MacchiatoColors {
    val primary = Color(0xFFB7BDF8)       // Lavender
    val onPrimary = Color(0xFF24273A)
    val primaryContainer = Color(0xFF5B6078)
    val onPrimaryContainer = Color(0xFFCAD3F5)
    val secondary = Color(0xFFA6DA95)     // Green
    val onSecondary = Color(0xFF24273A)
    val secondaryContainer = Color(0xFF494D64)
    val onSecondaryContainer = Color(0xFFCAD3F5)
    val tertiary = Color(0xFFF5BDE6)      // Pink
    val onTertiary = Color(0xFF24273A)
    val tertiaryContainer = Color(0xFF494D64)
    val onTertiaryContainer = Color(0xFFCAD3F5)
    val background = Color(0xFF24273A)    // Base
    val onBackground = Color(0xFFCAD3F5)  // Text
    val surface = Color(0xFF1E2030)       // Mantle
    val onSurface = Color(0xFFCAD3F5)
    val surfaceVariant = Color(0xFF363A4F) // Surface0
    val onSurfaceVariant = Color(0xFFB8C0E0) // Subtext1
    val error = Color(0xFFED8796)         // Red
    val onError = Color(0xFF24273A)
    val errorContainer = Color(0xFF494D64)
    val onErrorContainer = Color(0xFFED8796)
    val outline = Color(0xFF6E738D)       // Overlay1
    val outlineVariant = Color(0xFF5B6078)
    val inverseSurface = Color(0xFFCAD3F5)
    val inverseOnSurface = Color(0xFF24273A)
    val inversePrimary = Color(0xFF7287FD)
    val surfaceTint = Color(0xFFB7BDF8)
}

// ── Catppuccin Frappé ──
object FrappeColors {
    val primary = Color(0xFFBABBF1)       // Lavender
    val onPrimary = Color(0xFF303446)
    val primaryContainer = Color(0xFF626880)
    val onPrimaryContainer = Color(0xFFC6D0F5)
    val secondary = Color(0xFFA6D189)     // Green
    val onSecondary = Color(0xFF303446)
    val secondaryContainer = Color(0xFF51576D)
    val onSecondaryContainer = Color(0xFFC6D0F5)
    val tertiary = Color(0xFFF4B8E4)      // Pink
    val onTertiary = Color(0xFF303446)
    val tertiaryContainer = Color(0xFF51576D)
    val onTertiaryContainer = Color(0xFFC6D0F5)
    val background = Color(0xFF303446)    // Base
    val onBackground = Color(0xFFC6D0F5)  // Text
    val surface = Color(0xFF292C3C)       // Mantle
    val onSurface = Color(0xFFC6D0F5)
    val surfaceVariant = Color(0xFF414559) // Surface0
    val onSurfaceVariant = Color(0xFFB5BFE2) // Subtext1
    val error = Color(0xFFE78284)         // Red
    val onError = Color(0xFF303446)
    val errorContainer = Color(0xFF51576D)
    val onErrorContainer = Color(0xFFE78284)
    val outline = Color(0xFF737994)       // Overlay1
    val outlineVariant = Color(0xFF626880)
    val inverseSurface = Color(0xFFC6D0F5)
    val inverseOnSurface = Color(0xFF303446)
    val inversePrimary = Color(0xFF7287FD)
    val surfaceTint = Color(0xFFBABBF1)
}

// ── Catppuccin Latte ──
object LatteColors {
    val primary = Color(0xFF7287FD)       // Lavender
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFBCC0CC)
    val onPrimaryContainer = Color(0xFF4C4F69)
    val secondary = Color(0xFF40A02B)     // Green
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFCCD0DA)
    val onSecondaryContainer = Color(0xFF4C4F69)
    val tertiary = Color(0xFFEA76CB)      // Pink
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFCCD0DA)
    val onTertiaryContainer = Color(0xFF4C4F69)
    val background = Color(0xFFEFF1F5)    // Base
    val onBackground = Color(0xFF4C4F69)  // Text
    val surface = Color(0xFFE6E9EF)       // Mantle
    val onSurface = Color(0xFF4C4F69)
    val surfaceVariant = Color(0xFFCCD0DA) // Surface0
    val onSurfaceVariant = Color(0xFF5C5F77) // Subtext1
    val error = Color(0xFFD20F39)         // Red
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFCCD0DA)
    val onErrorContainer = Color(0xFFD20F39)
    val outline = Color(0xFF8C8FA1)       // Overlay1
    val outlineVariant = Color(0xFFBCC0CC)
    val inverseSurface = Color(0xFF4C4F69)
    val inverseOnSurface = Color(0xFFEFF1F5)
    val inversePrimary = Color(0xFFBABBF1)
    val surfaceTint = Color(0xFF7287FD)
}

// ── Nord ──
object NordColors : AppColorTokens {
    override val primary = Color(0xFF88C0D0)
    override val onPrimary = Color(0xFF102027)
    override val primaryContainer = Color(0xFF5E81AC)
    override val onPrimaryContainer = Color(0xFFE5F4FA)
    override val secondary = Color(0xFFA3BE8C)
    override val onSecondary = Color(0xFF18210F)
    override val secondaryContainer = Color(0xFF4C6A3D)
    override val onSecondaryContainer = Color(0xFFE7F2DD)
    override val tertiary = Color(0xFFB48EAD)
    override val onTertiary = Color(0xFF2A1832)
    override val tertiaryContainer = Color(0xFF6D5974)
    override val onTertiaryContainer = Color(0xFFF4E5F2)
    override val background = Color(0xFF2E3440)
    override val onBackground = Color(0xFFECEFF4)
    override val surface = Color(0xFF3B4252)
    override val onSurface = Color(0xFFECEFF4)
    override val surfaceVariant = Color(0xFF434C5E)
    override val onSurfaceVariant = Color(0xFFD8DEE9)
    override val error = Color(0xFFBF616A)
    override val onError = Color(0xFF2A090D)
    override val errorContainer = Color(0xFF7D3038)
    override val onErrorContainer = Color(0xFFFFDAD8)
    override val outline = Color(0xFF6D788A)
    override val outlineVariant = Color(0xFF4C566A)
    override val inverseSurface = Color(0xFFECEFF4)
    override val inverseOnSurface = Color(0xFF2E3440)
    override val inversePrimary = Color(0xFF3B6B7A)
    override val surfaceTint = Color(0xFF88C0D0)
}

// ── Solarized Dark ──
object SolarizedDarkColors : AppColorTokens {
    override val primary = Color(0xFF268BD2)
    override val onPrimary = Color(0xFFFFFFFF)
    override val primaryContainer = Color(0xFF0D4F75)
    override val onPrimaryContainer = Color(0xFFD1ECFF)
    override val secondary = Color(0xFF2AA198)
    override val onSecondary = Color(0xFF002B28)
    override val secondaryContainer = Color(0xFF11645E)
    override val onSecondaryContainer = Color(0xFFC7F3EF)
    override val tertiary = Color(0xFFB58900)
    override val onTertiary = Color(0xFF2F2300)
    override val tertiaryContainer = Color(0xFF6B5100)
    override val onTertiaryContainer = Color(0xFFFFE6A6)
    override val background = Color(0xFF002B36)
    override val onBackground = Color(0xFFEEE8D5)
    override val surface = Color(0xFF073642)
    override val onSurface = Color(0xFFEEE8D5)
    override val surfaceVariant = Color(0xFF0B3A42)
    override val onSurfaceVariant = Color(0xFF93A1A1)
    override val error = Color(0xFFDC322F)
    override val onError = Color(0xFFFFFFFF)
    override val errorContainer = Color(0xFF7B1716)
    override val onErrorContainer = Color(0xFFFFDAD8)
    override val outline = Color(0xFF586E75)
    override val outlineVariant = Color(0xFF12414C)
    override val inverseSurface = Color(0xFFEEE8D5)
    override val inverseOnSurface = Color(0xFF002B36)
    override val inversePrimary = Color(0xFF0066A0)
    override val surfaceTint = Color(0xFF268BD2)
}

// ── Solarized Light ──
object SolarizedLightColors : AppColorTokens {
    override val primary = Color(0xFF268BD2)
    override val onPrimary = Color(0xFFFFFFFF)
    override val primaryContainer = Color(0xFFC9E6F8)
    override val onPrimaryContainer = Color(0xFF00344F)
    override val secondary = Color(0xFF2AA198)
    override val onSecondary = Color(0xFFFFFFFF)
    override val secondaryContainer = Color(0xFFCBEDEA)
    override val onSecondaryContainer = Color(0xFF003B37)
    override val tertiary = Color(0xFFB58900)
    override val onTertiary = Color(0xFFFFFFFF)
    override val tertiaryContainer = Color(0xFFFFE8A6)
    override val onTertiaryContainer = Color(0xFF3D2C00)
    override val background = Color(0xFFFDF6E3)
    override val onBackground = Color(0xFF073642)
    override val surface = Color(0xFFF6EFD6)
    override val onSurface = Color(0xFF073642)
    override val surfaceVariant = Color(0xFFEDE5C8)
    override val onSurfaceVariant = Color(0xFF586E75)
    override val error = Color(0xFFDC322F)
    override val onError = Color(0xFFFFFFFF)
    override val errorContainer = Color(0xFFFFDAD8)
    override val onErrorContainer = Color(0xFF410003)
    override val outline = Color(0xFF93A1A1)
    override val outlineVariant = Color(0xFFD8CFB2)
    override val inverseSurface = Color(0xFF073642)
    override val inverseOnSurface = Color(0xFFFDF6E3)
    override val inversePrimary = Color(0xFF85C8F2)
    override val surfaceTint = Color(0xFF268BD2)
}

// ── Gruvbox Dark ──
object GruvboxDarkColors : AppColorTokens {
    override val primary = Color(0xFFFABD2F)
    override val onPrimary = Color(0xFF3C2A00)
    override val primaryContainer = Color(0xFF7C5A00)
    override val onPrimaryContainer = Color(0xFFFFE8A6)
    override val secondary = Color(0xFFB8BB26)
    override val onSecondary = Color(0xFF292B00)
    override val secondaryContainer = Color(0xFF5F6200)
    override val onSecondaryContainer = Color(0xFFF3F5A4)
    override val tertiary = Color(0xFF83A598)
    override val onTertiary = Color(0xFF0D2A23)
    override val tertiaryContainer = Color(0xFF3D675A)
    override val onTertiaryContainer = Color(0xFFD6EEE6)
    override val background = Color(0xFF282828)
    override val onBackground = Color(0xFFEBDBB2)
    override val surface = Color(0xFF32302F)
    override val onSurface = Color(0xFFEBDBB2)
    override val surfaceVariant = Color(0xFF3C3836)
    override val onSurfaceVariant = Color(0xFFD5C4A1)
    override val error = Color(0xFFFB4934)
    override val onError = Color(0xFF3B0700)
    override val errorContainer = Color(0xFF8F1D12)
    override val onErrorContainer = Color(0xFFFFDAD5)
    override val outline = Color(0xFF928374)
    override val outlineVariant = Color(0xFF504945)
    override val inverseSurface = Color(0xFFEBDBB2)
    override val inverseOnSurface = Color(0xFF282828)
    override val inversePrimary = Color(0xFFB57614)
    override val surfaceTint = Color(0xFFFABD2F)
}

// ── Gruvbox Light ──
object GruvboxLightColors : AppColorTokens {
    override val primary = Color(0xFFB57614)
    override val onPrimary = Color(0xFFFFFFFF)
    override val primaryContainer = Color(0xFFFFDFA2)
    override val onPrimaryContainer = Color(0xFF3A2400)
    override val secondary = Color(0xFF79740E)
    override val onSecondary = Color(0xFFFFFFFF)
    override val secondaryContainer = Color(0xFFE7E2A3)
    override val onSecondaryContainer = Color(0xFF262500)
    override val tertiary = Color(0xFF076678)
    override val onTertiary = Color(0xFFFFFFFF)
    override val tertiaryContainer = Color(0xFFB8E7F0)
    override val onTertiaryContainer = Color(0xFF002F38)
    override val background = Color(0xFFFBF1C7)
    override val onBackground = Color(0xFF3C3836)
    override val surface = Color(0xFFF2E5BC)
    override val onSurface = Color(0xFF3C3836)
    override val surfaceVariant = Color(0xFFEBDCB2)
    override val onSurfaceVariant = Color(0xFF665C54)
    override val error = Color(0xFFCC241D)
    override val onError = Color(0xFFFFFFFF)
    override val errorContainer = Color(0xFFFFDAD5)
    override val onErrorContainer = Color(0xFF410001)
    override val outline = Color(0xFF928374)
    override val outlineVariant = Color(0xFFD5C4A1)
    override val inverseSurface = Color(0xFF3C3836)
    override val inverseOnSurface = Color(0xFFFBF1C7)
    override val inversePrimary = Color(0xFFFABD2F)
    override val surfaceTint = Color(0xFFB57614)
}

// ── Rose Pine ──
object RosePineColors : AppColorTokens {
    override val primary = Color(0xFFC4A7E7)
    override val onPrimary = Color(0xFF22122F)
    override val primaryContainer = Color(0xFF5D4775)
    override val onPrimaryContainer = Color(0xFFF1E4FF)
    override val secondary = Color(0xFF9CCFD8)
    override val onSecondary = Color(0xFF0A2B31)
    override val secondaryContainer = Color(0xFF3E6D75)
    override val onSecondaryContainer = Color(0xFFD6F4F8)
    override val tertiary = Color(0xFFF6C177)
    override val onTertiary = Color(0xFF3A2500)
    override val tertiaryContainer = Color(0xFF7A5616)
    override val onTertiaryContainer = Color(0xFFFFE4B8)
    override val background = Color(0xFF191724)
    override val onBackground = Color(0xFFE0DEF4)
    override val surface = Color(0xFF1F1D2E)
    override val onSurface = Color(0xFFE0DEF4)
    override val surfaceVariant = Color(0xFF26233A)
    override val onSurfaceVariant = Color(0xFF908CAA)
    override val error = Color(0xFFEB6F92)
    override val onError = Color(0xFF3A0717)
    override val errorContainer = Color(0xFF7B2A42)
    override val onErrorContainer = Color(0xFFFFD9E2)
    override val outline = Color(0xFF6E6A86)
    override val outlineVariant = Color(0xFF403D52)
    override val inverseSurface = Color(0xFFE0DEF4)
    override val inverseOnSurface = Color(0xFF191724)
    override val inversePrimary = Color(0xFF8B62B3)
    override val surfaceTint = Color(0xFFC4A7E7)
}

// ── Tokyo Night ──
object TokyoNightColors : AppColorTokens {
    override val primary = Color(0xFF7AA2F7)
    override val onPrimary = Color(0xFF001E44)
    override val primaryContainer = Color(0xFF2F5EBC)
    override val onPrimaryContainer = Color(0xFFDCE7FF)
    override val secondary = Color(0xFF9ECE6A)
    override val onSecondary = Color(0xFF142500)
    override val secondaryContainer = Color(0xFF4A6D20)
    override val onSecondaryContainer = Color(0xFFE3F7C8)
    override val tertiary = Color(0xFFE0AF68)
    override val onTertiary = Color(0xFF332100)
    override val tertiaryContainer = Color(0xFF7A5319)
    override val onTertiaryContainer = Color(0xFFFFE2B9)
    override val background = Color(0xFF1A1B26)
    override val onBackground = Color(0xFFC0CAF5)
    override val surface = Color(0xFF16161E)
    override val onSurface = Color(0xFFC0CAF5)
    override val surfaceVariant = Color(0xFF24283B)
    override val onSurfaceVariant = Color(0xFFA9B1D6)
    override val error = Color(0xFFF7768E)
    override val onError = Color(0xFF3A0714)
    override val errorContainer = Color(0xFF872B3C)
    override val onErrorContainer = Color(0xFFFFD9DF)
    override val outline = Color(0xFF565F89)
    override val outlineVariant = Color(0xFF32344A)
    override val inverseSurface = Color(0xFFC0CAF5)
    override val inverseOnSurface = Color(0xFF1A1B26)
    override val inversePrimary = Color(0xFF2F5EBC)
    override val surfaceTint = Color(0xFF7AA2F7)
}

// ── High Contrast ──
object HighContrastColors : AppColorTokens {
    override val primary = Color(0xFFFFD400)
    override val onPrimary = Color(0xFF000000)
    override val primaryContainer = Color(0xFF4A3D00)
    override val onPrimaryContainer = Color(0xFFFFF2A6)
    override val secondary = Color(0xFF00E5FF)
    override val onSecondary = Color(0xFF000000)
    override val secondaryContainer = Color(0xFF004D57)
    override val onSecondaryContainer = Color(0xFFC8F8FF)
    override val tertiary = Color(0xFFFF4DFF)
    override val onTertiary = Color(0xFF000000)
    override val tertiaryContainer = Color(0xFF5C005C)
    override val onTertiaryContainer = Color(0xFFFFD6FF)
    override val background = Color(0xFF000000)
    override val onBackground = Color(0xFFFFFFFF)
    override val surface = Color(0xFF0B0B0B)
    override val onSurface = Color(0xFFFFFFFF)
    override val surfaceVariant = Color(0xFF202020)
    override val onSurfaceVariant = Color(0xFFE6E6E6)
    override val error = Color(0xFFFF453A)
    override val onError = Color(0xFF000000)
    override val errorContainer = Color(0xFF5D0A06)
    override val onErrorContainer = Color(0xFFFFDAD7)
    override val outline = Color(0xFFFFFFFF)
    override val outlineVariant = Color(0xFF606060)
    override val inverseSurface = Color(0xFFFFFFFF)
    override val inverseOnSurface = Color(0xFF000000)
    override val inversePrimary = Color(0xFF8A7200)
    override val surfaceTint = Color(0xFFFFD400)
}
