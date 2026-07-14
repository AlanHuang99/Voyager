package com.voyagerfiles.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voyagerfiles.BuildConfig
import com.voyagerfiles.ui.theme.AppTheme
import com.voyagerfiles.ui.theme.BlackColors
import com.voyagerfiles.ui.theme.DarkColors
import com.voyagerfiles.ui.theme.ForestColors
import com.voyagerfiles.ui.theme.FrappeColors
import com.voyagerfiles.ui.theme.GruvboxDarkColors
import com.voyagerfiles.ui.theme.GruvboxLightColors
import com.voyagerfiles.ui.theme.HighContrastColors
import com.voyagerfiles.ui.theme.LatteColors
import com.voyagerfiles.ui.theme.MacchiatoColors
import com.voyagerfiles.ui.theme.MochaColors
import com.voyagerfiles.ui.theme.NordColors
import com.voyagerfiles.ui.theme.OceanColors
import com.voyagerfiles.ui.theme.PurpleColors
import com.voyagerfiles.ui.theme.RosePineColors
import com.voyagerfiles.ui.theme.SolarizedDarkColors
import com.voyagerfiles.ui.theme.SolarizedLightColors
import com.voyagerfiles.ui.theme.SystemColors
import com.voyagerfiles.ui.theme.TokyoNightColors
import com.voyagerfiles.ui.theme.WhiteColors
import com.voyagerfiles.viewmodel.FileBrowserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
) {
    val currentTheme by viewModel.theme.collectAsState()
    val browseState by viewModel.browseState.collectAsState()
    val useTrash by viewModel.useTrash.collectAsState()
    var selectedThemeCategoryIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedThemeCategory = themeCategories[selectedThemeCategoryIndex]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Theme",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            ScrollableTabRow(
                selectedTabIndex = selectedThemeCategoryIndex,
                edgePadding = 0.dp,
            ) {
                themeCategories.forEachIndexed { index, category ->
                    Tab(
                        selected = selectedThemeCategoryIndex == index,
                        onClick = { selectedThemeCategoryIndex = index },
                        text = { Text(category.label) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selectedThemeCategory.themes.forEach { theme ->
                    ThemeOptionRow(
                        theme = theme,
                        isSelected = currentTheme == theme,
                        onClick = { viewModel.setTheme(theme) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Storage access",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                if (hasAllFilesAccess) "Full file access" else "Limited access",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (hasAllFilesAccess) {
                    "Device storage and mounted external media are available."
                } else {
                    "Document trees and remote servers remain available. Direct device storage requires Android special access."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRequestAllFilesAccess) {
                Text(if (hasAllFilesAccess) "Manage access" else "Grant full access")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Display section
            Text(
                "Display",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show hidden files", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = browseState.showHidden,
                    onCheckedChange = { viewModel.setShowHidden(it) },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use Trash for local files", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Turn off only if local deletes should be permanent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = useTrash,
                    onCheckedChange = viewModel::setUseTrash,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // About section
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text("Voyager", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Voyager - A Material Design 3 file browser with SFTP, FTP, SMB, and WebDAV support.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Licensed under GPLv3 | F-Droid compatible",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeOptionRow(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = getThemePreviewColors(theme)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(8.dp),
                ) else Modifier
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(colors.first),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(colors.second),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            theme.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isSelected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private data class ThemeCategory(
    val label: String,
    val themes: List<AppTheme>,
)

private val themeCategories = listOf(
    ThemeCategory(
        label = "Base",
        themes = listOf(
            AppTheme.SYSTEM,
            AppTheme.WHITE,
            AppTheme.DARK,
            AppTheme.BLACK,
            AppTheme.HIGH_CONTRAST,
            AppTheme.CUSTOM,
        ),
    ),
    ThemeCategory(
        label = "Color",
        themes = listOf(
            AppTheme.OCEAN,
            AppTheme.PURPLE,
            AppTheme.FOREST,
        ),
    ),
    ThemeCategory(
        label = "Catppuccin",
        themes = listOf(
            AppTheme.LATTE,
            AppTheme.FRAPPE,
            AppTheme.MACCHIATO,
            AppTheme.MOCHA,
        ),
    ),
    ThemeCategory(
        label = "Classics",
        themes = listOf(
            AppTheme.NORD,
            AppTheme.SOLARIZED_LIGHT,
            AppTheme.SOLARIZED_DARK,
            AppTheme.GRUVBOX_LIGHT,
            AppTheme.GRUVBOX_DARK,
        ),
    ),
    ThemeCategory(
        label = "Night",
        themes = listOf(
            AppTheme.ROSE_PINE,
            AppTheme.TOKYO_NIGHT,
        ),
    ),
)

private fun getThemePreviewColors(theme: AppTheme): Pair<Color, Color> = when (theme) {
    AppTheme.SYSTEM -> SystemColors.primary to SystemColors.background
    AppTheme.BLACK -> BlackColors.primary to BlackColors.background
    AppTheme.WHITE -> WhiteColors.primary to WhiteColors.background
    AppTheme.DARK -> DarkColors.primary to DarkColors.background
    AppTheme.OCEAN -> OceanColors.primary to OceanColors.background
    AppTheme.PURPLE -> PurpleColors.primary to PurpleColors.background
    AppTheme.FOREST -> ForestColors.primary to ForestColors.background
    AppTheme.MOCHA -> MochaColors.primary to MochaColors.background
    AppTheme.MACCHIATO -> MacchiatoColors.primary to MacchiatoColors.background
    AppTheme.FRAPPE -> FrappeColors.primary to FrappeColors.background
    AppTheme.LATTE -> LatteColors.primary to LatteColors.background
    AppTheme.NORD -> NordColors.primary to NordColors.background
    AppTheme.SOLARIZED_DARK -> SolarizedDarkColors.primary to SolarizedDarkColors.background
    AppTheme.SOLARIZED_LIGHT -> SolarizedLightColors.primary to SolarizedLightColors.background
    AppTheme.GRUVBOX_DARK -> GruvboxDarkColors.primary to GruvboxDarkColors.background
    AppTheme.GRUVBOX_LIGHT -> GruvboxLightColors.primary to GruvboxLightColors.background
    AppTheme.ROSE_PINE -> RosePineColors.primary to RosePineColors.background
    AppTheme.TOKYO_NIGHT -> TokyoNightColors.primary to TokyoNightColors.background
    AppTheme.HIGH_CONTRAST -> HighContrastColors.primary to HighContrastColors.background
    AppTheme.CUSTOM -> Color(0xFF6750A4) to Color(0xFF1C1B1F)
}
