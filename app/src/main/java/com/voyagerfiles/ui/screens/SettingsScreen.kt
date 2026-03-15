package com.voyagerfiles.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.voyagerfiles.ui.theme.AppTheme
import com.voyagerfiles.ui.theme.BlackColors
import com.voyagerfiles.ui.theme.DarkColors
import com.voyagerfiles.ui.theme.ForestColors
import com.voyagerfiles.ui.theme.FrappeColors
import com.voyagerfiles.ui.theme.LatteColors
import com.voyagerfiles.ui.theme.MacchiatoColors
import com.voyagerfiles.ui.theme.MochaColors
import com.voyagerfiles.ui.theme.OceanColors
import com.voyagerfiles.ui.theme.PurpleColors
import com.voyagerfiles.ui.theme.SystemColors
import com.voyagerfiles.ui.theme.WhiteColors
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit,
) {
    val currentTheme by viewModel.theme.collectAsState()
    val browseState by viewModel.browseState.collectAsState()

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
            // Theme section
            Text(
                "Theme",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppTheme.entries.forEach { theme ->
                    ThemeChip(
                        theme = theme,
                        isSelected = currentTheme == theme,
                        onClick = { viewModel.setTheme(theme) },
                    )
                }
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
                "Version 1.0.0",
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
private fun ThemeChip(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = getThemePreviewColors(theme)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp),
                ) else Modifier
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Color preview dots
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(colors.first),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(colors.second),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            theme.displayName,
            style = MaterialTheme.typography.labelMedium,
        )
        if (isSelected) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

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
    AppTheme.CUSTOM -> Color(0xFF6750A4) to Color(0xFF1C1B1F)
}
