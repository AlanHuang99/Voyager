package com.voyagerfiles.data.remote.sftp

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Shared, atomic persistence for the app's host/port pins. Disk is authoritative for every operation. */
class SftpKnownHosts(private val file: File) : HostKeyRepository {
    data class Fingerprint(val algorithm: String, val sha256: String)

    fun fingerprints(host: String, port: Int): List<Fingerprint> =
        getHostKey(endpoint(host, port), null).map { key ->
            Fingerprint(
                algorithm = key.type,
                sha256 = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key.key)),
                ),
            )
        }.distinct()

    /** Call only after the user confirms resetting trust for the displayed endpoint. */
    fun forget(host: String, port: Int) = remove(endpoint(host, port), null, null)

    override fun check(host: String, key: ByteArray): Int = synchronized(diskLock) {
        val saved = matchingEntries(host, null).mapNotNull { it.key }
        when {
            saved.any { MessageDigest.isEqual(Base64.getDecoder().decode(it.key), key) } -> HostKeyRepository.OK
            saved.isEmpty() -> HostKeyRepository.NOT_INCLUDED
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey, userinfo: UserInfo?) = synchronized(diskLock) {
        // JSch calls check and add separately. Recheck under the write lock so simultaneous first connections cannot save conflicting keys or overwrite another endpoint's newly saved pin.
        when (check(hostkey.host, Base64.getDecoder().decode(hostkey.key))) {
            HostKeyRepository.OK -> return@synchronized
            HostKeyRepository.CHANGED -> error("SFTP host key changed. Verify the server before reconnecting.")
        }
        val previous = readText()
        val marker = hostkey.marker.takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
        val comment = hostkey.comment?.let { " $it" }.orEmpty()
        writeAtomically(previous + (if (previous.isNotEmpty() && !previous.endsWith('\n')) "\n" else "") +
            "$marker${hostkey.host} ${hostkey.type} ${hostkey.key}$comment\n")
    }

    override fun remove(host: String, type: String?) = remove(host, type, null)

    override fun remove(host: String, type: String?, key: ByteArray?) = synchronized(diskLock) {
        val original = readText()
        var changed = false
        val remaining = original.splitToSequence('\n').map { line ->
            val entry = parse(line)
            val saved = entry.key
            if (saved == null || (type != null && saved.type != type) ||
                (key != null && !MessageDigest.isEqual(Base64.getDecoder().decode(saved.key), key))) {
                line
            } else {
                val aliases = saved.host.split(',')
                val retained = aliases.filterNot { matches(it, host) }
                if (retained.size == aliases.size) line else {
                    changed = true
                    if (retained.isEmpty()) null else {
                        val match = checkNotNull(HOST_FIELD.find(line))
                        line.replaceRange(match.groups[2]!!.range, retained.joinToString(","))
                    }
                }
            }
        }.filterNotNull().joinToString("\n")
        if (changed) writeAtomically(remaining)
    }

    override fun getKnownHostsRepositoryID(): String = file.absolutePath

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = synchronized(diskLock) {
        matchingEntries(host, type).mapNotNull { it.key }.toTypedArray()
    }

    private fun matchingEntries(host: String?, type: String?) = readText().lineSequence()
        .map(::parse).filter { entry ->
            entry.key?.let { key ->
                (type == null || key.type == type) &&
                    (host == null || key.host.split(',').any { matches(it, host) })
            } == true
        }.toList()

    private fun parse(line: String): Entry {
        val jsch = JSch()
        jsch.setKnownHosts((line + "\n").byteInputStream())
        return Entry(jsch.hostKeyRepository.hostKey.firstOrNull())
    }

    private fun readText(): String = if (file.exists()) file.readText() else ""

    private fun writeAtomically(text: String) {
        val parent = checkNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create the SFTP security directory" }
        val pending = Files.createTempFile(parent.toPath(), ".known_hosts-", ".tmp")
        try {
            FileOutputStream(pending.toFile()).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(pending, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    private fun matches(saved: String, requested: String): Boolean {
        if (!saved.startsWith("|1|")) return canonical(saved).equals(canonical(requested), ignoreCase = true)
        val fields = saved.split('|')
        if (fields.size != 4) return false
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(fields[2]), "HmacSHA1"))
        return MessageDigest.isEqual(
            Base64.getDecoder().decode(fields[3]),
            mac.doFinal(requested.toByteArray(Charsets.UTF_8)),
        )
    }

    private data class Entry(val key: HostKey?)

    private companion object {
        // All app SFTP connections and the editor run in one process. Never hold this lock for network I/O.
        val diskLock = Any()
        val HOST_FIELD = Regex("^(\\s*(?:@\\S+\\s+)?)(\\S+)")

        fun endpoint(host: String, port: Int): String {
            require(host.isNotBlank() && port in 1..65535)
            // JSch preserves supplied IPv6 brackets, then adds another pair for a nondefault port. Keep the same identity for existing pins.
            val address = host.trim()
            return if (port == 22) address else "[$address]:$port"
        }

        fun canonical(host: String): String =
            if (host.startsWith('[') && host.endsWith("]:22")) host.substring(1, host.length - 4) else host
    }
}
