package com.voyagerfiles.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.isNetwork
import com.voyagerfiles.data.preview.RemoteThumbnailSource
import com.voyagerfiles.data.repository.FileProvider
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlin.math.min

fun interface RemoteImageThumbnailLoader {
    suspend fun load(file: FileItem, maxSize: Int): Bitmap?
}

internal class RemoteImageThumbnailSession(providerFactory: () -> FileProvider) : RemoteImageThumbnailLoader, Closeable {
    private val source = RemoteThumbnailSource(providerFactory = providerFactory)
    private val identity = Any()
    private var closed = false

    override suspend fun load(file: FileItem, maxSize: Int): Bitmap? {
        return try {
            require(file.source.isNetwork && !file.isDirectory && file.isImage) { "Remote previews require an image" }
            require(maxSize > 0) { "Thumbnail size must be positive" }
            val size = maxSize.coerceAtMost(MAX_DIMENSION)
            val key = Key(identity, file.path, file.size, file.lastModified.time, size)
            synchronized(cacheLock) {
                check(!closed) { "Thumbnail session is closed" }
                cache.get(key)
            }?.let { return it }
            val bitmap = source.load(file) { decode(it, size) }
            synchronized(cacheLock) {
                if (closed) {
                    bitmap.recycle()
                    return null
                }
                val existing = cache.get(key)
                if (existing != null) {
                    bitmap.recycle()
                    existing
                } else {
                    cache.put(key, bitmap)
                    while (cache.snapshot().size > MAX_CACHE_ENTRIES) cache.remove(cache.snapshot().keys.first())
                    bitmap
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        synchronized(cacheLock) {
            if (closed) return
            closed = true
            cache.snapshot().keys.filter { it.session === identity }.forEach(cache::remove)
        }
        source.close()
    }

    private fun decode(bytes: ByteArray, size: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType?.startsWith("image/") == true &&
            bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS
        ) { "Invalid or oversized preview image" }
        var sample = 1
        while (ceilDiv(bounds.outWidth, sample) > size * 2 || ceilDiv(bounds.outHeight, sample) > size * 2) sample *= 2
        require(ceilDiv(bounds.outWidth, sample).toLong() * ceilDiv(bounds.outHeight, sample) * 4 <= MAX_DECODED_BYTES) {
            "Image exceeds the preview decode budget"
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Invalid preview image" }
        try {
            require(bitmap.width > 0 && bitmap.height > 0 && bitmap.allocationByteCount <= MAX_DECODED_BYTES) {
                "Image exceeds the preview decode budget"
            }
            val scale = min(1f, min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height))
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            if (bounds.outMimeType in EXIF_MIME_TYPES) {
                val exif = ExifInterface(bytes.inputStream())
                if (exif.isFlipped || exif.rotationDegrees != 0) {
                    val matrix = Matrix().apply {
                        if (exif.isFlipped) postScale(-1f, 1f)
                        if (exif.rotationDegrees != 0) postRotate(exif.rotationDegrees.toFloat())
                    }
                    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (oriented !== bitmap) bitmap.recycle()
                    bitmap = oriented
                }
            }
            require(bitmap.width <= size && bitmap.height <= size && bitmap.allocationByteCount <= MAX_DECODED_BYTES) {
                "Image exceeds the preview output bounds"
            }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor

    private data class Key(val session: Any, val path: String, val bytes: Long, val modified: Long, val size: Int)

    private companion object {
        const val MAX_DIMENSION = 256
        const val MAX_SOURCE_DIMENSION = 32_768
        const val MAX_SOURCE_PIXELS = 100L * 1024 * 1024
        const val MAX_DECODED_BYTES = 2 * 1024 * 1024
        const val MAX_CACHE_ENTRIES = 256
        val EXIF_MIME_TYPES = setOf("image/jpeg", "image/webp", "image/heic", "image/heif")
        val cacheLock = Any()
        val cache = object : LruCache<Key, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
        }
    }
}
