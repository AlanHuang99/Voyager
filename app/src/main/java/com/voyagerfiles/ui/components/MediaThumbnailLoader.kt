package com.voyagerfiles.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Xml
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import kotlin.math.min

/** Local and document-tree previews. No network provider is opened by this loader. */
internal object MediaThumbnailLoader {
    internal const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    internal const val MAX_IMAGE_BYTES = 1024 * 1024
    private const val MAX_IMAGE_PIXELS = 16L * 1024 * 1024
    private const val MAX_DIMENSION = 512
    private val permits = Semaphore(2)
    private data class Key(val path: String, val version: String, val width: Int, val height: Int)
    private val cache = object : LruCache<Key, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }

    fun supports(file: FileItem): Boolean = !file.isDirectory &&
        file.source in setOf(FileSource.LOCAL, FileSource.SAF) && (file.isVideo || file.isOfficeDocument)

    suspend fun load(context: Context, file: FileItem, maxWidth: Int, maxHeight: Int): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            permits.withPermit {
                try {
                    require(supports(file)) { "Unsupported preview source or file type" }
                    if (file.source == FileSource.SAF) require(Uri.parse(file.path).scheme == "content") {
                        "Document previews require a content URI"
                    }
                    require(maxWidth > 0 && maxHeight > 0) { "Thumbnail bounds must be positive" }
                    val width = maxWidth.coerceAtMost(MAX_DIMENSION)
                    val height = maxHeight.coerceAtMost(MAX_DIMENSION)
                    val local = File(file.path).takeIf { file.source == FileSource.LOCAL }
                    if (local != null) require(local.isFile && local.canRead()) { "Preview file is unavailable" }
                    val version = "${file.source}:${file.name}:${local?.length() ?: file.size}:${local?.lastModified() ?: file.lastModified.time}"
                    val key = Key(file.path, version, width, height)
                    val bitmap = cache.get(key) ?: run {
                        val decoded = if (file.isVideo) video(context, file, width, height)
                        else office(context, file, width, height)
                        try {
                            currentCoroutineContext().ensureActive()
                            cache.put(key, decoded)
                            decoded
                        } catch (error: CancellationException) {
                            decoded.recycle()
                            throw error
                        }
                    }
                    Result.success(bitmap)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        }

    fun invalidate(path: String) {
        cache.snapshot().keys.filter { it.path == path }.forEach(cache::remove)
    }

    fun clear() = cache.evictAll()

    private fun video(context: Context, file: FileItem, width: Int, height: Int): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            if (file.source == FileSource.LOCAL) retriever.setDataSource(file.path)
            else retriever.setDataSource(context, Uri.parse(file.path))
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
            } else {
                // API 26 cannot decode directly at thumbnail size. Bound its full-frame allocation.
                val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
                val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0
                require(sourceWidth in 1..4096 && sourceHeight in 1..4096 && sourceWidth * sourceHeight <= 4L * 1024 * 1024) {
                    "Video exceeds the legacy decoder bounds"
                }
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            return requireNotNull(frame) { "No video frame available" }.bounded(width, height)
        } finally {
            retriever.release()
        }
    }

    private suspend fun office(context: Context, file: FileItem, width: Int, height: Int): Bitmap {
        if (file.source == FileSource.LOCAL) return embeddedImage(File(file.path), file.extension, width, height)
        val temporary = File.createTempFile("office-preview-", ".zip", context.cacheDir)
        try {
            require(file.size <= MAX_ARCHIVE_BYTES) { "Office archive is too large" }
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(file.path))).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARCHIVE_BYTES) { "Office archive is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return embeddedImage(temporary, file.extension, width, height)
        } finally {
            temporary.delete()
        }
    }

    private fun embeddedImage(archive: File, extension: String, width: Int, height: Int): Bitmap {
        require(archive.length() in 1..MAX_ARCHIVE_BYTES) { "Office archive is too large or empty" }
        return ZipFile(archive).use { zip ->
            require(zip.size() <= 4096) { "Office archive has too many entries" }
            val documentPart = when (extension.lowercase()) {
                "docx" -> "word/document.xml"
                "pptx" -> "ppt/presentation.xml"
                "xlsx" -> "xl/workbook.xml"
                else -> error("Unsupported Office format")
            }
            require(zip.getEntry("[Content_Types].xml") != null && zip.getEntry(documentPart) != null) {
                "Not an Office document package"
            }
            val relations = zip.readEntry("_rels/.rels", 64 * 1024)
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(relations.inputStream(), "UTF-8")
            }
            var target: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                require(parser.eventType != XmlPullParser.DOCDECL) { "Document type declarations are unsupported" }
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship" &&
                    parser.getAttributeValue(null, "Type") == "http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail"
                ) {
                    require(parser.getAttributeValue(null, "TargetMode") != "External") { "External thumbnails are unsupported" }
                    target = parser.getAttributeValue(null, "Target")
                }
                parser.nextToken()
            }
            val path = requireNotNull(target) { "No embedded thumbnail" }.removePrefix("/")
            require(path.split('/').none { it == ".." } && ':' !in path && '\\' !in path) { "Invalid thumbnail path" }
            require(path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg")) { "Unsupported thumbnail format" }
            val bytes = zip.readEntry(path, MAX_IMAGE_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType in setOf("image/png", "image/jpeg") &&
                bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS
            ) { "Invalid or oversized thumbnail image" }
            var sample = 1
            while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Invalid thumbnail image" }
                .bounded(width, height)
        }
    }

    private fun ZipFile.readEntry(path: String, limit: Int): ByteArray {
        val entry = requireNotNull(getEntry(path)) { "Missing package part" }
        require(!entry.isDirectory && entry.size in 0..limit.toLong()) { "Package part exceeds preview limits" }
        return getInputStream(entry).use { it.readBounded(limit) }
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit) { "Package part exceeds preview limits" }
            output.write(buffer, 0, count)
        }
    }

    private fun Bitmap.bounded(width: Int, height: Int): Bitmap {
        val scale = min(1f, min(width.toFloat() / this.width, height.toFloat() / this.height))
        if (scale == 1f) return this
        return try {
            Bitmap.createScaledBitmap(this, (this.width * scale).toInt().coerceAtLeast(1), (this.height * scale).toInt().coerceAtLeast(1), true)
        } finally {
            recycle()
        }
    }
}
