package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

data class RootTextDocument(val path: String, val text: String, internal val fingerprint: String)

/** Separate from local storage: constructing this provider does not request root until used. */
class RootFileProvider(private val shell: RootShell = RootShell()) : FileProvider, Closeable {
    override suspend fun listFiles(path: String) = io {
        val directory = path(path)
        val script = "d=${q(directory)}; [ -d \"\$d\" ] && [ -r \"\$d\" ] && [ -x \"\$d\" ] || { echo 'Folder missing or permission denied' >&2; exit 1; }; " +
            "for p in \"\$d\"/* \"\$d\"/.[!.]* \"\$d\"/..?*; do [ -e \"\$p\" ] || [ -L \"\$p\" ] || continue; " +
            "printf '%s\\0' \"\$p\"; stat -c '%f %s %Y %u:%g' -- \"\$p\" || exit 1; done"
        parse(shell.execute(script))
    }

    override suspend fun getFileInfo(path: String) = io { info(path(path)) }
    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val p = q(path(path))
        // A denied su request is an error, not an absent file.
        shell.execute("if [ -e $p ] || [ -L $p ]; then printf 1; else printf 0; fi", 1).contentEquals(byteArrayOf(49))
    }
    override fun getParentPath(path: String): String? = File(path(path)).parent
    override fun isSameStorage(other: FileProvider): Boolean = other is RootFileProvider || other is LocalFileProvider
    override fun isSamePath(first: String, second: String): Boolean = canonical(first) == canonical(second)
    override suspend fun isDescendantPath(ancestor: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val root = canonical(ancestor).trimEnd('/')
        val candidate = canonical(path)
        candidate == root || candidate.startsWith("$root/")
    }

    override suspend fun createDirectory(path: String, name: String) = io {
        val target = child(path, name)
        shell.execute("mkdir -- ${q(target)}")
        info(target)
    }
    override suspend fun createFile(path: String, name: String) = io {
        val target = child(path, name)
        shell.execute("set -C; : > ${q(target)}")
        info(target)
    }
    override suspend fun delete(path: String) = io {
        val p = mutablePath(path)
        shell.execute("[ -e ${q(p)} ] || [ -L ${q(p)} ] || { echo 'File no longer exists' >&2; exit 1; }; rm -r -- ${q(p)}")
        Unit
    }
    override suspend fun rename(oldPath: String, newName: String) = io {
        val source = mutablePath(oldPath)
        val target = child(checkNotNull(getParentPath(source)), newName)
        moveNew(source, target)
        info(target)
    }
    override suspend fun copy(sourcePath: String, destPath: String) = io {
        val source = mutablePath(sourcePath)
        val target = child(destPath, File(source).name)
        require(!canonical(target).startsWith("${canonical(source).trimEnd('/')}/")) { "A folder cannot be copied into itself" }
        shell.execute(noTarget(target) + "exec cp -R -P -pnT -- ${q(source)} ${q(target)}")
        Unit
    }
    override suspend fun move(sourcePath: String, destPath: String) = io {
        val source = mutablePath(sourcePath)
        val target = child(destPath, File(source).name)
        require(!canonical(target).startsWith("${canonical(source).trimEnd('/')}/")) { "A folder cannot be moved into itself" }
        moveNew(source, target)
    }
    override suspend fun getInputStream(path: String): Result<InputStream> = io {
        val p = q(path(path))
        shell.input(regular(p) + "exec cat -- $p")
    }
    override suspend fun getOutputStream(path: String): Result<OutputStream> = io {
        stagedOutput(mutablePath(path))
    }
    override suspend fun writeStream(path: String, input: InputStream, sourcePath: String, totalBytes: Long?, onProgress: (StreamTransferProgress) -> Unit) = io {
        val output = stagedOutput(mutablePath(path))
        try {
            StreamTransfer.copy(input, output, sourcePath, totalBytes, onProgress = onProgress)
            output.close()
        } catch (error: Throwable) {
            output.abortTransfer()
            throw error
        }
    }

    suspend fun readText(path: String): Result<RootTextDocument> = io {
        val p = path(path)
        val before = fingerprint(p)
        val bytes = shell.execute(regular(q(p)) + "exec cat -- ${q(p)}", MAX_TEXT_BYTES)
        if (bytes.any { it == 0.toByte() }) throw IOException("Binary files cannot be edited as text")
        val text = decode(bytes)
        if (before != fingerprint(p) || !before.endsWith(hash(bytes))) throw IOException("File changed while opening. Open it again.")
        RootTextDocument(p, text, before)
    }

    suspend fun saveText(document: RootTextDocument, text: String): Result<Unit> = io {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Text editor limit is 256 KiB" }
        require('\u0000' !in text) { "Binary files cannot be edited as text" }
        val output = stagedOutput(mutablePath(document.path), document.fingerprint)
        try {
            output.write(bytes)
            output.close()
        } catch (error: Throwable) {
            output.abortTransfer()
            throw error
        }
    }

    private fun stagedOutput(path: String, expected: String? = null): StagedOutput {
        val original = expected ?: fingerprint(path)
        val parent = canonical(checkNotNull(getParentPath(path)))
        val parentId = shell.execute("stat -Lc '%d:%i' -- ${q(parent)}", 128).toString(Charsets.UTF_8).trim()
        val stageName = ".voyager-root-${UUID.randomUUID()}"
        // Android mksh closes extra descriptors in children; address the live owning shell.
        val source = "\"\$rootfd/4/\"${q(File(path).name)}"
        val stage = "\"\$rootfd/4/$stageName\""
        val pinnedParent = "rootfd=/proc/\$\$/fd; cd ${q(parent)} || exit 1; exec 4<.; " +
            "[ \"\$(stat -Lc '%d:%i' \$rootfd/4)\" = ${q(parentId)} ] || { echo 'Parent folder changed' >&2; exit 1; }; "
        val prepare = pinnedParent + "umask 077; mkdir -- ${q(stageName)} || exit 1; cd ${q(stageName)} || exit 1; " +
            "[ \"\$(pwd -P)\" = ${q("${parent.trimEnd('/')}/$stageName")} ] && " +
            "[ \"\$(stat -c %u .)\" = \"\$(id -u)\" ] || { echo 'Staging folder changed' >&2; exit 1; }; " +
            "case \"\$(stat -c %a .)\" in 700|2700) ;; *) echo 'Unsafe staging permissions' >&2; exit 1;; esac; stat -c '%d:%i' ."
        val stageId = shell.execute(prepare, 128).toString(Charsets.UTF_8).trim()
        val pinStage = "[ ! -L $stage ] || exit 1; exec 5<$stage; " +
            "[ \"\$(stat -Lc '%d:%i' \$rootfd/5)\" = ${q(stageId)} ] || { echo 'Staging folder changed' >&2; exit 1; }; "
        // Cleanup uses the recorded directory inode and never recursively follows a replaced path.
        val cleanup = pinnedParent + pinStage + "rm -f -- \$rootfd/5/payload; " +
            "if [ ! -L $stage ] && [ \"\$(stat -c '%d:%i' -- $stage 2>/dev/null)\" = ${q(stageId)} ]; then rmdir -- $stage; fi; exit \$?"
        val validateOriginal = "actual=\$(${fingerprintOperand(source)}); [ \"\$actual\" = ${q(original)} ] || " +
            "{ echo 'File changed since opening. Original preserved; reopen before saving.' >&2; exit 1; }; " +
            "[ \"\$(stat -c %h -- $source)\" = 1 ] || { echo 'Cannot replace a hard-linked file' >&2; exit 1; }; "
        val script = pinnedParent + pinStage + "cd \$rootfd/5 || exit 1; " + validateOriginal +
            "set -C; exec 3>payload; set +C; stagefile=\$(stat -Lc '%d:%i' \$rootfd/3); " +
            // All data and metadata changes stay bound to the exclusively opened staging inode.
            "owner=\$(stat -c '%u:%g' -- $source); mode=\$(stat -c '%a' -- $source); " +
            "chown \"\$owner\" \$rootfd/3 && chmod \"\$mode\" \$rootfd/3 || exit 1; " +
            "context=\$(ls -Zd -- $source 2>/dev/null); context=\${context%% *}; " +
            "targetcontext=\$(ls -ZLd \$rootfd/3 2>/dev/null); targetcontext=\${targetcontext%% *}; " +
            "case \"\$context\" in *:*) if [ \"\$context\" != \"\$targetcontext\" ]; then chcon \"\$context\" \$rootfd/3 || exit 1; fi;; esac; " +
            "cat >&3 || exit 1; " + validateOriginal +
            "[ -f payload ] && [ ! -L payload ] && [ \"\$(stat -c '%d:%i' payload)\" = \"\$stagefile\" ] && " +
            "[ \"\$(stat -Lc %h \$rootfd/3)\" = 1 ] || { echo 'Staging file changed; original retained' >&2; exit 1; }; " +
            "[ \"\$(stat -c '%f:%u:%g' -- $source)\" = \"\$(stat -Lc '%f:%u:%g' \$rootfd/3)\" ] || " +
            "{ echo 'Cannot preserve file ownership or permissions; original retained' >&2; exit 1; }; " +
            "mv -fT -- payload $source; exit \$?"
        try {
            return StagedOutput(shell.output(script), cleanup)
        } catch (error: Throwable) {
            runCatching { shell.execute(cleanup) }
            throw error
        }
    }

    private inner class StagedOutput(val stream: OutputStream, val cleanupScript: String) : OutputStream(), TransferAbortable {
        private var closed = false
        override fun write(byte: Int) = stream.write(byte)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = stream.write(bytes, offset, length)
        override fun close() {
            if (closed) return
            closed = true
            try {
                TransferCancellation.check()
                stream.close()
            } catch (error: Throwable) {
                (stream as TransferAbortable).abortTransfer()
                throw error
            } finally { cleanup() }
        }
        override fun abortTransfer() {
            closed = true
            (stream as TransferAbortable).abortTransfer()
            cleanup()
        }
        private fun cleanup() { runCatching { shell.execute(cleanupScript) } }
    }

    private fun fingerprint(path: String) = shell.execute(fingerprintCommand(path), 1024).toString(Charsets.UTF_8).trimEnd('\n')
    private fun fingerprintCommand(path: String): String = fingerprintOperand(q(path))
    private fun fingerprintOperand(p: String): String {
        // Hash stdin so GNU sha256sum never escapes a newline-bearing filename in its output.
        return regular(p) + "stat -c '%d:%i:%f:%u:%g:%h:%s:%Y:%Z' -- $p || exit 1; " +
            "context=\$(ls -Zd -- $p 2>/dev/null); printf '%s\\n' \"\${context%% *}\"; sha256sum < $p | cut -d ' ' -f 1"
    }
    private fun info(path: String): FileItem = parse(shell.execute("printf '%s\\0' ${q(path)}; stat -c '%f %s %Y %u:%g' -- ${q(path)}")).single()
    private fun parse(bytes: ByteArray): List<FileItem> {
        val result = mutableListOf<FileItem>()
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: throw IOException("Invalid root listing")
            val path = path(decode(bytes.copyOfRange(offset, end)))
            val line = (end + 1 until bytes.size).firstOrNull { bytes[it] == 10.toByte() } ?: throw IOException("Invalid root metadata")
            val fields = bytes.copyOfRange(end + 1, line).toString(Charsets.UTF_8).split(' ')
            val mode = fields[0].toInt(16)
            result.add(FileItem(File(path).name, path, mode and 0xf000 == 0x4000, fields[1].toLong(), Date(fields[2].toLong() * 1000), File(path).name.startsWith('.'), fields[0], fields[3], FileSource.ROOT))
            if (result.size > 20_000) throw IOException("Folder exceeds the root listing limit (20,000 entries)")
            offset = line + 1
        }
        return result
    }
    private fun moveNew(source: String, target: String) {
        shell.execute(noTarget(target) + "mv -nT -- ${q(source)} ${q(target)}; [ ! -e ${q(source)} ] && [ ! -L ${q(source)} ] || { echo 'Move failed; original retained' >&2; exit 1; }")
    }
    private fun noTarget(path: String) = "[ ! -e ${q(path)} ] && [ ! -L ${q(path)} ] || { echo 'Destination already exists' >&2; exit 1; }; "
    private fun regular(quoted: String) = "[ -f $quoted ] && [ ! -L $quoted ] || { echo 'Only regular files can be read or edited; symbolic links and special files are unsupported' >&2; exit 1; }; "
    private fun path(value: String): String {
        require(value.startsWith('/') && '\u0000' !in value) { "Root paths must be absolute and contain no NUL" }
        return File(value).toPath().normalize().toString()
    }
    private fun canonical(value: String): String = shell.execute("readlink -f -- ${q(path(value))}", 65_536)
        .toString(Charsets.UTF_8).removeSuffix("\n").also { if (it.isEmpty()) throw IOException("Cannot resolve root path") }
    private fun mutablePath(value: String) = path(value).also { require(it != "/") { "The root directory cannot be modified" } }
    private fun child(parent: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "Invalid file name" }
        return path("${path(parent).trimEnd('/')}/$name")
    }
    private fun q(value: String) = RootShell.quote(value)
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IOException("Only valid UTF-8 text and filenames are supported in root sessions", error)
    }
    private suspend fun <T> io(action: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(action()) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
    }
    override suspend fun disconnect() = withContext(Dispatchers.IO) { close() }
    override fun close() = shell.close()
    companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
