package com.voyagerfiles.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val BlackColorScheme = darkColorScheme(
    primary = BlackColors.primary,
    onPrimary = BlackColors.onPrimary,
    primaryContainer = BlackColors.primaryContainer,
    onPrimaryContainer = BlackColors.onPrimaryContainer,
    secondary = BlackColors.secondary,
    onSecondary = BlackColors.onSecondary,
    secondaryContainer = BlackColors.secondaryContainer,
    onSecondaryContainer = BlackColors.onSecondaryContainer,
    tertiary = BlackColors.tertiary,
    onTertiary = BlackColors.onTertiary,
    tertiaryContainer = BlackColors.tertiaryContainer,
    onTertiaryContainer = BlackColors.onTertiaryContainer,
    background = BlackColors.background,
    onBackground = BlackColors.onBackground,
    surface = BlackColors.surface,
    onSurface = BlackColors.onSurface,
    surfaceVariant = BlackColors.surfaceVariant,
    onSurfaceVariant = BlackColors.onSurfaceVariant,
    error = BlackColors.error,
    onError = BlackColors.onError,
    errorContainer = BlackColors.errorContainer,
    onErrorContainer = BlackColors.onErrorContainer,
    outline = BlackColors.outline,
    outlineVariant = BlackColors.outlineVariant,
    inverseSurface = BlackColors.inverseSurface,
    inverseOnSurface = BlackColors.inverseOnSurface,
    inversePrimary = BlackColors.inversePrimary,
    surfaceTint = BlackColors.surfaceTint,
)

private val WhiteColorScheme = lightColorScheme(
    primary = WhiteColors.primary,
    onPrimary = WhiteColors.onPrimary,
    primaryContainer = WhiteColors.primaryContainer,
    onPrimaryContainer = WhiteColors.onPrimaryContainer,
    secondary = WhiteColors.secondary,
    onSecondary = WhiteColors.onSecondary,
    secondaryContainer = WhiteColors.secondaryContainer,
    onSecondaryContainer = WhiteColors.onSecondaryContainer,
    tertiary = WhiteColors.tertiary,
    onTertiary = WhiteColors.onTertiary,
    tertiaryContainer = WhiteColors.tertiaryContainer,
    onTertiaryContainer = WhiteColors.onTertiaryContainer,
    background = WhiteColors.background,
    onBackground = WhiteColors.onBackground,
    surface = WhiteColors.surface,
    onSurface = WhiteColors.onSurface,
    surfaceVariant = WhiteColors.surfaceVariant,
    onSurfaceVariant = WhiteColors.onSurfaceVariant,
    error = WhiteColors.error,
    onError = WhiteColors.onError,
    errorContainer = WhiteColors.errorContainer,
    onErrorContainer = WhiteColors.onErrorContainer,
    outline = WhiteColors.outline,
    outlineVariant = WhiteColors.outlineVariant,
    inverseSurface = WhiteColors.inverseSurface,
    inverseOnSurface = WhiteColors.inverseOnSurface,
    inversePrimary = WhiteColors.inversePrimary,
    surfaceTint = WhiteColors.surfaceTint,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkColors.primary,
    onPrimary = DarkColors.onPrimary,
    primaryContainer = DarkColors.primaryContainer,
    onPrimaryContainer = DarkColors.onPrimaryContainer,
    secondary = DarkColors.secondary,
    onSecondary = DarkColors.onSecondary,
    secondaryContainer = DarkColors.secondaryContainer,
    onSecondaryContainer = DarkColors.onSecondaryContainer,
    tertiary = DarkColors.tertiary,
    onTertiary = DarkColors.onTertiary,
    tertiaryContainer = DarkColors.tertiaryContainer,
    onTertiaryContainer = DarkColors.onTertiaryContainer,
    background = DarkColors.background,
    onBackground = DarkColors.onBackground,
    surface = DarkColors.surface,
    onSurface = DarkColors.onSurface,
    surfaceVariant = DarkColors.surfaceVariant,
    onSurfaceVariant = DarkColors.onSurfaceVariant,
    error = DarkColors.error,
    onError = DarkColors.onError,
    errorContainer = DarkColors.errorContainer,
    onErrorContainer = DarkColors.onErrorContainer,
    outline = DarkColors.outline,
    outlineVariant = DarkColors.outlineVariant,
    inverseSurface = DarkColors.inverseSurface,
    inverseOnSurface = DarkColors.inverseOnSurface,
    inversePrimary = DarkColors.inversePrimary,
    surfaceTint = DarkColors.surfaceTint,
)

private val OceanColorScheme = darkColorScheme(
    primary = OceanColors.primary,
    onPrimary = OceanColors.onPrimary,
    primaryContainer = OceanColors.primaryContainer,
    onPrimaryContainer = OceanColors.onPrimaryContainer,
    secondary = OceanColors.secondary,
    onSecondary = OceanColors.onSecondary,
    secondaryContainer = OceanColors.secondaryContainer,
    onSecondaryContainer = OceanColors.onSecondaryContainer,
    tertiary = OceanColors.tertiary,
    onTertiary = OceanColors.onTertiary,
    tertiaryContainer = OceanColors.tertiaryContainer,
    onTertiaryContainer = OceanColors.onTertiaryContainer,
    background = OceanColors.background,
    onBackground = OceanColors.onBackground,
    surface = OceanColors.surface,
    onSurface = OceanColors.onSurface,
    surfaceVariant = OceanColors.surfaceVariant,
    onSurfaceVariant = OceanColors.onSurfaceVariant,
    error = OceanColors.error,
    onError = OceanColors.onError,
    errorContainer = OceanColors.errorContainer,
    onErrorContainer = OceanColors.onErrorContainer,
    outline = OceanColors.outline,
    outlineVariant = OceanColors.outlineVariant,
    inverseSurface = OceanColors.inverseSurface,
    inverseOnSurface = OceanColors.inverseOnSurface,
    inversePrimary = OceanColors.inversePrimary,
    surfaceTint = OceanColors.surfaceTint,
)

private val PurpleColorScheme = darkColorScheme(
    primary = PurpleColors.primary,
    onPrimary = PurpleColors.onPrimary,
    primaryContainer = PurpleColors.primaryContainer,
    onPrimaryContainer = PurpleColors.onPrimaryContainer,
    secondary = PurpleColors.secondary,
    onSecondary = PurpleColors.onSecondary,
    secondaryContainer = PurpleColors.secondaryContainer,
    onSecondaryContainer = PurpleColors.onSecondaryContainer,
    tertiary = PurpleColors.tertiary,
    onTertiary = PurpleColors.onTertiary,
    tertiaryContainer = PurpleColors.tertiaryContainer,
    onTertiaryContainer = PurpleColors.onTertiaryContainer,
    background = PurpleColors.background,
    onBackground = PurpleColors.onBackground,
    surface = PurpleColors.surface,
    onSurface = PurpleColors.onSurface,
    surfaceVariant = PurpleColors.surfaceVariant,
    onSurfaceVariant = PurpleColors.onSurfaceVariant,
    error = PurpleColors.error,
    onError = PurpleColors.onError,
    errorContainer = PurpleColors.errorContainer,
    onErrorContainer = PurpleColors.onErrorContainer,
    outline = PurpleColors.outline,
    outlineVariant = PurpleColors.outlineVariant,
    inverseSurface = PurpleColors.inverseSurface,
    inverseOnSurface = PurpleColors.inverseOnSurface,
    inversePrimary = PurpleColors.inversePrimary,
    surfaceTint = PurpleColors.surfaceTint,
)

private val ForestColorScheme = darkColorScheme(
    primary = ForestColors.primary,
    onPrimary = ForestColors.onPrimary,
    primaryContainer = ForestColors.primaryContainer,
    onPrimaryContainer = ForestColors.onPrimaryContainer,
    secondary = ForestColors.secondary,
    onSecondary = ForestColors.onSecondary,
    secondaryContainer = ForestColors.secondaryContainer,
    onSecondaryContainer = ForestColors.onSecondaryContainer,
    tertiary = ForestColors.tertiary,
    onTertiary = ForestColors.onTertiary,
    tertiaryContainer = ForestColors.tertiaryContainer,
    onTertiaryContainer = ForestColors.onTertiaryContainer,
    background = ForestColors.background,
    onBackground = ForestColors.onBackground,
    surface = ForestColors.surface,
    onSurface = ForestColors.onSurface,
    surfaceVariant = ForestColors.surfaceVariant,
    onSurfaceVariant = ForestColors.onSurfaceVariant,
    error = ForestColors.error,
    onError = ForestColors.onError,
    errorContainer = ForestColors.errorContainer,
    onErrorContainer = ForestColors.onErrorContainer,
    outline = ForestColors.outline,
    outlineVariant = ForestColors.outlineVariant,
    inverseSurface = ForestColors.inverseSurface,
    inverseOnSurface = ForestColors.inverseOnSurface,
    inversePrimary = ForestColors.inversePrimary,
    surfaceTint = ForestColors.surfaceTint,
)

private val MochaColorScheme = darkColorScheme(
    primary = MochaColors.primary,
    onPrimary = MochaColors.onPrimary,
    primaryContainer = MochaColors.primaryContainer,
    onPrimaryContainer = MochaColors.onPrimaryContainer,
    secondary = MochaColors.secondary,
    onSecondary = MochaColors.onSecondary,
    secondaryContainer = MochaColors.secondaryContainer,
    onSecondaryContainer = MochaColors.onSecondaryContainer,
    tertiary = MochaColors.tertiary,
    onTertiary = MochaColors.onTertiary,
    tertiaryContainer = MochaColors.tertiaryContainer,
    onTertiaryContainer = MochaColors.onTertiaryContainer,
    background = MochaColors.background,
    onBackground = MochaColors.onBackground,
    surface = MochaColors.surface,
    onSurface = MochaColors.onSurface,
    surfaceVariant = MochaColors.surfaceVariant,
    onSurfaceVariant = MochaColors.onSurfaceVariant,
    error = MochaColors.error,
    onError = MochaColors.onError,
    errorContainer = MochaColors.errorContainer,
    onErrorContainer = MochaColors.onErrorContainer,
    outline = MochaColors.outline,
    outlineVariant = MochaColors.outlineVariant,
    inverseSurface = MochaColors.inverseSurface,
    inverseOnSurface = MochaColors.inverseOnSurface,
    inversePrimary = MochaColors.inversePrimary,
    surfaceTint = MochaColors.surfaceTint,
)

private val MacchiatoColorScheme = darkColorScheme(
    primary = MacchiatoColors.primary,
    onPrimary = MacchiatoColors.onPrimary,
    primaryContainer = MacchiatoColors.primaryContainer,
    onPrimaryContainer = MacchiatoColors.onPrimaryContainer,
    secondary = MacchiatoColors.secondary,
    onSecondary = MacchiatoColors.onSecondary,
    secondaryContainer = MacchiatoColors.secondaryContainer,
    onSecondaryContainer = MacchiatoColors.onSecondaryContainer,
    tertiary = MacchiatoColors.tertiary,
    onTertiary = MacchiatoColors.onTertiary,
    tertiaryContainer = MacchiatoColors.tertiaryContainer,
    onTertiaryContainer = MacchiatoColors.onTertiaryContainer,
    background = MacchiatoColors.background,
    onBackground = MacchiatoColors.onBackground,
    surface = MacchiatoColors.surface,
    onSurface = MacchiatoColors.onSurface,
    surfaceVariant = MacchiatoColors.surfaceVariant,
    onSurfaceVariant = MacchiatoColors.onSurfaceVariant,
    error = MacchiatoColors.error,
    onError = MacchiatoColors.onError,
    errorContainer = MacchiatoColors.errorContainer,
    onErrorContainer = MacchiatoColors.onErrorContainer,
    outline = MacchiatoColors.outline,
    outlineVariant = MacchiatoColors.outlineVariant,
    inverseSurface = MacchiatoColors.inverseSurface,
    inverseOnSurface = MacchiatoColors.inverseOnSurface,
    inversePrimary = MacchiatoColors.inversePrimary,
    surfaceTint = MacchiatoColors.surfaceTint,
)

private val FrappeColorScheme = darkColorScheme(
    primary = FrappeColors.primary,
    onPrimary = FrappeColors.onPrimary,
    primaryContainer = FrappeColors.primaryContainer,
    onPrimaryContainer = FrappeColors.onPrimaryContainer,
    secondary = FrappeColors.secondary,
    onSecondary = FrappeColors.onSecondary,
    secondaryContainer = FrappeColors.secondaryContainer,
    onSecondaryContainer = FrappeColors.onSecondaryContainer,
    tertiary = FrappeColors.tertiary,
    onTertiary = FrappeColors.onTertiary,
    tertiaryContainer = FrappeColors.tertiaryContainer,
    onTertiaryContainer = FrappeColors.onTertiaryContainer,
    background = FrappeColors.background,
    onBackground = FrappeColors.onBackground,
    surface = FrappeColors.surface,
    onSurface = FrappeColors.onSurface,
    surfaceVariant = FrappeColors.surfaceVariant,
    onSurfaceVariant = FrappeColors.onSurfaceVariant,
    error = FrappeColors.error,
    onError = FrappeColors.onError,
    errorContainer = FrappeColors.errorContainer,
    onErrorContainer = FrappeColors.onErrorContainer,
    outline = FrappeColors.outline,
    outlineVariant = FrappeColors.outlineVariant,
    inverseSurface = FrappeColors.inverseSurface,
    inverseOnSurface = FrappeColors.inverseOnSurface,
    inversePrimary = FrappeColors.inversePrimary,
    surfaceTint = FrappeColors.surfaceTint,
)

private val LatteColorScheme = lightColorScheme(
    primary = LatteColors.primary,
    onPrimary = LatteColors.onPrimary,
    primaryContainer = LatteColors.primaryContainer,
    onPrimaryContainer = LatteColors.onPrimaryContainer,
    secondary = LatteColors.secondary,
    onSecondary = LatteColors.onSecondary,
    secondaryContainer = LatteColors.secondaryContainer,
    onSecondaryContainer = LatteColors.onSecondaryContainer,
    tertiary = LatteColors.tertiary,
    onTertiary = LatteColors.onTertiary,
    tertiaryContainer = LatteColors.tertiaryContainer,
    onTertiaryContainer = LatteColors.onTertiaryContainer,
    background = LatteColors.background,
    onBackground = LatteColors.onBackground,
    surface = LatteColors.surface,
    onSurface = LatteColors.onSurface,
    surfaceVariant = LatteColors.surfaceVariant,
    onSurfaceVariant = LatteColors.onSurfaceVariant,
    error = LatteColors.error,
    onError = LatteColors.onError,
    errorContainer = LatteColors.errorContainer,
    onErrorContainer = LatteColors.onErrorContainer,
    outline = LatteColors.outline,
    outlineVariant = LatteColors.outlineVariant,
    inverseSurface = LatteColors.inverseSurface,
    inverseOnSurface = LatteColors.inverseOnSurface,
    inversePrimary = LatteColors.inversePrimary,
    surfaceTint = LatteColors.surfaceTint,
)

fun getColorScheme(
    theme: AppTheme,
    isDark: Boolean,
    customColorScheme: ColorScheme?,
): ColorScheme = when (theme) {
    AppTheme.SYSTEM -> if (isDark) DarkColorScheme else WhiteColorScheme
    AppTheme.BLACK -> BlackColorScheme
    AppTheme.WHITE -> WhiteColorScheme
    AppTheme.DARK -> DarkColorScheme
    AppTheme.OCEAN -> OceanColorScheme
    AppTheme.PURPLE -> PurpleColorScheme
    AppTheme.FOREST -> ForestColorScheme
    AppTheme.MOCHA -> MochaColorScheme
    AppTheme.MACCHIATO -> MacchiatoColorScheme
    AppTheme.FRAPPE -> FrappeColorScheme
    AppTheme.LATTE -> LatteColorScheme
    AppTheme.CUSTOM -> customColorScheme ?: if (isDark) DarkColorScheme else WhiteColorScheme
}

fun isDarkTheme(theme: AppTheme, systemIsDark: Boolean): Boolean = when (theme) {
    AppTheme.SYSTEM -> systemIsDark
    AppTheme.WHITE, AppTheme.LATTE -> false
    else -> true
}

@Composable
fun VoyagerTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    customColorScheme: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val systemIsDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val colorScheme = when {
        // Use dynamic color for SYSTEM theme on Android 12+
        appTheme == AppTheme.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (systemIsDark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        else -> getColorScheme(appTheme, systemIsDark, customColorScheme)
    }

    val darkTheme = isDarkTheme(appTheme, systemIsDark)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
