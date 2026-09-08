package com.voyagerfiles.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

internal object ApkThumbnailLoader {
    private const val MAX_CACHE_ENTRIES = 48

    private data class CacheKey(
        val canonicalPath: String,
        val byteSize: Long,
        val lastModified: Long,
        val maxWidth: Int,
        val maxHeight: Int,
    )

    private val cache = object : LinkedHashMap<CacheKey, Bitmap>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Bitmap>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    fun load(
        context: Context,
        file: FileItem,
        maxWidth: Int,
        maxHeight: Int,
    ): Result<Bitmap> = runCatching {
        require(file.source == FileSource.LOCAL) { "APK icon loading requires a local file" }
        require(file.isApk && !file.isDirectory) { "File is not an APK archive" }
        require(maxWidth > 0 && maxHeight > 0) { "Thumbnail bounds must be positive" }

        val archive = File(file.path)
        require(archive.isFile && archive.canRead()) { "APK archive is not a readable regular file" }
        val key = CacheKey(
            canonicalPath = archive.canonicalPath,
            byteSize = archive.length(),
            lastModified = archive.lastModified(),
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )
        synchronized(cache) { cache[key] }?.let { return@runCatching it }

        val packageManager = context.packageManager
        val packageInfo = requireNotNull(packageManager.getPackageArchiveInfo(key.canonicalPath, 0)) {
            "Could not parse APK archive"
        }
        val applicationInfo = requireNotNull(packageInfo.applicationInfo) {
            "APK archive has no application metadata"
        }.apply {
            sourceDir = key.canonicalPath
            publicSourceDir = key.canonicalPath
        }
        val bitmap = applicationInfo.loadIcon(packageManager).toBoundedBitmap(maxWidth, maxHeight)
        synchronized(cache) { cache[key] = bitmap }
        bitmap
    }

    fun invalidate(path: String) {
        synchronized(cache) { cache.keys.removeAll { it.canonicalPath == path } }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    private fun Drawable.toBoundedBitmap(maxWidth: Int, maxHeight: Int): Bitmap {
        val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: maxWidth
        val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: maxHeight
        val scale = min(maxWidth.toFloat() / sourceWidth, maxHeight.toFloat() / sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, maxWidth)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, maxHeight)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            setBounds(0, 0, width, height)
            draw(Canvas(bitmap))
        }
    }
}
