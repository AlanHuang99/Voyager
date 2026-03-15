package com.voyagerfiles.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import com.voyagerfiles.data.model.FileItem
import java.io.File

object FileUtils {

    fun getStorageInfo(): StorageInfo {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val total = stat.totalBytes
            val available = stat.availableBytes
            val used = total - available
            StorageInfo(total, used, available)
        } catch (_: Exception) {
            StorageInfo(0, 0, 0)
        }
    }

    fun getCommonDirectories(): List<StorageDirectory> = listOf(
        StorageDirectory("Internal Storage", Environment.getExternalStorageDirectory().absolutePath),
        StorageDirectory("Downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath),
        StorageDirectory("Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath),
        StorageDirectory("Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath),
        StorageDirectory("Music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath),
        StorageDirectory("Movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath),
        StorageDirectory("DCIM", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath),
    )

    fun openFile(context: Context, file: FileItem) {
        val javaFile = File(file.path)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            javaFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    }

    fun shareFile(context: Context, file: FileItem) {
        val javaFile = File(file.path)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            javaFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }
}

data class StorageInfo(
    val total: Long,
    val used: Long,
    val available: Long,
) {
    val usedPercentage: Float get() = if (total > 0) used.toFloat() / total.toFloat() else 0f
}

data class StorageDirectory(
    val name: String,
    val path: String,
)
