package com.voyagerfiles.data.index

import androidx.annotation.StringRes
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.util.StorageVolumeInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

enum class StorageCategory(val filter: FileTypeFilter) {
    APPS(FileTypeFilter.APPS),
    VIDEOS(FileTypeFilter.VIDEOS),
    AUDIO(FileTypeFilter.AUDIO),
    IMAGES(FileTypeFilter.IMAGES),
    DOCUMENTS(FileTypeFilter.DOCUMENTS),
}

enum class CategoryScanStatus(@StringRes val labelRes: Int) {
    PENDING(R.string.category_pending),
    SCANNING(R.string.category_scanning),
    COMPLETE(R.string.category_complete),
    PARTIAL(R.string.category_partial),
    UNAVAILABLE(R.string.storage_unavailable),
    CANCELLED(R.string.category_cancelled),
    LIMITED(R.string.category_limited),
    DENIED(R.string.category_permission_required),
    FAILED(R.string.category_failed),
}

data class CategoryVolumeCoverage(
    val volume: StorageVolumeInfo,
    val status: CategoryScanStatus = CategoryScanStatus.PENDING,
    val filesExamined: Int = 0,
    val inaccessibleEntries: Int = 0,
    val skippedEntries: Int = 0,
)

data class CategoryIndexState(
    val status: CategoryScanStatus = CategoryScanStatus.PENDING,
    val files: List<FileItem> = emptyList(),
    val coverage: List<CategoryVolumeCoverage> = emptyList(),
    val staleEntries: Int = 0,
)

/** Scans ordinary shared-storage paths only. Links are never traversed and directory handles are closed on cancellation. */
class StorageCategoryIndex(
    private val maxResults: Int = 50_000,
    private val maxDepth: Int = 128,
    private val canRead: (Path) -> Boolean = Files::isReadable,
) {
    init {
        require(maxResults > 0 && maxDepth > 0)
    }

    fun scan(
        category: StorageCategory,
        volumes: List<StorageVolumeInfo>,
        showHidden: Boolean,
        hasPermission: () -> Boolean,
    ): Flow<CategoryIndexState> = flow {
        val matches = mutableListOf<FileItem>()
        val coverage = volumes.map(::CategoryVolumeCoverage).toMutableList()
        val visitedRoots = mutableListOf<Path>()
        var currentVolume = 0
        var visitedSinceUpdate = 0
        var lastUpdate = System.nanoTime()

        suspend fun checkAccess() {
            currentCoroutineContext().ensureActive()
            if (!hasPermission()) throw StoragePermissionRevoked()
        }

        suspend fun publish(status: CategoryScanStatus = CategoryScanStatus.SCANNING) {
            checkAccess()
            emit(CategoryIndexState(status, matches.toList(), coverage.toList()))
            visitedSinceUpdate = 0
            lastUpdate = System.nanoTime()
        }

        fun inaccessible() {
            coverage[currentVolume] = coverage[currentVolume].let {
                it.copy(inaccessibleEntries = it.inaccessibleEntries + 1)
            }
        }

        fun skipped() {
            coverage[currentVolume] = coverage[currentVolume].let { it.copy(skippedEntries = it.skippedEntries + 1) }
        }

        suspend fun visit(path: Path, depth: Int) {
            checkAccess()
            visitedSinceUpdate++
            if (visitedSinceUpdate >= 256 || System.nanoTime() - lastUpdate >= 250_000_000L) publish()
            try {
                if (depth > 0 && path in visitedRoots) {
                    skipped()
                    return
                }
                if (depth > 0 && !showHidden && path.fileName.toString().startsWith('.')) {
                    skipped()
                    return
                }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
                    skipped()
                    return
                }
                if (!canRead(path)) {
                    inaccessible()
                    return
                }
                if (attributes.isDirectory) {
                    if (depth >= maxDepth) {
                        skipped()
                        return
                    }
                    Files.newDirectoryStream(path).use { children ->
                        for (child in children) visit(child, depth + 1)
                    }
                } else {
                    coverage[currentVolume] = coverage[currentVolume].let { it.copy(filesExamined = it.filesExamined + 1) }
                    val file = FileItem(
                        name = path.fileName.toString(),
                        path = path.toString(),
                        isDirectory = false,
                        size = attributes.size(),
                        lastModified = Date(attributes.lastModifiedTime().toMillis()),
                        isHidden = path.any { it.toString().startsWith('.') },
                    )
                    if (category.filter.matches(file)) {
                        if (matches.size == maxResults) throw CategoryResultLimit()
                        matches += file
                    }
                }
            } catch (_: IOException) {
                inaccessible()
            } catch (_: SecurityException) {
                inaccessible()
            } catch (_: java.nio.file.DirectoryIteratorException) {
                inaccessible()
            }
        }

        try {
            checkAccess()
            publish()
            for ((index, volume) in volumes.withIndex()) {
                currentVolume = index
                checkAccess()
                if (!volume.isAvailable) {
                    coverage[index] = coverage[index].copy(status = CategoryScanStatus.UNAVAILABLE)
                    publish()
                    continue
                }
                coverage[index] = coverage[index].copy(status = CategoryScanStatus.SCANNING)
                try {
                    val root = java.io.File(checkNotNull(volume.path)).toPath().toRealPath()
                    if (visitedRoots.any { root.startsWith(it) }) {
                        skipped()
                    } else {
                        visitedRoots.add(root)
                        visit(root, 0)
                    }
                } catch (_: IOException) {
                    inaccessible()
                } catch (_: SecurityException) {
                    inaccessible()
                }
                coverage[index] = coverage[index].let {
                    it.copy(status = if (it.inaccessibleEntries > 0 || it.skippedEntries > 0) CategoryScanStatus.PARTIAL else CategoryScanStatus.COMPLETE)
                }
                publish()
            }
            matches.sortWith(compareBy<FileItem> { it.name.lowercase() }.thenBy { it.path })
            publish(when {
                coverage.isEmpty() -> CategoryScanStatus.UNAVAILABLE
                coverage.any { it.status != CategoryScanStatus.COMPLETE } -> CategoryScanStatus.PARTIAL
                else -> CategoryScanStatus.COMPLETE
            })
        } catch (_: StoragePermissionRevoked) {
            emit(CategoryIndexState(status = CategoryScanStatus.DENIED))
        } catch (_: CategoryResultLimit) {
            coverage[currentVolume] = coverage[currentVolume].copy(status = CategoryScanStatus.LIMITED)
            currentCoroutineContext().ensureActive()
            emit(if (hasPermission()) {
                CategoryIndexState(CategoryScanStatus.LIMITED, matches.toList(), coverage.toList())
            } else {
                CategoryIndexState(status = CategoryScanStatus.DENIED)
            })
        }
    }.flowOn(Dispatchers.IO)

    /** A result is a snapshot; reject removed, replaced, unreadable or out-of-scope files before opening it. */
    fun isCurrent(file: FileItem, volumes: List<StorageVolumeInfo>, hasPermission: () -> Boolean): Boolean {
        if (!hasPermission()) return false
        return try {
            val path = java.io.File(file.path).toPath()
            val resolved = path.toRealPath()
            if (resolved != path || !canRead(path)) return false
            val inScope = volumes.any { volume ->
                volume.isAvailable && runCatching {
                    resolved.startsWith(java.io.File(checkNotNull(volume.path)).toPath().toRealPath())
                }.getOrDefault(false)
            }
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            inScope && attributes.isRegularFile && attributes.size() == file.size &&
                attributes.lastModifiedTime().toMillis() == file.lastModified.time && hasPermission()
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private class StoragePermissionRevoked : RuntimeException()
    private class CategoryResultLimit : RuntimeException()
}
