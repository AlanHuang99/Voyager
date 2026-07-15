package com.voyagerfiles.util

data class StorageVolumeInfo(
    val description: String,
    val path: String?,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val state: String,
) {
    val isAvailable: Boolean
        get() = path != null && (state == STATE_MOUNTED || state == STATE_MOUNTED_READ_ONLY)

    val isReadOnly: Boolean
        get() = state == STATE_MOUNTED_READ_ONLY

    val statusLabel: String?
        get() = when {
            !isAvailable -> "Unavailable"
            isReadOnly -> "Read only"
            else -> null
        }

    companion object {
        const val STATE_MOUNTED = "mounted"
        const val STATE_MOUNTED_READ_ONLY = "mounted_ro"
    }
}

fun mergeStorageVolumes(
    platformVolumes: List<StorageVolumeInfo>,
    fallbackPaths: List<String>,
    primaryPath: String,
): List<StorageVolumeInfo> {
    val normalizedPrimary = primaryPath.normalizedStoragePath()
    val byPath = linkedMapOf<String, StorageVolumeInfo>()
    val withoutPath = mutableListOf<StorageVolumeInfo>()

    platformVolumes.forEach { volume ->
        val path = volume.path?.normalizedStoragePath()
        if (path == null) {
            withoutPath += volume
        } else {
            byPath.putIfAbsent(path, volume.copy(path = path))
        }
    }

    var externalIndex = byPath.values.count { !it.isPrimary }
    fallbackPaths.map(String::normalizedStoragePath).distinct().forEach { path ->
        if (path.isBlank() || path in byPath) return@forEach
        val isPrimary = path == normalizedPrimary
        if (!isPrimary) externalIndex++
        byPath[path] = StorageVolumeInfo(
            description = if (isPrimary) "Internal storage" else if (externalIndex == 1) "External storage" else "External storage $externalIndex",
            path = path,
            isPrimary = isPrimary,
            isRemovable = !isPrimary,
            state = StorageVolumeInfo.STATE_MOUNTED,
        )
    }

    return (byPath.values + withoutPath).sortedWith(
        compareByDescending<StorageVolumeInfo> { it.isPrimary }.thenBy { it.description.lowercase() },
    )
}

private fun String.normalizedStoragePath(): String = trim().trimEnd('/').ifBlank { "/" }
