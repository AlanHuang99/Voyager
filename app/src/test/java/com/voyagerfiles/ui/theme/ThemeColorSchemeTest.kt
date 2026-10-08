package com.voyagerfiles.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorSchemeTest {

    private val schemes: List<Pair<String, ColorScheme>> = AppTheme.entries.flatMap { theme ->
        listOf(false, true).map { systemIsDark ->
            "$theme (system dark: $systemIsDark)" to getColorScheme(theme, systemIsDark, customColorScheme = null)
        }
    }

    @Test
    fun everyThemeReplacesMaterialsBaselineSurfaceContainers() {
        val baselines = listOf(darkColorScheme(), lightColorScheme())
        schemes.forEach { (name, scheme) ->
            baselines.forEach { baseline ->
                containerRoles(scheme).zip(containerRoles(baseline)).forEach { (role, baselineRole) ->
                    if (role.second != scheme.surface) {
                        assertNotEquals("$name ${role.first}", baselineRole.second, role.second)
                    }
                }
            }
        }
    }

    @Test
    fun surfaceContainersStepAwayFromSurfaceInOrder() {
        schemes.forEach { (name, scheme) ->
            val dark = scheme.surface.luminance() < scheme.onSurface.luminance()
            val steps = listOf(
                scheme.surfaceContainerLowest,
                scheme.surface,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            ).map { if (dark) it.luminance() else -it.luminance() }
            steps.zipWithNext().forEach { (lower, higher) ->
                assertTrue("$name containers out of order: $steps", lower <= higher)
            }
        }
    }

    @Test
    fun dividersStayVisibleOnSheetsMenusAndDialogs() {
        schemes.forEach { (name, scheme) ->
            listOf(
                "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            ).forEach { (role, background) ->
                val contrast = contrast(scheme.outlineVariant, background)
                assertTrue(
                    "$name outlineVariant on $role has contrast $contrast",
                    contrast >= MIN_DIVIDER_CONTRAST,
                )
            }
        }
    }

    private fun containerRoles(scheme: ColorScheme): List<Pair<String, Color>> = listOf(
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        "surfaceDim" to scheme.surfaceDim,
        "surfaceBright" to scheme.surfaceBright,
    )

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private companion object {
        const val MIN_DIVIDER_CONTRAST = 1.3f
    }
}
