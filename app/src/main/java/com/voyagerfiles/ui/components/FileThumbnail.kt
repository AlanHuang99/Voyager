package com.voyagerfiles.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.voyagerfiles.data.model.FileItem
import java.io.File

@Composable
fun FileThumbnailOrIcon(
    file: FileItem,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val icon = fileIcon(file)
    if (file.usesLocalImageThumbnail) {
        Surface(
            modifier = modifier.size(iconSize),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(File(file.path))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = rememberAsyncImagePainter(icon),
                error = rememberAsyncImagePainter(icon),
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(iconSize),
        tint = fileIconTint(file),
    )
}

@Composable
private fun fileIconTint(file: FileItem) = when {
    file.isDirectory -> MaterialTheme.colorScheme.primary
    file.isImage -> MaterialTheme.colorScheme.tertiary
    file.isVideo -> MaterialTheme.colorScheme.error
    file.isAudio -> MaterialTheme.colorScheme.secondary
    file.isApk -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun fileIcon(file: FileItem): ImageVector = when {
    file.isDirectory -> Icons.Filled.Folder
    file.isImage -> Icons.Filled.Image
    file.isVideo -> Icons.Filled.VideoFile
    file.isAudio -> Icons.Filled.AudioFile
    file.isText -> Icons.Filled.Description
    file.isArchive -> Icons.Filled.Archive
    file.isApk -> Icons.Filled.Android
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
