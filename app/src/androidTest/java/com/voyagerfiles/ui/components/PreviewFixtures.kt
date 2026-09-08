package com.voyagerfiles.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.model.FileItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun File.previewItem() = FileItem(name, absolutePath, false, length(), Date(lastModified()))

internal fun previewVideo(directory: File, extension: String = "mp4"): File =
    directory.resolve("fixture.$extension").also { destination ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("previews/red.$extension").use { input ->
            destination.outputStream().use(input::copyTo)
        }
    }

internal fun previewImage(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
    val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    return try {
        ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

internal fun previewOffice(
    directory: File,
    extension: String = "docx",
    thumbnail: ByteArray? = previewImage(),
    target: String = "docProps/thumbnail.png",
    relationTarget: String = target,
    external: Boolean = false,
): File = directory.resolve("fixture-${System.nanoTime()}.$extension").also { destination ->
    ZipOutputStream(destination.outputStream()).use { zip ->
        fun entry(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        val (part, contentType, body) = when (extension) {
            "pptx" -> Triple("ppt/presentation.xml", "presentationml.presentation", "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>")
            "xlsx" -> Triple("xl/workbook.xml", "spreadsheetml.sheet", "<s:workbook xmlns:s=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><s:sheets/></s:workbook>")
            else -> Triple("word/document.xml", "wordprocessingml.document", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p/></w:body></w:document>")
        }
        entry("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/$part" ContentType="application/vnd.openxmlformats-officedocument.$contentType.main+xml"/></Types>""".toByteArray())
        entry(part, body.toByteArray())
        val previewRelation = if (thumbnail == null) "" else """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail" Target="$relationTarget" ${if (external) "TargetMode=\"External\"" else ""}/>"""
        entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="$part"/>$previewRelation</Relationships>""".toByteArray())
        thumbnail?.let { entry(target, it) }
    }
}
