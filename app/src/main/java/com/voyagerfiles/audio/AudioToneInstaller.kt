package com.voyagerfiles.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.ForwardingOutputStream
import com.voyagerfiles.data.repository.StreamTransfer
import com.voyagerfiles.data.repository.StreamTransferProgress
import com.voyagerfiles.data.repository.TransferCancellation
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AudioTone(val ringtoneType: Int, val directory: String) {
    RINGTONE(RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES),
    NOTIFICATION(RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS),
}

/** Copies validated audio to shared media so its lifetime does not depend on the original document. */
class AudioToneInstaller(private val context: Context) {
    suspend fun install(
        file: FileItem,
        tone: AudioTone,
        beforeCommit: suspend () -> Unit = { TransferCancellation.check() },
        onProgress: (StreamTransferProgress) -> Unit = {},
    ): Uri = withContext(Dispatchers.IO) {
        check(Settings.System.canWrite(context)) { "Allow Voyager to modify system settings, then try again." }
        require(isSupported(file)) { "Select one local audio file or audio document." }
        val resolver = context.contentResolver
        val staged = File.createTempFile("tone-", ".audio", context.cacheDir)
        var inserted: Uri? = null
        var legacyFile: File? = null
        var committed = false
        val original = RingtoneManager.getActualDefaultRingtoneUri(context, tone.ringtoneType)
        try {
            val input = when (file.source) {
                FileSource.LOCAL -> File(file.path).inputStream()
                FileSource.SAF -> resolver.openInputStream(Uri.parse(file.path))
                    ?: throw IOException("The audio document is no longer accessible.")
                else -> error("Unsupported audio source")
            }
            input.use { source ->
                staged.outputStream().use { target ->
                    val bounded = object : ForwardingOutputStream(target) {
                        private var bytes = 0L
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            require(bytes + length <= MAX_BYTES) { "Choose an audio file smaller than 128 MB." }
                            super.write(buffer, offset, length)
                            bytes += length
                        }
                    }
                    StreamTransfer.copy(source, bounded, file.path, file.size.takeIf { it > 0 }, onProgress = onProgress)
                }
            }
            validateAudio(staged)
            TransferCancellation.check()
            val safeExtension = file.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "audio"
            val displayName = "${file.name.substringBeforeLast('.').take(80).replace(Regex("[/\\\\\\p{Cntrl}]"), "_")}-${java.util.UUID.randomUUID()}.$safeExtension"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.TITLE, file.name.substringBeforeLast('.'))
                put(MediaStore.Audio.Media.MIME_TYPE, file.mimeType)
                put(MediaStore.Audio.Media.IS_RINGTONE, tone == AudioTone.RINGTONE)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, tone == AudioTone.NOTIFICATION)
                put(MediaStore.Audio.Media.IS_MUSIC, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${tone.directory}/Voyager")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val directory = File(Environment.getExternalStoragePublicDirectory(tone.directory), "Voyager")
                    check(directory.isDirectory || directory.mkdirs()) { "The tone folder is not writable." }
                    legacyFile = File(directory, displayName).also { check(it.createNewFile()) }
                    @Suppress("DEPRECATION")
                    put(MediaStore.Audio.Media.DATA, checkNotNull(legacyFile).absolutePath)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the shared audio file.")
            inserted = uri
            resolver.openOutputStream(uri, "w")?.use { target ->
                staged.inputStream().use { source -> StreamTransfer.copy(source, target, file.path, staged.length()) }
            } ?: throw IOException("Could not write the shared audio file.")
            beforeCommit()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
            }
            RingtoneManager.setActualDefaultRingtoneUri(context, tone.ringtoneType, uri)
            check(RingtoneManager.getActualDefaultRingtoneUri(context, tone.ringtoneType) == uri) {
                "Android did not accept this audio file as a system tone."
            }
            committed = true
            uri
        } finally {
            staged.delete()
            if (!committed) {
                // A failed setter may have changed the default before reporting an error.
                val uri = inserted
                if (uri != null && RingtoneManager.getActualDefaultRingtoneUri(context, tone.ringtoneType) == uri) {
                    RingtoneManager.setActualDefaultRingtoneUri(context, tone.ringtoneType, original)
                }
                if (uri != null) resolver.delete(uri, null, null)
                legacyFile?.delete()
            }
        }
    }

    private fun validateAudio(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" &&
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                "This file does not contain supported audio."
            }
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val MAX_BYTES = 128L * 1024 * 1024
        fun isSupported(file: FileItem): Boolean = !file.isDirectory && file.isAudio &&
            (file.source == FileSource.LOCAL || file.source == FileSource.SAF)
    }
}
