package com.voyagerfiles.data.duplicates

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DuplicateFile(val path: String, val size: Long, val modified: FileTime, val fileKey: String?)
data class DuplicateGroup(val hash: String, val files: List<DuplicateFile>) {
    val id: String get() = "${files.first().size}:$hash"
}
data class DuplicateScan(
    val groups: List<DuplicateGroup> = emptyList(),
    val filesExamined: Int = 0,
    val unreadable: Int = 0,
    val missing: Int = 0,
    val changed: Int = 0,
    val linksSkipped: Int = 0,
    val excludedDirectories: Int = 0,
    val limited: Boolean = false,
)
data class DuplicateScanProgress(val examined: Int, val hashingPath: String? = null)

/** Explicit local scans only; no links are followed and no content leaves the device. */
class DuplicateScanner(private val maximumFiles: Int = 100_000) {
    init { require(maximumFiles > 0) }

    suspend fun scan(root: File, onProgress: (DuplicateScanProgress) -> Unit = {}): DuplicateScan = withContext(Dispatchers.IO) {
        require(root.isDirectory && root.canRead()) { "The selected folder is no longer readable." }
        val canonicalRoot = root.canonicalFile.toPath()
        require(canonicalRoot.none { it.toString() == ".VoyagerTrash" }) { "Restore files from Trash before scanning them." }
        val pending = ArrayDeque<java.nio.file.Path>().apply { add(canonicalRoot) }
        val candidates = mutableListOf<DuplicateFile>()
        val seenKeys = mutableSetOf<String>()
        var result = DuplicateScan()
        var entriesExamined = 0L
        while (pending.isNotEmpty() && !result.limited) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeLast()
            try {
                if (Files.isSymbolicLink(directory) || directory.toRealPath() != directory.toAbsolutePath().normalize()) {
                    result = result.copy(linksSkipped = result.linksSkipped + 1)
                    continue
                }
                Files.newDirectoryStream(directory).use { entries ->
                    for (path in entries) {
                        currentCoroutineContext().ensureActive()
                        if (++entriesExamined > maximumFiles.toLong() * 2) {
                            result = result.copy(limited = true)
                            break
                        }
                        try {
                            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                            when {
                                attrs.isSymbolicLink -> result = result.copy(linksSkipped = result.linksSkipped + 1)
                                attrs.isDirectory -> {
                                    if (path.fileName.toString() == ".VoyagerTrash") {
                                        result = result.copy(excludedDirectories = result.excludedDirectories + 1)
                                    } else pending.add(path)
                                }
                                attrs.isRegularFile -> {
                                    if (candidates.size >= maximumFiles) {
                                        result = result.copy(limited = true)
                                        break
                                    }
                                    val key = attrs.fileKey()?.toString()
                                    if (key != null && !seenKeys.add(key)) {
                                        result = result.copy(linksSkipped = result.linksSkipped + 1)
                                    } else {
                                        candidates += DuplicateFile(path.toString(), attrs.size(), attrs.lastModifiedTime(), key)
                                    }
                                }
                            }
                        } catch (_: NoSuchFileException) {
                            result = result.copy(missing = result.missing + 1)
                        } catch (_: IOException) {
                            result = result.copy(unreadable = result.unreadable + 1)
                        } catch (_: SecurityException) {
                            result = result.copy(unreadable = result.unreadable + 1)
                        }
                    }
                }
            } catch (_: NoSuchFileException) {
                result = result.copy(missing = result.missing + 1)
            } catch (_: IOException) {
                result = result.copy(unreadable = result.unreadable + 1)
            } catch (_: SecurityException) {
                result = result.copy(unreadable = result.unreadable + 1)
            }
            onProgress(DuplicateScanProgress(candidates.size))
        }
        val groups = mutableListOf<DuplicateGroup>()
        for (sameSize in candidates.groupBy { it.size }.values.filter { it.size > 1 }) {
            val hashes = mutableMapOf<String, MutableList<DuplicateFile>>()
            for (file in sameSize) {
                currentCoroutineContext().ensureActive()
                onProgress(DuplicateScanProgress(candidates.size, file.path))
                try {
                    hashes.getOrPut(hashUnchanged(file)) { mutableListOf() }.add(file)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: NoSuchFileException) {
                    result = result.copy(missing = result.missing + 1)
                } catch (_: ChangedFileException) {
                    result = result.copy(changed = result.changed + 1)
                } catch (_: IOException) {
                    result = result.copy(unreadable = result.unreadable + 1)
                } catch (_: SecurityException) {
                    result = result.copy(unreadable = result.unreadable + 1)
                }
            }
            hashes.filterValues { it.size > 1 }.forEach { (hash, files) ->
                groups += DuplicateGroup(hash, files.sortedBy { it.path })
            }
        }
        result.copy(groups = groups.sortedByDescending { it.files.first().size }, filesExamined = candidates.size)
    }

    /** Revalidate both the selected file and an unselected copy immediately before each removal. */
    suspend fun verifyRemoval(group: DuplicateGroup, selected: Set<String>, candidate: DuplicateFile) = withContext(Dispatchers.IO) {
        require(candidate in group.files && candidate.path in selected) { "Select an item from the duplicate results." }
        val keepers = group.files.filter { it.path !in selected }
        require(keepers.isNotEmpty()) { "Keep at least one copy of each file." }
        var keeperFound = false
        for (keeper in keepers) {
            try {
                if (hashUnchanged(keeper) == group.hash) {
                    keeperFound = true
                    break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Another unchanged keeper can still make this removal valid.
            } catch (_: SecurityException) {
                // An inaccessible keeper does not count as a preserved copy.
            }
        }
        check(keeperFound) { "The copy to keep changed or is unavailable. Scan again before removing files." }
        check(hashUnchanged(candidate) == group.hash) { "This file changed. Scan again before removing it." }
    }

    private suspend fun hashUnchanged(file: DuplicateFile): String {
        val path = File(file.path).toPath()
        fun checkIdentity() {
            if (File(file.path).canonicalPath != path.toAbsolutePath().normalize().toString()) throw ChangedFileException()
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() != file.size ||
                attrs.lastModifiedTime() != file.modified || attrs.fileKey()?.toString() != file.fileKey) {
                throw ChangedFileException()
            }
        }
        checkIdentity()
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                if (bytes > file.size) throw ChangedFileException()
                digest.update(buffer, 0, count)
            }
            if (bytes != file.size) throw ChangedFileException()
        }
        checkIdentity()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class ChangedFileException : IOException("The file changed during the scan. Scan again before removing it.")
}
