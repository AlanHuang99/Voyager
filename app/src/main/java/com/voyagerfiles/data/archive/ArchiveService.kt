package com.voyagerfiles.data.archive

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.repository.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream

object ArchiveService {
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun createZip(
        provider: FileProvider,
        selectedItems: List<FileItem>,
        destinationDirectory: String,
        archiveName: String,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        var createdArchive: FileItem? = null
        try {
            require(selectedItems.isNotEmpty()) { "Select at least one item to compress" }
            require(ArchiveFormat.detect(archiveName) == ArchiveFormat.ZIP) {
                "ZIP archive names must end with .zip"
            }
            validateChildName(archiveName)
            createdArchive = createCheckedFile(provider, destinationDirectory, archiveName)
            val state = ZipCreationState()
            val entryNames = mutableSetOf<String>()

            provider.getOutputStream(createdArchive.path).getOrThrow().use { providerOutput ->
                ZipArchiveOutputStream(providerOutput).use { zip ->
                    zip.setEncoding("UTF-8")
                    zip.setUseLanguageEncodingFlag(true)
                    zip.setUseZip64(Zip64Mode.AsNeeded)
                    selectedItems.forEach { item ->
                        val topLevelName = safeProviderEntryName(item.name)
                        addZipItem(
                            provider = provider,
                            item = item,
                            entryName = topLevelName,
                            zip = zip,
                            entryNames = entryNames,
                            state = state,
                            onProgress = onProgress,
                        )
                    }
                }
            }

            Result.success(provider.getFileInfo(createdArchive.path).getOrThrow())
        } catch (error: Throwable) {
            createdArchive?.let { archive ->
                runCatching {
                    if (provider.exists(archive.path)) {
                        provider.delete(archive.path).getOrThrow()
                    }
                }.onFailure(error::addSuppressed)
            }
            Result.failure(
                if (error is ArchiveException || error is IllegalArgumentException) {
                    error
                } else {
                    ArchiveException(
                        "Could not create $archiveName: ${error.message ?: "archive write failed"}",
                        error,
                    )
                }
            )
        }
    }

    suspend fun extract(
        provider: FileProvider,
        archive: FileItem,
        destinationDirectory: String,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        var extractionRoot: FileItem? = null
        try {
            require(!archive.isDirectory) { "Select an archive file to extract" }
            val format = ArchiveFormat.detect(archive.name)
                ?: throw UnsupportedArchiveException(
                    format = null,
                    message = "Voyager does not recognize ${archive.name} as a supported archive",
                )
            if (!format.canExtract) {
                throw UnsupportedArchiveException(
                    format = format,
                    message = "RAR extraction is not available in this build. Extract the RAR with a trusted archive tool, then open the extracted folder in Voyager.",
                )
            }

            val rootName = "${format.stem(archive.name)}_extracted"
            validateChildName(rootName)
            extractionRoot = createCheckedDirectory(provider, destinationDirectory, rootName)
            val tree = ExtractionTree(provider, extractionRoot, onProgress)

            provider.getInputStream(archive.path).getOrThrow().use { input ->
                when (format) {
                    ArchiveFormat.ZIP -> extractZip(input, tree)
                    ArchiveFormat.TAR -> extractTar(input, tree)
                    ArchiveFormat.TAR_GZIP -> {
                        gzipInput(input).use { compressed ->
                            extractTar(compressed, tree)
                        }
                    }

                    ArchiveFormat.TAR_BZIP2 -> {
                        BZip2CompressorInputStream(input, true).use { compressed ->
                            extractTar(compressed, tree)
                        }
                    }

                    ArchiveFormat.GZIP -> {
                        gzipInput(input).use { compressed ->
                            tree.writeFile(
                                rawName = format.stem(archive.name),
                                input = compressed,
                                totalBytes = null,
                            )
                        }
                    }

                    ArchiveFormat.BZIP2 -> {
                        BZip2CompressorInputStream(input, true).use { compressed ->
                            tree.writeFile(
                                rawName = format.stem(archive.name),
                                input = compressed,
                                totalBytes = null,
                            )
                        }
                    }

                    ArchiveFormat.RAR_UNSUPPORTED -> error("Unsupported RAR reached extraction")
                }
            }

            Result.success(extractionRoot)
        } catch (error: Throwable) {
            extractionRoot?.let { root ->
                runCatching {
                    if (provider.exists(root.path)) {
                        provider.delete(root.path).getOrThrow()
                    }
                }.onFailure(error::addSuppressed)
            }
            Result.failure(
                if (error is ArchiveException || error is IllegalArgumentException) {
                    error
                } else {
                    CorruptArchiveException(
                        "Could not extract ${archive.name}: ${error.message ?: "the archive is invalid or corrupt"}",
                        error,
                    )
                }
            )
        }
    }

    private suspend fun addZipItem(
        provider: FileProvider,
        item: FileItem,
        entryName: String,
        zip: ZipArchiveOutputStream,
        entryNames: MutableSet<String>,
        state: ZipCreationState,
        onProgress: (ArchiveProgress) -> Unit,
    ) {
        val normalizedName = if (item.isDirectory) "$entryName/" else entryName
        val normalizedKey = ArchiveEntryPath.parse(normalizedName).getOrThrow().joinToString("/")
        if (!entryNames.add(normalizedKey)) {
            throw UnsafeArchiveEntryException(normalizedName, "duplicate normalized entry path")
        }

        val entry = ZipArchiveEntry(normalizedName).apply {
            if (item.lastModified.time > 0) time = item.lastModified.time
        }
        zip.putArchiveEntry(entry)
        var entryFailure: Throwable? = null
        try {
            if (!item.isDirectory) {
                var processedBytes = 0L
                provider.getInputStream(item.path).getOrThrow().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        zip.write(buffer, 0, read)
                        processedBytes += read
                        onProgress(
                            ArchiveProgress(
                                currentEntryName = entryName,
                                completedEntries = state.completedEntries,
                                processedBytes = processedBytes,
                                totalBytes = item.size.takeIf { it >= 0 },
                            )
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            entryFailure = error
            throw error
        } finally {
            runCatching { zip.closeArchiveEntry() }
                .onFailure { closeError ->
                    if (entryFailure != null) {
                        entryFailure.addSuppressed(closeError)
                    } else {
                        throw closeError
                    }
                }
        }

        state.completedEntries++
        onProgress(
            ArchiveProgress(
                currentEntryName = entryName,
                completedEntries = state.completedEntries,
                processedBytes = item.size.takeIf { !item.isDirectory && it >= 0 } ?: 0,
                totalBytes = item.size.takeIf { !item.isDirectory && it >= 0 },
            )
        )

        if (item.isDirectory) {
            provider.listFiles(item.path).getOrThrow().forEach { child ->
                addZipItem(
                    provider = provider,
                    item = child,
                    entryName = "$entryName/${safeProviderEntryName(child.name)}",
                    zip = zip,
                    entryNames = entryNames,
                    state = state,
                    onProgress = onProgress,
                )
            }
        }
    }

    private suspend fun extractZip(
        input: InputStream,
        tree: ExtractionTree,
    ) {
        val temporaryZip = File.createTempFile("voyager-archive-", ".zip")
        var extractionFailure: Throwable? = null
        try {
            temporaryZip.outputStream().use { output ->
                copyBounded(input, output)
            }
            ZipFile.builder().setFile(temporaryZip).get().use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    validateZipEntry(entry, zip.canReadEntryData(entry))
                    val unixType = entry.unixMode and UnixStat.FILE_TYPE_FLAG
                    val isDirectory = entry.isDirectory || unixType == UnixStat.DIR_FLAG
                    if (isDirectory) {
                        tree.createDirectory(entry.name)
                    } else {
                        zip.getInputStream(entry).use { entryInput ->
                            tree.writeFile(
                                rawName = entry.name,
                                input = entryInput,
                                totalBytes = entry.size.takeIf { it >= 0 },
                            )
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            extractionFailure = error
            throw error
        } finally {
            if (temporaryZip.exists() && !temporaryZip.delete()) {
                val cleanupError = ArchiveException(
                    "Could not remove the temporary ZIP file ${temporaryZip.name}",
                )
                if (extractionFailure != null) {
                    extractionFailure.addSuppressed(cleanupError)
                } else {
                    throw cleanupError
                }
            }
        }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
        }
    }

    private fun gzipInput(input: InputStream): GzipCompressorInputStream =
        GzipCompressorInputStream.builder()
            .setInputStream(input)
            .setDecompressConcatenated(true)
            .get()

    private fun validateZipEntry(
        entry: ZipArchiveEntry,
        canReadEntryData: Boolean,
    ) {
        if (entry.generalPurposeBit.usesEncryption()) {
            throw UnsupportedArchiveException(
                ArchiveFormat.ZIP,
                "Password-protected ZIP archives are not supported",
            )
        }
        if (!canReadEntryData) {
            throw UnsupportedArchiveException(
                ArchiveFormat.ZIP,
                "The ZIP uses a compression or encryption feature Voyager cannot read",
            )
        }
        if (entry.isUnixSymlink) {
            throw UnsafeArchiveEntryException(entry.name, "symbolic links are not extracted")
        }
        val unixType = entry.unixMode and UnixStat.FILE_TYPE_FLAG
        if (unixType !in setOf(0, UnixStat.FILE_FLAG, UnixStat.DIR_FLAG)) {
            throw UnsafeArchiveEntryException(entry.name, "special file entries are not extracted")
        }
        if (entry.isDirectory && unixType == UnixStat.FILE_FLAG) {
            throw UnsafeArchiveEntryException(entry.name, "file type metadata conflicts with the entry name")
        }
    }

    private suspend fun extractTar(
        input: InputStream,
        tree: ExtractionTree,
    ) {
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                validateTarEntry(entry, tar)
                if (entry.isDirectory) {
                    tree.createDirectory(entry.name)
                } else {
                    tree.writeFile(
                        rawName = entry.name,
                        input = tar,
                        totalBytes = entry.size.takeIf { it >= 0 },
                    )
                }
            }
        }
    }

    private fun validateTarEntry(
        entry: TarArchiveEntry,
        input: TarArchiveInputStream,
    ) {
        if (!entry.isCheckSumOK) {
            throw CorruptArchiveException("The TAR entry ${entry.name} has an invalid checksum")
        }
        if (!input.canReadEntryData(entry)) {
            throw UnsupportedArchiveException(
                ArchiveFormat.TAR,
                "The TAR entry ${entry.name} uses an unsupported encoding",
            )
        }
        if (
            entry.isSymbolicLink ||
            entry.isLink ||
            entry.isBlockDevice ||
            entry.isCharacterDevice ||
            entry.isFIFO ||
            entry.isSparse
        ) {
            throw UnsafeArchiveEntryException(entry.name, "links and special files are not extracted")
        }
        if (!entry.isDirectory && !entry.isFile) {
            throw UnsafeArchiveEntryException(entry.name, "unsupported TAR entry type")
        }
    }

    private suspend fun createCheckedFile(
        provider: FileProvider,
        parentPath: String,
        name: String,
    ): FileItem {
        requireChildAbsent(provider, parentPath, name)
        val created = provider.createFile(parentPath, name).getOrThrow()
        if (created.name != name) {
            runCatching { provider.delete(created.path).getOrThrow() }
            throw ArchiveConflictException(created.path)
        }
        return created
    }

    private suspend fun createCheckedDirectory(
        provider: FileProvider,
        parentPath: String,
        name: String,
    ): FileItem {
        requireChildAbsent(provider, parentPath, name)
        val created = provider.createDirectory(parentPath, name).getOrThrow()
        if (created.name != name) {
            runCatching { provider.delete(created.path).getOrThrow() }
            throw ArchiveConflictException(created.path)
        }
        return created
    }

    private suspend fun requireChildAbsent(
        provider: FileProvider,
        parentPath: String,
        name: String,
    ) {
        val existing = provider.listFiles(parentPath).getOrThrow().firstOrNull { it.name == name }
        if (existing != null) throw ArchiveConflictException(existing.path)
    }

    private fun validateChildName(name: String) {
        if (
            name.isBlank() ||
            name == "." ||
            name == ".." ||
            '/' in name ||
            '\\' in name ||
            '\u0000' in name
        ) {
            throw IllegalArgumentException("Invalid archive destination name")
        }
    }

    private fun safeProviderEntryName(name: String): String {
        val segments = ArchiveEntryPath.parse(name).getOrThrow()
        if (segments.size != 1) {
            throw UnsafeArchiveEntryException(name, "provider item names must contain one path segment")
        }
        return segments.single()
    }

    private class ZipCreationState {
        var completedEntries: Int = 0
    }

    private class ExtractionTree(
        private val provider: FileProvider,
        root: FileItem,
        private val onProgress: (ArchiveProgress) -> Unit,
    ) {
        private val entryNames = mutableSetOf<String>()
        private val nodeTypes = mutableMapOf("" to NodeType.DIRECTORY)
        private val providerPaths = mutableMapOf("" to root.path)
        private var completedEntries = 0

        suspend fun createDirectory(rawName: String) {
            val segments = registerEntry(rawName)
            ensureDirectory(segments, rawName)
            completedEntries++
            onProgress(
                ArchiveProgress(
                    currentEntryName = segments.joinToString("/"),
                    completedEntries = completedEntries,
                )
            )
        }

        suspend fun writeFile(
            rawName: String,
            input: InputStream,
            totalBytes: Long?,
        ) {
            val segments = registerEntry(rawName)
            val key = segments.joinToString("/")
            if (nodeTypes[key] != null) {
                throw UnsafeArchiveEntryException(rawName, "the path conflicts with another entry type")
            }
            val parentPath = ensureDirectory(segments.dropLast(1), rawName)
            val created = createCheckedFile(provider, parentPath, segments.last())
            nodeTypes[key] = NodeType.FILE
            providerPaths[key] = created.path

            var processedBytes = 0L
            try {
                provider.getOutputStream(created.path).getOrThrow().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        processedBytes += read
                        onProgress(
                            ArchiveProgress(
                                currentEntryName = key,
                                completedEntries = completedEntries,
                                processedBytes = processedBytes,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            } catch (error: Throwable) {
                runCatching {
                    if (provider.exists(created.path)) {
                        provider.delete(created.path).getOrThrow()
                    }
                }.onFailure(error::addSuppressed)
                throw error
            }

            completedEntries++
            onProgress(
                ArchiveProgress(
                    currentEntryName = key,
                    completedEntries = completedEntries,
                    processedBytes = processedBytes,
                    totalBytes = totalBytes,
                )
            )
        }

        private fun registerEntry(rawName: String): List<String> {
            val segments = ArchiveEntryPath.parse(rawName).getOrThrow()
            val key = segments.joinToString("/")
            if (!entryNames.add(key)) {
                throw UnsafeArchiveEntryException(rawName, "duplicate normalized entry path")
            }
            return segments
        }

        private suspend fun ensureDirectory(
            segments: List<String>,
            rawName: String,
        ): String {
            var currentKey = ""
            var currentProviderPath = providerPaths.getValue("")
            for (segment in segments) {
                currentKey = if (currentKey.isEmpty()) segment else "$currentKey/$segment"
                when (nodeTypes[currentKey]) {
                    NodeType.FILE -> {
                        throw UnsafeArchiveEntryException(
                            rawName,
                            "the path conflicts with a file entry",
                        )
                    }

                    NodeType.DIRECTORY -> {
                        currentProviderPath = providerPaths.getValue(currentKey)
                    }

                    null -> {
                        val created = createCheckedDirectory(
                            provider,
                            currentProviderPath,
                            segment,
                        )
                        nodeTypes[currentKey] = NodeType.DIRECTORY
                        providerPaths[currentKey] = created.path
                        currentProviderPath = created.path
                    }
                }
            }
            return currentProviderPath
        }

        private enum class NodeType {
            DIRECTORY,
            FILE,
        }
    }
}
