package com.voyagerfiles.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import java.io.File

internal const val PDF_THUMBNAIL_TEST_TAG = "pdf-thumbnail"
internal const val FILE_ICON_TEST_TAG = "file-icon"

@Composable
fun FileThumbnailOrIcon(
    file: FileItem,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val icon = fileIcon(file)
    if (file.isPdf && (file.source == FileSource.LOCAL || file.source == FileSource.SAF)) {
        val context = LocalContext.current
        val sizePixels = with(LocalDensity.current) { iconSize.roundToPx().coerceAtLeast(1) }
        val thumbnail by produceState<android.graphics.Bitmap?>(
            initialValue = null,
            file.source,
            file.path,
            file.lastModified,
            sizePixels,
        ) {
            value = PdfThumbnailLoader.load(
                context = context,
                file = file,
                maxWidth = sizePixels,
                maxHeight = sizePixels,
            ).getOrNull()
        }
        Surface(
            modifier = modifier.size(iconSize),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = checkNotNull(thumbnail).asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(PDF_THUMBNAIL_TEST_TAG),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .testTag(FILE_ICON_TEST_TAG),
                    tint = fileIconTint(file),
                )
            }
        }
        return
    }

    if (file.usesLocalImageThumbnail) {
        val fallbackPainter = rememberVectorPainter(icon)
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
                placeholder = fallbackPainter,
                error = fallbackPainter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier
            .size(iconSize)
            .testTag(FILE_ICON_TEST_TAG),
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
