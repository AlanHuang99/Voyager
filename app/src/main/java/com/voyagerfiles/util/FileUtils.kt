package com.voyagerfiles.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.FileProvider
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import java.io.File

object FileUtils {

    fun getStorageInfo(path: String = Environment.getExternalStorageDirectory().path): StorageInfo {
        return try {
            val stat = StatFs(path)
            val total = stat.totalBytes
            val available = stat.availableBytes
            val used = total - available
            StorageInfo(total, used, available)
        } catch (_: Exception) {
            StorageInfo(0, 0, 0)
        }
    }

    fun getStorageDirectories(context: Context? = null): List<StorageDirectory> {
        if (context != null) {
            return getStorageVolumes(context)
                .filter(StorageVolumeInfo::isAvailable)
                .mapNotNull { volume -> volume.path?.let { StorageDirectory(volume.description, it) } }
        }
        val primaryPath = Environment.getExternalStorageDirectory().absolutePath
        val removablePaths = mutableListOf<String>()

        context?.getExternalFilesDirs(null)?.forEach { appDirectory ->
            appDirectory?.toStorageRootPath()?.let { removablePaths += it }
        }
        removablePaths += discoverStorageRoots(primaryPath)

        return buildStorageDirectories(primaryPath, removablePaths)
    }

    fun getStorageVolumes(context: Context): List<StorageVolumeInfo> {
        val primaryPath = Environment.getExternalStorageDirectory().absolutePath
        val storageManager = context.getSystemService(StorageManager::class.java)
        val externalRoots = context.getExternalFilesDirs(null)
            .mapNotNull { it?.toStorageRootPath() }
        val fallbackPaths = (listOf(primaryPath) + externalRoots + discoverStorageRoots(primaryPath))
            .map { it.normalizedPath() }
            .filterNot { it.substringAfterLast("/") in ignoredStorageRootNames }

        val rootsByVolume = context.getExternalFilesDirs(null)
            .mapNotNull { appDirectory ->
                appDirectory ?: return@mapNotNull null
                val volume = storageManager.getStorageVolume(appDirectory) ?: return@mapNotNull null
                volume.identity(context) to appDirectory.toStorageRootPath()
            }
            .filter { it.second != null }
            .associate { it.first to checkNotNull(it.second) }

        val platformVolumes = storageManager.storageVolumes.map { volume ->
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath
            } else {
                rootsByVolume[volume.identity(context)] ?: if (volume.isPrimary) primaryPath else null
            }
            StorageVolumeInfo(
                description = volume.getDescription(context),
                path = path,
                isPrimary = volume.isPrimary,
                isRemovable = volume.isRemovable,
                state = volume.state,
            )
        }

        return mergeStorageVolumes(platformVolumes, fallbackPaths, primaryPath)
    }

    fun buildStorageDirectories(
        primaryExternalPath: String,
        removableVolumePaths: List<String>,
    ): List<StorageDirectory> {
        val primaryPath = primaryExternalPath.normalizedPath()
        val externalPaths = removableVolumePaths
            .map { it.normalizedPath() }
            .filter { it.isNotBlank() }
            .filterNot { it == primaryPath }
            .filterNot { it.substringAfterLast("/") in ignoredStorageRootNames }
            .distinct()

        return buildList {
            add(StorageDirectory("Internal Storage", primaryPath))
            externalPaths.forEachIndexed { index, path ->
                add(
                    StorageDirectory(
                        name = if (index == 0) "External Storage" else "External Storage ${index + 1}",
                        path = path,
                    )
                )
            }
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
        val uri: Uri = if (file.source == FileSource.SAF) {
            Uri.parse(file.path)
        } else {
            val javaFile = File(file.path)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                javaFile,
            )
        }
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

    private fun discoverStorageRoots(primaryPath: String): List<String> =
        runCatching {
            File("/storage").listFiles()
                ?.filter { it.isDirectory && it.canRead() }
                ?.map { it.absolutePath }
                ?.filterNot { it.normalizedPath() == primaryPath.normalizedPath() }
                ?: emptyList()
        }.getOrElse { emptyList() }

    private fun File.toStorageRootPath(): String? {
        val marker = "${File.separator}Android${File.separator}data"
        val markerIndex = absolutePath.indexOf(marker)
        return if (markerIndex > 0) absolutePath.substring(0, markerIndex) else null
    }

    private fun StorageVolume.identity(context: Context): String =
        listOf(isPrimary.toString(), uuid.orEmpty(), getDescription(context)).joinToString(":")

    private fun String.normalizedPath(): String =
        trim().trimEnd('/').ifBlank { "/" }

    private val ignoredStorageRootNames = setOf("emulated", "self")
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
