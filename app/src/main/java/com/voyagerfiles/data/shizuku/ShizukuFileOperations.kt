package com.voyagerfiles.data.shizuku

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.O_CREAT
import android.system.OsConstants.O_EXCL
import android.system.OsConstants.O_NOFOLLOW
import android.system.OsConstants.O_NONBLOCK
import android.system.OsConstants.O_RDONLY
import android.system.OsConstants.O_WRONLY
import android.system.OsConstants.S_ISDIR
import android.system.OsConstants.S_ISREG
import android.system.StructStat
import com.voyagerfiles.data.repository.RootFileProvider
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * File operations of the Shizuku UserService, which runs as shell or root.
 *
 * Canonical parents come from the supervised shell. Every mutation and file open goes through a
 * pinned directory: the parent is walked from `/` one component at a time without following
 * symbolic links, and later calls address `/proc/self/fd/N/name`. A path component swapped for a link
 * after the walk can't redirect a privileged operation. Byte formats match the shell commands in
 * [RootFileProvider], so listing parsing and fingerprints stay shared.
 */
internal class ShizukuFileOperations(private val serviceUid: Int) {

    fun stat(path: String): ByteArray = ByteArrayOutputStream().also { writeEntry(it, path, Os.lstat(path)) }.toByteArray()

    fun exists(path: String): Boolean = try { Os.lstat(path); true } catch (_: ErrnoException) { false }

    fun mkdir(path: String) = inParent(path) { parent, name -> Os.mkdir(parent.child(name), MODE_0700) }

    fun createFile(path: String) = inParent(path) { parent, name ->
        PinnedFd.open(parent.child(name), O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, MODE_0600).close()
    }

    fun fingerprint(path: String): String = inParent(path) { parent, name ->
        openRegular(parent.child(name), O_RDONLY).use { fingerprintOf(it) }
    }

    /** Opens [path] for reading; the caller owns the returned descriptor. */
    fun openRead(path: String): PinnedFd = inParent(path) { parent, name -> openRegular(parent.child(name), O_RDONLY) }

    /**
     * Creates the staging folder and payload of a replacement, mirroring RootFileProvider's shell staging.
     * [expected] is the fingerprint the original must still have, or null to take the current one.
     */
    fun openWrite(path: String, expected: String?): StagedWrite {
        val (parentPath, name) = split(path)
        val parent = pinDirectory(parentPath)
        val stageName = ".voyager-root-${UUID.randomUUID()}"
        var stage: PinnedFd? = null
        var payload: PinnedFd? = null
        try {
            val (fingerprint, original, context) = openRegular(parent.child(name), O_RDONLY).use { file ->
                Triple(fingerprintOf(file), Os.fstat(file.fd), selinuxContext(file.path))
            }
            if (expected != null && fingerprint != expected) throw IOException(CHANGED)
            if (original.st_nlink != 1L) throw IOException("Cannot replace a hard-linked file")
            Os.mkdir(parent.child(stageName), MODE_0700)
            stage = openDirectory(parent.child(stageName))
            val stageStat = Os.fstat(stage.fd)
            if (stageStat.st_uid != serviceUid) throw IOException("Staging folder changed")
            if (stageStat.st_mode and 0xfff != MODE_0700 && stageStat.st_mode and 0xfff != MODE_2700) {
                throw IOException("Unsafe staging permissions")
            }
            payload = PinnedFd.open(stage.child(PAYLOAD), O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, MODE_0600)
            // Owner before mode: chown clears set-id bits.
            Os.fchown(payload.fd, original.st_uid, original.st_gid)
            Os.fchmod(payload.fd, original.st_mode and 0xfff)
            if (context != null && ':' in context.text && context.text != selinuxContext(payload.path)?.text) {
                Os.setxattr(payload.path, SELINUX_XATTR, context.raw, 0)
            }
            return StagedWrite(parent, name, fingerprint, stageName, stage, payload)
        } catch (error: Throwable) {
            if (stage != null) {
                if (payload != null) runCatching { Os.remove(stage.child(PAYLOAD)) }
                runCatching { removeStage(parent, stageName, stage) }
            } else {
                // Created but not pinned: a random name, and removing an empty folder or a swapped-in link is harmless.
                runCatching { if (S_ISDIR(Os.lstat(parent.child(stageName)).st_mode)) Os.remove(parent.child(stageName)) }
            }
            payload?.close()
            stage?.close()
            parent.close()
            throw error
        }
    }

    /** A replacement in progress. [payload] receives the bytes; [commit] renames it over the original. */
    inner class StagedWrite internal constructor(
        private val parent: PinnedFd,
        private val name: String,
        private val expected: String,
        private val stageName: String,
        private val stage: PinnedFd,
        val payload: PinnedFd,
    ) {
        private var renamed = false

        fun commit() {
            Os.fsync(payload.fd)
            val original = openRegular(parent.child(name), O_RDONLY).use { file ->
                if (fingerprintOf(file) != expected) throw IOException(CHANGED)
                Os.fstat(file.fd)
            }
            if (original.st_nlink != 1L) throw IOException("Cannot replace a hard-linked file")
            val written = Os.fstat(payload.fd)
            val linked = try { Os.lstat(stage.child(PAYLOAD)) } catch (error: ErrnoException) { null }
            if (linked == null || !S_ISREG(linked.st_mode) || !sameFile(linked, written) || written.st_nlink != 1L) {
                throw IOException("Staging file changed; original retained")
            }
            if (written.st_mode != original.st_mode || written.st_uid != original.st_uid || written.st_gid != original.st_gid) {
                throw IOException("Cannot preserve file ownership or permissions; original retained")
            }
            Os.rename(stage.child(PAYLOAD), parent.child(name))
            renamed = true
            syncDirectory(parent)
            runCatching { removeStage(parent, stageName, stage) }
        }

        /** Removes what the write left behind. Never touches the target path. */
        fun discard() {
            if (!renamed) runCatching { Os.remove(stage.child(PAYLOAD)) }
            runCatching { removeStage(parent, stageName, stage) }
        }

        /** Only once nothing can still use [payload]: a closed descriptor number can be reused. */
        fun close() {
            payload.close()
            stage.close()
            parent.close()
        }
    }

    private fun removeStage(parent: PinnedFd, stageName: String, stage: PinnedFd) {
        val linked = Os.lstat(parent.child(stageName))
        if (S_ISDIR(linked.st_mode) && sameFile(linked, Os.fstat(stage.fd))) Os.remove(parent.child(stageName))
    }

    private fun syncDirectory(directory: PinnedFd) {
        // The pin is an O_PATH descriptor, which can't be synced. Some file systems refuse directory syncs.
        runCatching {
            val readable = PinnedFd.open(directory.path, O_RDONLY or O_NONBLOCK)
            try { Os.fsync(readable.fd) } finally { readable.close() }
        }
    }

    private fun <T> inParent(path: String, action: (PinnedFd, String) -> T): T {
        val (parentPath, name) = split(path)
        return pinDirectory(parentPath).use { action(it, name) }
    }

    private fun split(path: String): Pair<String, String> {
        require(path.startsWith('/') && '\u0000' !in path) { "Root paths must be absolute and contain no NUL" }
        val name = path.trimEnd('/').substringAfterLast('/')
        require(name.isNotEmpty() && name != "." && name != "..") { "Invalid file name" }
        return path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } to name
    }

    /** Walks [canonicalPath] from the root; any symbolic link on the way fails instead of being followed. */
    private fun pinDirectory(canonicalPath: String): PinnedFd {
        var current = openDirectory("/")
        try {
            for (part in canonicalPath.split('/')) {
                if (part.isEmpty()) continue
                require(part != "." && part != "..") { "Parent path must be canonical" }
                val next = openDirectory(current.child(part))
                current.close()
                current = next
            }
            return current
        } catch (error: Throwable) {
            current.close()
            throw error
        }
    }

    private fun openDirectory(path: String): PinnedFd {
        // O_PATH needs only search permission and opens nothing, so a swapped-in device or FIFO is inert.
        val directory = PinnedFd.open(path, O_PATH or O_NOFOLLOW)
        try {
            val stat = Os.fstat(directory.fd)
            if (!S_ISDIR(stat.st_mode)) throw IOException("Folder changed or is a symbolic link")
            return directory
        } catch (error: Throwable) {
            directory.close()
            throw error
        }
    }

    private fun openRegular(path: String, flags: Int): PinnedFd {
        val before = Os.lstat(path)
        if (!S_ISREG(before.st_mode)) throw IOException(REGULAR_ONLY)
        // O_PATH opens nothing, so a FIFO or device swapped in after the check can't block or react.
        // Reopening the verified descriptor through /proc then can't reach any other file.
        return PinnedFd.open(path, O_PATH or O_NOFOLLOW).use { pinned ->
            val verified = Os.fstat(pinned.fd)
            if (!S_ISREG(verified.st_mode) || !sameFile(before, verified)) throw IOException(REGULAR_ONLY)
            val file = PinnedFd.open(pinned.path, flags)
            try {
                if (!sameFile(verified, Os.fstat(file.fd))) throw IOException(REGULAR_ONLY)
                file
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }
    }

    private fun fingerprintOf(file: PinnedFd): String {
        val stat = Os.fstat(file.fd)
        val context = selinuxContext(file.path)?.text ?: "?"
        val hash = sha256(file, stat.st_size)
        return "${stat.st_dev}:${stat.st_ino}:${Integer.toHexString(stat.st_mode)}:${stat.st_uid}:${stat.st_gid}:" +
            "${stat.st_nlink}:${stat.st_size}:${stat.st_mtime}:${stat.st_ctime}\n$context\n$hash"
    }

    private fun sha256(file: PinnedFd, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var offset = 0L
        while (true) {
            val count = Os.pread(file.fd, buffer, 0, buffer.size, offset)
            if (count <= 0) break
            digest.update(buffer, 0, count)
            offset += count
            if (offset > size + buffer.size) break // Growing while hashed; the stat fields already differ.
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class Context(val raw: ByteArray, val text: String)

    private fun selinuxContext(path: String): Context? = try {
        val raw = Os.getxattr(path, SELINUX_XATTR)
        Context(raw, String(raw, Charsets.UTF_8).trimEnd('\u0000'))
    } catch (_: ErrnoException) { null }

    private fun writeEntry(output: ByteArrayOutputStream, path: String, stat: StructStat) {
        output.write(path.toByteArray(Charsets.UTF_8))
        output.write(0)
        output.write("${Integer.toHexString(stat.st_mode)} ${stat.st_size} ${stat.st_mtime} ${stat.st_uid}:${stat.st_gid}\n".toByteArray(Charsets.UTF_8))
    }

    private fun sameFile(first: StructStat, second: StructStat) = first.st_dev == second.st_dev && first.st_ino == second.st_ino

    companion object {
        /** Linux's generic O_PATH, used by every Android ABI; android.system.OsConstants doesn't expose it. */
        const val O_PATH = 0x200000
        /** Same for O_CLOEXEC; OsConstants only has it from API 27. */
        const val O_CLOEXEC = 0x80000
        private val MODE_0600 = "600".toInt(8)
        private val MODE_0700 = "700".toInt(8)
        private val MODE_2700 = "2700".toInt(8)
        private const val PAYLOAD = "payload"
        private const val SELINUX_XATTR = "security.selinux"
        private const val CHANGED = "File changed since opening. Original preserved; reopen before saving."
        private const val REGULAR_ONLY = "Only regular files can be read or edited; symbolic links and special files are unsupported"
    }
}

/** A descriptor the service owns, addressable as `/proc/self/fd/N` while open. */
internal class PinnedFd private constructor(private val descriptor: ParcelFileDescriptor) : Closeable {
    val fd: java.io.FileDescriptor get() = descriptor.fileDescriptor
    val path: String get() = "/proc/self/fd/${descriptor.fd}"
    fun child(name: String) = "$path/$name"
    override fun close() = descriptor.close()

    companion object {
        fun open(path: String, flags: Int, mode: Int = 0): PinnedFd {
            val raw = Os.open(path, flags or ShizukuFileOperations.O_CLOEXEC, mode)
            try { return PinnedFd(ParcelFileDescriptor.dup(raw)) } finally { Os.close(raw) }
        }
    }
}
