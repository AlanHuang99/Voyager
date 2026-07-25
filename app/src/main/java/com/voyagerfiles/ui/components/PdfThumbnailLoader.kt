package com.voyagerfiles.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

object PdfThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(maximumCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(
        context: Context,
        file: FileItem,
        maxWidth: Int,
        maxHeight: Int,
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isPdf) { "The selected file is not a PDF" }
            require(file.source == FileSource.LOCAL || file.source == FileSource.SAF) {
                "PDF thumbnails are available only for local files and document trees"
            }
            require(maxWidth > 0 && maxHeight > 0) {
                "PDF thumbnail dimensions must be positive"
            }

            val cacheKey = listOf(
                file.source.name,
                file.path,
                file.lastModified.time,
                maxWidth,
                maxHeight,
            ).joinToString("|")
            cache.get(cacheKey)?.let { return@runCatching it }

            openDescriptor(context, file).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "The PDF does not contain any pages" }
                    renderer.openPage(0).use { page ->
                        val scale = min(
                            maxWidth.toFloat() / page.width,
                            maxHeight.toFloat() / page.height,
                        )
                        val width = max(1, (page.width * scale).toInt())
                        val height = max(1, (page.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            cache.put(cacheKey, bitmap)
                            bitmap
                        } catch (error: Throwable) {
                            bitmap.recycle()
                            throw error
                        }
                    }
                }
            }
        }
    }

    fun clear() {
        cache.evictAll()
    }

    private fun openDescriptor(context: Context, file: FileItem): ParcelFileDescriptor =
        when (file.source) {
            FileSource.LOCAL -> ParcelFileDescriptor.open(
                File(file.path),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )

            FileSource.SAF -> requireNotNull(
                context.contentResolver.openFileDescriptor(Uri.parse(file.path), "r"),
            ) {
                "The PDF could not be opened"
            }

            else -> error("Unsupported PDF thumbnail source: ${file.source}")
        }

    private fun maximumCacheBytes(): Int {
        val runtimeLimit = (Runtime.getRuntime().maxMemory() / CACHE_MEMORY_DIVISOR)
            .coerceAtLeast(1L)
        return min(MAX_CACHE_BYTES.toLong(), runtimeLimit)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    private const val CACHE_MEMORY_DIVISOR = 8
}
