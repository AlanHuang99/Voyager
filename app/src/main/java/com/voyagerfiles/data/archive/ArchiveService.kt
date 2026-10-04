package com.voyagerfiles.data.archive

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.NewFile
import com.voyagerfiles.data.repository.StreamTransfer
import com.voyagerfiles.data.repository.TransferAbortable
import com.voyagerfiles.data.repository.TransferCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale

object ArchiveService {
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
            requireChildAbsent(provider, destinationDirectory, archiveName)
            val plan = planZip(provider, selectedItems, onProgress)
            createdArchive = createCheckedFile(provider, destinationDirectory, archiveName)

            val providerOutput = provider.getOutputStream(createdArchive.path).getOrThrow()
            val completedProgress = TransferCancellation.registerAbort {
                (providerOutput as? TransferAbortable)?.abortTransfer()
            }.use {
                providerOutput.use { output ->
                    ZipArchiveOutputStream(output).use { zip ->
                        zip.setEncoding("UTF-8")
                        zip.setUseLanguageEncodingFlag(true)
                        zip.setUseZip64(Zip64Mode.AsNeeded)
                        writeZip(provider, plan, zip, onProgress)
                    }
                }
            }

            val archive = provider.getFileInfo(createdArchive.path).getOrThrow()
            TransferCancellation.check()
            onProgress(completedProgress)
            TransferCancellation.check()
            Result.success(archive)
        } catch (error: Throwable) {
            val cancellation = cancellationOf(error)
            withContext(NonCancellable) {
                createdArchive?.let { archive ->
                    runCatching {
                        if (provider.exists(archive.path)) {
                            provider.delete(archive.path).getOrThrow()
                        }
                    }.onFailure(error::addSuppressed)
                }
            }
            if (cancellation != null) throw cancellation
            Result.failure(
                if (error is ArchiveException || error is IllegalArgumentException) {
                    error
                } else {
                    ArchiveException(failureReason(error, "archive write failed"), error)
                }
            )
        }
    }

    suspend fun extract(
        provider: FileProvider,
        archive: FileItem,
        destinationDirectory: String,
        onReport: (ArchiveExtractionReport) -> Unit = {},
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        var tree: ExtractionTree? = null
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
            requireChildAbsent(provider, destinationDirectory, rootName)
            val extractionTree = ExtractionTree(
                provider = provider,
                onProgress = onProgress,
                ignoresNameCase = provider.ignoresNameCase(destinationDirectory),
            ) {
                createCheckedDirectory(provider, destinationDirectory, rootName)
            }
            tree = extractionTree

            val sourceSize = archive.size.takeIf { it > 0 }
            provider.getInputStream(archive.path).getOrThrow().use { input ->
                val abort = TransferCancellation.registerAbort { abortInput(input) }
                try {
                    if (format == ArchiveFormat.ZIP) {
                        extractionTree.sourceErrorsSkippable = true
                        extractZip(input, sourceSize, extractionTree)
                    } else {
                        // Stream formats have no index, so progress follows the compressed input.
                        val source = CountingInputStream(input)
                        extractionTree.sourcePosition = source::count
                        extractionTree.totalBytes = sourceSize
                        extractStream(format, archive.name, source, extractionTree)
                    }
                } finally {
                    abort.close()
                }
            }

            TransferCancellation.check()
            extractionTree.root()
            TransferCancellation.check()
            val report = extractionTree.report(complete = true)
            // When every entry failed there is nothing to keep, so this is a failed extraction.
            report.notExtracted.firstOrNull()?.takeIf { report.extractedEntries == 0 }?.let { throw it.error }
            extractionTree.reportComplete()
            TransferCancellation.check()
            onReport(report)
            Result.success(report.root)
        } catch (error: Throwable) {
            val cancellation = cancellationOf(error)
            // A failure keeps what was written. Cancel removes it, and so does an unsafe entry, because
            // the archive cannot be trusted.
            val keep = cancellation == null && error !is UnsafeArchiveEntryException
            val kept = withContext(NonCancellable + TransferCancellation.detached()) {
                runCatching { tree?.keepOrRemove(keep) }
                    .onFailure(error::addSuppressed)
                    .getOrNull()
            }
            kept?.let(onReport)
            if (cancellation != null) throw cancellation
            val failure = if (error is ArchiveException || error is IllegalArgumentException) {
                error
            } else {
                val truncated = generateSequence(error) { it.cause }.any { it is EOFException }
                CorruptArchiveException(
                    if (truncated) "the archive ends early and may be incomplete"
                    else failureReason(error, "the archive is invalid or corrupt"),
                    error,
                )
            }
            Result.failure(kept?.let { PartialExtractionException(it.root, failure) } ?: failure)
        }
    }

    /**
     * Walks the selection before the archive exists, so totals are known up front and duplicate paths fail without leaving a partial archive. Each folder is still listed only once.
     */
    private suspend fun planZip(
        provider: FileProvider,
        selectedItems: List<FileItem>,
        onProgress: (ArchiveProgress) -> Unit,
    ): List<ZipPlanEntry> {
        val plan = mutableListOf<ZipPlanEntry>()
        val entryNames = mutableSetOf<String>()

        suspend fun visit(item: FileItem, entryName: String) {
            TransferCancellation.check()
            val zipName = if (item.isDirectory) "$entryName/" else entryName
            val normalizedKey = ArchiveEntryPath.parse(zipName).getOrThrow().joinToString("/")
            if (!entryNames.add(normalizedKey)) {
                throw UnsafeArchiveEntryException(zipName, "duplicate normalized entry path")
            }
            plan += ZipPlanEntry(item, entryName, zipName)
            onProgress(
                ArchiveProgress(
                    phase = ArchivePhase.PREPARING,
                    currentEntryName = entryName,
                    completedEntries = plan.size,
                )
            )
            if (item.isDirectory) {
                provider.listFiles(item.path).getOrThrow().forEach { child ->
                    visit(child, "$entryName/${safeProviderEntryName(child.name)}")
                }
            }
        }

        selectedItems.forEach { item -> visit(item, safeProviderEntryName(item.name)) }
        return plan
    }

    private suspend fun writeZip(
        provider: FileProvider,
        plan: List<ZipPlanEntry>,
        zip: ZipArchiveOutputStream,
        onProgress: (ArchiveProgress) -> Unit,
    ): ArchiveProgress {
        val fileSizes = plan.filterNot { it.item.isDirectory }.map { it.item.size }
        val totalBytes = fileSizes.takeIf { sizes -> sizes.all { it >= 0 } }?.sum()
        var completedEntries = 0
        var writtenBytes = 0L

        fun report(entryName: String, pendingBytes: Long) = onProgress(
            ArchiveProgress(
                currentEntryName = entryName,
                completedEntries = completedEntries,
                totalEntries = plan.size,
                processedBytes = writtenBytes + pendingBytes,
                totalBytes = totalBytes,
            )
        )

        plan.forEach { planned ->
            TransferCancellation.check()
            val entry = ZipArchiveEntry(planned.zipName).apply {
                if (planned.item.lastModified.time > 0) time = planned.item.lastModified.time
            }
            zip.putArchiveEntry(entry)
            var entryFailure: Throwable? = null
            var entryBytes = 0L
            try {
                if (!planned.item.isDirectory) {
                    provider.getInputStream(planned.item.path).getOrThrow().use { input ->
                        StreamTransfer.copy(input, zip, planned.entryName, totalBytes = null) { copied ->
                            entryBytes = copied.bytesTransferred
                            report(planned.entryName, entryBytes)
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
            completedEntries++
            writtenBytes += entryBytes
            report(planned.entryName, 0)
        }
        return ArchiveProgress(
            completedEntries = completedEntries,
            totalEntries = plan.size,
            processedBytes = writtenBytes,
            totalBytes = totalBytes,
            isComplete = true,
        )
    }

    private suspend fun extractZip(
        input: InputStream,
        sourceSize: Long?,
        tree: ExtractionTree,
    ) = withSpooledArchive(input, ".zip", sourceSize, tree::reportReadingSource) { spooledZip ->
        ZipFile.builder().setFile(spooledZip).get().use { zip ->
            val entries = zip.entries.toList()
            entries.forEach { entry -> validateZipEntry(entry, zip.canReadEntryData(entry)) }
            val fileSizes = entries.filterNot(::isZipDirectory).map { it.size }
            tree.totalEntries = entries.size
            tree.totalBytes = fileSizes.takeIf { sizes -> sizes.all { it >= 0 } }?.sum()
            entries.forEach { entry ->
                extractZipEntry(entry, tree) { zip.getInputStream(entry) }
            }
        }
    }

    private suspend fun extractZipEntry(
        entry: ZipArchiveEntry,
        tree: ExtractionTree,
        openEntry: () -> InputStream,
    ) {
        if (isZipDirectory(entry)) {
            tree.createDirectory(entry.name)
        } else {
            openEntry().use { entryInput ->
                tree.writeFile(rawName = entry.name, input = entryInput)
            }
        }
    }

    private fun isZipDirectory(entry: ZipArchiveEntry): Boolean =
        entry.isDirectory || (entry.unixMode and UnixStat.FILE_TYPE_FLAG) == UnixStat.DIR_FLAG

    /** Copies [input] to an app-private temporary file for readers that need random access. */
    private suspend fun <T> withSpooledArchive(
        input: InputStream,
        suffix: String,
        sourceSize: Long?,
        onCopied: (copiedBytes: Long, totalBytes: Long?) -> Unit,
        block: suspend (File) -> T,
    ): T {
        val spooledArchive = File.createTempFile("voyager-archive-", suffix)
        var failure: Throwable? = null
        try {
            spooledArchive.outputStream().use { output ->
                StreamTransfer.copy(input, output, spooledArchive.name, sourceSize) { copied ->
                    onCopied(copied.bytesTransferred, sourceSize)
                }
            }
            return block(spooledArchive)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (spooledArchive.exists() && !spooledArchive.delete()) {
                val cleanupError = ArchiveException(
                    "Could not remove the temporary archive file ${spooledArchive.name}",
                )
                if (failure != null) {
                    failure.addSuppressed(cleanupError)
                } else {
                    throw cleanupError
                }
            }
        }
    }

    private suspend fun extractStream(
        format: ArchiveFormat,
        archiveName: String,
        input: InputStream,
        tree: ExtractionTree,
    ) {
        when (format) {
            ArchiveFormat.TAR -> extractTar(input, tree)
            ArchiveFormat.TAR_GZIP -> gzipInput(input).use { extractTar(it, tree) }
            ArchiveFormat.TAR_BZIP2 -> BZip2CompressorInputStream(input, true).use { extractTar(it, tree) }
            ArchiveFormat.GZIP -> gzipInput(input).use { compressed ->
                tree.totalEntries = 1
                tree.writeFile(rawName = format.stem(archiveName), input = compressed)
            }
            ArchiveFormat.BZIP2 -> BZip2CompressorInputStream(input, true).use { compressed ->
                tree.totalEntries = 1
                tree.writeFile(rawName = format.stem(archiveName), input = compressed)
            }
            ArchiveFormat.ZIP -> error("ZIP archives are extracted from a spooled copy")
            ArchiveFormat.RAR_UNSUPPORTED -> error("Unsupported RAR reached extraction")
        }
    }

    /** The operation's message already names the action and the archive, so this is only the cause. */
    private fun failureReason(error: Throwable, fallback: String): String =
        error.message?.takeIf { it.isNotBlank() } ?: fallback

    private fun abortInput(input: InputStream) {
        if (input is TransferAbortable) input.abortTransfer() else input.close()
    }

    /** An aborted stream surfaces as an I/O error; the cancellation token says what really happened. */
    private fun cancellationOf(error: Throwable): CancellationException? =
        error as? CancellationException
            ?: runCatching { TransferCancellation.check() }.exceptionOrNull() as? CancellationException

    private fun gzipInput(input: InputStream): GzipCompressorInputStream =
        GzipCompressorInputStream.builder()
            .setInputStream(input)
            .setDecompressConcatenated(true)
            .get()

    private fun validateZipEntry(
        entry: ZipArchiveEntry,
        canReadEntryData: Boolean,
    ) {
        ArchiveEntryPath.parse(entry.name).getOrThrow()
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
                    tree.writeFile(rawName = entry.name, input = tar)
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
        return createExactFile(provider, parentPath, name)
    }

    private suspend fun createCheckedDirectory(
        provider: FileProvider,
        parentPath: String,
        name: String,
    ): FileItem {
        requireChildAbsent(provider, parentPath, name)
        return createExactDirectory(provider, parentPath, name)
    }

    /**
     * Creates [name] without listing [parentPath] first. Only for folders Voyager just created, where
     * a listing per child would make extracting n files into one folder cost O(n²) entries.
     */
    private suspend fun createExactFile(
        provider: FileProvider,
        parentPath: String,
        name: String,
    ): FileItem = requireExactName(provider, provider.createFile(parentPath, name).getOrThrow(), name)

    private suspend fun createExactDirectory(
        provider: FileProvider,
        parentPath: String,
        name: String,
    ): FileItem = requireExactName(provider, provider.createDirectory(parentPath, name).getOrThrow(), name)

    /** Like [createExactFile], but already open for writing, which saves local storage a second open. */
    private suspend fun openExactFile(
        provider: FileProvider,
        parentPath: String,
        name: String,
    ): NewFile {
        val created = provider.openNewFile(parentPath, name).getOrThrow()
        if (created.name != name) {
            runCatching { created.output.close() }
            runCatching { provider.delete(created.path).getOrThrow() }
            throw ArchiveConflictException(created.path)
        }
        return created
    }

    /** Some providers pick a free name such as "name (1)" instead of failing on a conflict. */
    private suspend fun requireExactName(
        provider: FileProvider,
        created: FileItem,
        name: String,
    ): FileItem {
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

    /** [entryName] names the item in progress; [zipName] is the entry, with "/" for folders. */
    private class ZipPlanEntry(val item: FileItem, val entryName: String, val zipName: String)

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var count: Long = 0
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }

        override fun markSupported(): Boolean = false
    }

    /** Remembers whether reading the entry failed, which a stream format cannot skip past. */
    private class ReadFailureTrackingInputStream(input: InputStream) : FilterInputStream(input) {
        var failed = false
            private set

        override fun read(): Int = tracked { super.read() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            tracked { super.read(buffer, offset, length) }

        override fun skip(n: Long): Long = tracked { super.skip(n) }

        private inline fun <T> tracked(block: () -> T): T = try {
            block()
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    /**
     * [ignoresNameCase] is true for destinations known to treat names that differ in letter case as one
     * item, false where every entry keeps its exact name, and null when the root has to show which.
     */
    private class ExtractionTree(
        private val provider: FileProvider,
        private val onProgress: (ArchiveProgress) -> Unit,
        private var ignoresNameCase: Boolean?,
        private val createRoot: suspend () -> FileItem,
    ) {
        private var createdRoot: FileItem? = null
        private val entryNames = mutableSetOf<String>()
        private val nodeTypes = mutableMapOf<String, NodeType>()
        private val providerPaths = mutableMapOf<String, String>()

        /**
         * Children of each created folder by name (case-folded where the destination ignores case), with
         * the path of a child folder or null for a file. This tree is the only writer there, so it never
         * has to list them.
         */
        private val children = mutableMapOf<String, MutableMap<String, String?>>()

        /** Creation order, so a removal deletes children before their folder and can count as it goes. */
        private val createdPaths = mutableListOf<String>()
        private var completedEntries = 0
        private var writtenFiles = 0
        private val renamed = mutableListOf<RenamedEntry>()
        private val notExtracted = mutableListOf<FailedEntry>()
        private var renamedCount = 0
        private var notExtractedCount = 0
        private var writtenBytes = 0L
        var totalEntries: Int? = null
        var totalBytes: Long? = null

        /** When set, byte progress follows this position in the compressed source instead of bytes written. */
        var sourcePosition: (() -> Long)? = null

        /** True when entries are read independently, so a broken entry does not break the ones after it. */
        var sourceErrorsSkippable = false

        fun reportReadingSource(copiedBytes: Long, totalBytes: Long?) = onProgress(
            ArchiveProgress(
                phase = ArchivePhase.READING_SOURCE,
                processedBytes = copiedBytes,
                totalBytes = totalBytes,
            )
        )

        fun reportComplete() = onProgress(
            ArchiveProgress(
                completedEntries = completedEntries,
                totalEntries = totalEntries ?: completedEntries,
                processedBytes = sourcePosition?.invoke() ?: writtenBytes,
                totalBytes = totalBytes,
                isComplete = true,
                skippedEntries = notExtractedCount,
                renamedEntries = renamedCount,
            )
        )

        /** Creates the extraction root on first use, so an unreadable archive leaves nothing behind. */
        suspend fun root(): FileItem = createdRoot ?: createRoot().also { root ->
            createdRoot = root
            nodeTypes[""] = NodeType.DIRECTORY
            providerPaths[""] = root.path
            if (ignoresNameCase == null) ignoresNameCase = lookupIgnoresCase(root)
        }

        /**
         * Asks for the root just created with the case of every letter flipped, as `git init` does with
         * `.git/CoNfIg`. Files and folders share one name lookup, and the folders created below the root
         * inherit how it treats case, so the answer holds for the whole extraction. The root's own name
         * is looked up first, so a failed lookup cannot pass for a case-sensitive destination.
         */
        private suspend fun lookupIgnoresCase(root: FileItem): Boolean {
            require(root.path.endsWith("/${root.name}")) { "Cannot look up ${root.path} by name" }
            if (!provider.exists(root.path)) {
                throw ArchiveException("The new folder ${root.name} cannot be found on the destination")
            }
            val flipped = root.name.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
            return provider.exists(root.path.dropLast(root.name.length) + flipped.joinToString(""))
        }

        fun report(complete: Boolean) = ArchiveExtractionReport(
            root = checkNotNull(createdRoot),
            extractedEntries = completedEntries,
            extractedFiles = writtenFiles,
            totalEntries = totalEntries,
            renamed = renamed.toList(),
            notExtracted = notExtracted.toList(),
            renamedCount = renamedCount,
            notExtractedCount = notExtractedCount,
            complete = complete,
        )

        /**
         * After a failure, keeps what was written and returns its report. When nothing was extracted, or
         * [keep] is false, removes it instead and restores the last counts for the result.
         */
        suspend fun keepOrRemove(keep: Boolean): ArchiveExtractionReport? {
            createdRoot ?: return null
            if (keep && completedEntries > 0) return report(complete = false)
            val summary = progress(currentEntryName = null)
            discard()
            onProgress(summary)
            return null
        }

        private suspend fun discard() {
            val root = createdRoot ?: return
            val removals = createdPaths.asReversed()
            removals.forEachIndexed { index, path ->
                // A leftover makes the final recursive delete below fail, which reports it.
                runCatching { provider.delete(path).getOrThrow() }
                onProgress(
                    ArchiveProgress(
                        phase = ArchivePhase.REMOVING,
                        currentEntryName = path.substringAfterLast('/'),
                        completedEntries = index + 1,
                        totalEntries = removals.size,
                    )
                )
            }
            if (provider.exists(root.path)) {
                provider.delete(root.path).getOrThrow()
            }
            createdRoot = null
            entryNames.clear()
            nodeTypes.clear()
            providerPaths.clear()
            children.clear()
            createdPaths.clear()
            completedEntries = 0
            writtenFiles = 0
            renamed.clear()
            notExtracted.clear()
            renamedCount = 0
            notExtractedCount = 0
            writtenBytes = 0
        }

        suspend fun createDirectory(rawName: String) {
            TransferCancellation.check()
            val segments = registerEntry(rawName)
            ensureDirectory(segments, rawName)
            completedEntries++
            report(segments.joinToString("/"))
        }

        suspend fun writeFile(
            rawName: String,
            input: InputStream,
        ) {
            TransferCancellation.check()
            val segments = registerEntry(rawName)
            val key = segments.joinToString("/")
            if (nodeTypes[key] != null) {
                throw UnsafeArchiveEntryException(rawName, "the path conflicts with another entry type")
            }
            // The archive declares a file here whatever happens to its write, so no entry may go below it.
            nodeTypes[key] = NodeType.FILE
            val parentPath = ensureDirectory(segments.dropLast(1), rawName)
            val name = claimName(parentPath, segments.last(), key, isDirectory = false)

            val source = ReadFailureTrackingInputStream(input)
            var fileBytes = 0L
            try {
                val created = openExactFile(provider, parentPath, name)
                createdPaths += created.path
                providerPaths[key] = created.path
                try {
                    val output = created.output
                    TransferCancellation.registerAbort {
                        (output as? TransferAbortable)?.abortTransfer()
                    }.use {
                        output.use {
                            StreamTransfer.copy(source, output, key, totalBytes = null) { copied ->
                                fileBytes = copied.bytesTransferred
                                report(key, fileBytes)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    val cleanup = withContext(NonCancellable) {
                        runCatching {
                            if (provider.exists(created.path)) {
                                provider.delete(created.path).getOrThrow()
                            }
                        }
                    }
                    cleanup.exceptionOrNull()?.let { cleanupError ->
                        // The incomplete file is still there: it stays tracked so a removal finds it, and the
                        // extraction stops instead of finishing with a broken file in it.
                        val failure = ArchiveCleanupException(created.path, error).apply { addSuppressed(cleanupError) }
                        leaveOut(key, failure)
                        throw failure
                    }
                    createdPaths.removeAt(createdPaths.lastIndex)
                    providerPaths.remove(key)
                    throw error
                }
            } catch (error: Throwable) {
                if (!canSkip(error, source)) throw error
                return leaveOut(key, error)
            }

            completedEntries++
            writtenFiles++
            writtenBytes += fileBytes
            report(key)
        }

        private fun canSkip(error: Throwable, source: ReadFailureTrackingInputStream): Boolean =
            error is Exception &&
                error !is ArchiveCleanupException &&
                cancellationOf(error) == null &&
                (sourceErrorsSkippable || !source.failed)

        /** Counts and lists an entry that was not extracted; its declared type stays as it was. */
        private fun leaveOut(key: String, error: Throwable) {
            notExtractedCount++
            if (notExtracted.size < ArchiveExtractionReport.MAX_LISTED) notExtracted += FailedEntry(key, error)
            report(key)
        }

        /**
         * Returns the name to create [name] under in [parentPath]: numbered when the destination ignores
         * case and only the case differs, otherwise [name] itself.
         */
        private fun claimName(
            parentPath: String,
            name: String,
            entryPath: String,
            isDirectory: Boolean,
        ): String {
            val used = children.getOrPut(parentPath) { mutableMapOf() }
            if (name.foldCase() !in used) {
                used[name.foldCase()] = null
                return name
            }
            val free = generateSequence(1) { it + 1 }
                .map { numberedName(name, it, isDirectory) }
                .first { it.foldCase() !in used }
            used[free.foldCase()] = null
            renamedCount++
            if (renamed.size < ArchiveExtractionReport.MAX_LISTED) renamed += RenamedEntry(entryPath, free)
            return free
        }

        private fun report(entryName: String, pendingBytes: Long = 0) =
            onProgress(progress(entryName, pendingBytes))

        private fun progress(currentEntryName: String?, pendingBytes: Long = 0) = ArchiveProgress(
            currentEntryName = currentEntryName,
            completedEntries = completedEntries,
            totalEntries = totalEntries,
            processedBytes = sourcePosition?.invoke() ?: (writtenBytes + pendingBytes),
            totalBytes = totalBytes,
            skippedEntries = notExtractedCount,
            renamedEntries = renamedCount,
        )

        private fun registerEntry(rawName: String): List<String> {
            val segments = ArchiveEntryPath.parse(rawName).getOrThrow()
            val key = segments.joinToString("/")
            if (!entryNames.add(key)) {
                throw UnsafeArchiveEntryException(rawName, "duplicate normalized entry path")
            }
            return segments
        }

        /** Returns the folder's provider path, creating what is missing. */
        private suspend fun ensureDirectory(
            segments: List<String>,
            rawName: String,
        ): String {
            var currentKey = ""
            var currentProviderPath = root().path
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
                        val parentPath = currentProviderPath
                        // Where the destination ignores case, a folder whose name differs only in case
                        // merges into the one created first, as the disk would; nothing inside it is lost.
                        currentProviderPath = children[parentPath]?.get(segment.foldCase()) ?: run {
                            val name = claimName(parentPath, segment, currentKey, isDirectory = true)
                            val created = createExactDirectory(provider, parentPath, name)
                            createdPaths += created.path
                            children.getValue(parentPath)[name.foldCase()] = created.path
                            created.path
                        }
                        nodeTypes[currentKey] = NodeType.DIRECTORY
                        providerPaths[currentKey] = currentProviderPath
                    }
                }
            }
            return currentProviderPath
        }

        private fun String.foldCase(): String = if (ignoresNameCase == true) lowercase(Locale.ROOT) else this

        private enum class NodeType {
            DIRECTORY,
            FILE,
        }
    }

    /** "photo.png" becomes "photo (1).png"; folders keep dots in their names. */
    private fun numberedName(name: String, number: Int, isDirectory: Boolean): String {
        val extensionStart = name.lastIndexOf('.').takeIf { !isDirectory && it > 0 }
            ?: return "$name ($number)"
        return "${name.substring(0, extensionStart)} ($number)${name.substring(extensionStart)}"
    }
}
