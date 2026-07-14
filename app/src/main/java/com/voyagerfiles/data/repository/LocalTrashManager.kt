package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.UUID

class LocalTrashManager(
    volumeRoots: List<File>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val roots = volumeRoots
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .distinctBy { it.path }
        .sortedByDescending { it.path.length }

    suspend fun moveToTrash(path: String): Result<TrashEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(path).canonicalFile
            if (!source.exists()) throw IllegalArgumentException("Item no longer exists: $path")
            val root = rootFor(source) ?: throw IllegalArgumentException("Trash is not available for this location")
            val trashRoot = trashRoot(root)
            if (isAtOrInside(source, trashRoot)) throw IllegalArgumentException("Items already in Trash cannot be moved to Trash")
            if (!trashRoot.exists() && !trashRoot.mkdirs()) throw IOException("Could not create Trash on ${root.path}")

            val id = idGenerator()
            val deletedAt = clock()
            val pendingDirectory = File(trashRoot, ".pending-$id")
            val finalDirectory = File(trashRoot, id)
            if (pendingDirectory.exists() || finalDirectory.exists()) throw IOException("Could not allocate a unique Trash entry")
            if (!pendingDirectory.mkdir()) throw IOException("Could not prepare Trash entry")

            val payload = File(pendingDirectory, PAYLOAD_NAME)
            val metadata = File(pendingDirectory, METADATA_NAME)
            try {
                writeMetadata(
                    metadata = metadata,
                    id = id,
                    originalPath = source.path,
                    displayName = source.name,
                    isDirectory = source.isDirectory,
                    deletedAt = deletedAt,
                )
                movePath(source, payload)
                if (!pendingDirectory.renameTo(finalDirectory)) {
                    runCatching { movePath(payload, source) }
                        .onFailure { restoreError -> throw IOException("Could not finalize Trash entry or restore the original", restoreError) }
                    throw IOException("Could not finalize Trash entry")
                }
            } catch (error: Throwable) {
                if (source.exists()) pendingDirectory.deleteRecursively()
                throw error
            }

            readEntry(finalDirectory, root) ?: throw IOException("Trash metadata could not be verified")
        }
    }

    suspend fun listEntries(): List<TrashEntry> = withContext(Dispatchers.IO) {
        roots.flatMap { root ->
            val trashRoot = trashRoot(root)
            recoverPendingEntries(trashRoot)
            trashRoot.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && !it.name.startsWith(PENDING_PREFIX) }
                ?.mapNotNull { readEntry(it, root) }
                ?.toList()
                .orEmpty()
        }.sortedByDescending(TrashEntry::deletedAt)
    }

    suspend fun restore(entry: TrashEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = rootForEntry(entry)
            val verified = readEntry(entry.entryDirectory, root)
                ?: throw IllegalArgumentException("Trash entry is missing or damaged")
            if (verified.id != entry.id) throw IllegalArgumentException("Trash entry does not match")
            val target = File(verified.originalPath)
            if (target.exists()) throw IllegalStateException("${verified.displayName} already exists in the original folder")
            val parent = target.parentFile ?: throw IllegalArgumentException("Original folder is unavailable")
            if (parent.exists() && !parent.isDirectory) throw IllegalStateException("Original folder is blocked by a file")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not recreate the original folder")

            movePath(verified.payload, target)
            verified.entryDirectory.deleteRecursively()
            Unit
        }
    }

    suspend fun deletePermanently(entry: TrashEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = rootForEntry(entry)
            val verified = readEntry(entry.entryDirectory, root)
                ?: throw IllegalArgumentException("Trash entry is missing or damaged")
            if (verified.id != entry.id) throw IllegalArgumentException("Trash entry does not match")
            if (!verified.payload.deleteRecursively()) throw IOException("Could not permanently delete ${verified.displayName}")
            if (!verified.entryDirectory.deleteRecursively()) throw IOException("Could not remove Trash metadata")
        }
    }

    suspend fun empty(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var removed = 0
            roots.forEach { root ->
                trashRoot(root).listFiles().orEmpty().forEach { entry ->
                    if (!entry.deleteRecursively()) throw IOException("Could not remove ${entry.name} from Trash")
                    removed++
                }
            }
            removed
        }
    }

    private fun rootFor(source: File): File? =
        roots.firstOrNull { root -> source != root && isAtOrInside(source, root) }

    private fun rootForEntry(entry: TrashEntry): File {
        val entryDirectory = entry.entryDirectory.canonicalFile
        return roots.firstOrNull { root -> isAtOrInside(entryDirectory, trashRoot(root)) }
            ?: throw IllegalArgumentException("Trash entry is outside configured storage")
    }

    private fun readEntry(entryDirectory: File, root: File): TrashEntry? = runCatching {
        val canonicalDirectory = entryDirectory.canonicalFile
        if (!canonicalDirectory.isDirectory || !isAtOrInside(canonicalDirectory, trashRoot(root))) return@runCatching null
        val metadata = File(canonicalDirectory, METADATA_NAME)
        val payload = File(canonicalDirectory, PAYLOAD_NAME)
        if (!metadata.isFile || !payload.exists()) return@runCatching null

        val properties = Properties().apply {
            FileInputStream(metadata).use { input -> load(input) }
        }
        val originalPath = properties.getProperty(KEY_ORIGINAL_PATH)?.takeIf(String::isNotBlank) ?: return@runCatching null
        val original = File(originalPath).canonicalFile
        if (!isAtOrInside(original, root) || isAtOrInside(original, trashRoot(root))) return@runCatching null
        val id = properties.getProperty(KEY_ID)?.takeIf(String::isNotBlank) ?: return@runCatching null
        if (id != canonicalDirectory.name) return@runCatching null
        val displayName = properties.getProperty(KEY_DISPLAY_NAME)?.takeIf(String::isNotBlank) ?: return@runCatching null
        val deletedAt = properties.getProperty(KEY_DELETED_AT)?.toLongOrNull() ?: return@runCatching null
        val isDirectory = properties.getProperty(KEY_IS_DIRECTORY)?.toBooleanStrictOrNull() ?: return@runCatching null
        if (payload.isDirectory != isDirectory) return@runCatching null

        TrashEntry(
            id = id,
            originalPath = original.path,
            displayName = displayName,
            isDirectory = isDirectory,
            deletedAt = deletedAt,
            entryDirectory = canonicalDirectory,
            payload = payload.canonicalFile,
        )
    }.getOrNull()

    private fun recoverPendingEntries(trashRoot: File) {
        trashRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(PENDING_PREFIX) }
            ?.forEach { pending ->
                val id = pending.name.removePrefix(PENDING_PREFIX)
                val finalDirectory = File(trashRoot, id)
                if (!finalDirectory.exists() && File(pending, METADATA_NAME).isFile && File(pending, PAYLOAD_NAME).exists()) {
                    pending.renameTo(finalDirectory)
                }
            }
    }

    private fun writeMetadata(
        metadata: File,
        id: String,
        originalPath: String,
        displayName: String,
        isDirectory: Boolean,
        deletedAt: Long,
    ) {
        val properties = Properties().apply {
            setProperty(KEY_ID, id)
            setProperty(KEY_ORIGINAL_PATH, originalPath)
            setProperty(KEY_DISPLAY_NAME, displayName)
            setProperty(KEY_IS_DIRECTORY, isDirectory.toString())
            setProperty(KEY_DELETED_AT, deletedAt.toString())
        }
        FileOutputStream(metadata).use { properties.store(it, null) }
    }

    private fun movePath(source: File, target: File) {
        if (source.renameTo(target)) return
        copyPath(source, target)
        val deleted = if (source.isDirectory) source.deleteRecursively() else source.delete()
        if (!deleted) {
            target.deleteRecursively()
            throw IOException("Could not remove the original after copying")
        }
    }

    private fun copyPath(source: File, target: File) {
        try {
            if (source.isDirectory) {
                if (!source.copyRecursively(target, overwrite = false)) throw IOException("Could not copy ${source.name}")
            } else {
                source.copyTo(target, overwrite = false)
            }
        } catch (error: Throwable) {
            target.deleteRecursively()
            throw error
        }
    }

    private fun trashRoot(root: File): File = File(root, TRASH_DIRECTORY_NAME)

    private fun isAtOrInside(file: File, directory: File): Boolean =
        file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())

    companion object {
        private const val TRASH_DIRECTORY_NAME = ".VoyagerTrash"
        private const val PENDING_PREFIX = ".pending-"
        private const val METADATA_NAME = "metadata.properties"
        private const val PAYLOAD_NAME = "payload"
        private const val KEY_ID = "id"
        private const val KEY_ORIGINAL_PATH = "originalPath"
        private const val KEY_DISPLAY_NAME = "displayName"
        private const val KEY_IS_DIRECTORY = "isDirectory"
        private const val KEY_DELETED_AT = "deletedAt"
    }
}
