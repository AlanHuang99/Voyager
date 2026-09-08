package com.voyagerfiles.audio

import android.content.Context
import android.media.RingtoneManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.TransferCancellation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class AudioToneInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "tone-test-${UUID.randomUUID()}").apply { mkdirs() }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun localRingtoneAndContentNotificationPreserveSourcesAndRestoreDefaults() = runBlocking {
        assumeTrue(Settings.System.canWrite(context))
        val source = root.resolve("probe.wav").apply { writeBytes(wave()) }
        val bytes = source.readBytes()
        val installer = AudioToneInstaller(context)
        for (tone in AudioTone.entries) {
            val original = RingtoneManager.getActualDefaultRingtoneUri(context, tone.ringtoneType)
            var installed: android.net.Uri? = null
            try {
                val item = if (tone == AudioTone.RINGTONE) FileItem(source.name, source.path, false, source.length())
                    else FileItem(source.name, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source).toString(), false, source.length(), source = FileSource.SAF)
                installed = installer.install(item, tone)
                assertEquals(installed, RingtoneManager.getActualDefaultRingtoneUri(context, tone.ringtoneType))
                assertArrayEquals(bytes, context.contentResolver.openInputStream(installed)!!.use { it.readBytes() })
                assertArrayEquals(bytes, source.readBytes())
            } finally {
                RingtoneManager.setActualDefaultRingtoneUri(context, tone.ringtoneType, original)
                assertEquals(original, RingtoneManager.getActualDefaultRingtoneUri(context, tone.ringtoneType))
                installed?.let { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test fun malformedAudioAndCancelledCopyLeaveNoMediaOrDefaultChanges() = runBlocking {
        assumeTrue(Settings.System.canWrite(context))
        val before = countTestMedia()
        val original = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        val source = root.resolve("probe-invalid.wav").apply { writeText("not audio") }
        assertTrue(runCatching { AudioToneInstaller(context).install(FileItem(source.name, source.path, false), AudioTone.RINGTONE) }.isFailure)
        source.writeBytes(wave())
        val token = TransferCancellation()
        val failure = withContext(token.contextElement()) {
            runCatching {
                AudioToneInstaller(context).install(FileItem(source.name, source.path, false), AudioTone.RINGTONE,
                    onProgress = { token.cancel() })
            }.exceptionOrNull()
        }
        assertTrue(failure is CancellationException)
        assertEquals(before, countTestMedia())
        assertEquals(original, RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE))
        assertArrayEquals(wave(), source.readBytes())
    }

    @Test fun failureBeforeCommitRemovesOwnedMediaAndKeepsOriginalTone() = runBlocking {
        assumeTrue(Settings.System.canWrite(context))
        val before = countTestMedia()
        val original = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        val source = root.resolve("probe-commit.wav").apply { writeBytes(wave()) }
        val error = runCatching {
            AudioToneInstaller(context).install(FileItem(source.name, source.path, false), AudioTone.NOTIFICATION,
                beforeCommit = { throw CancellationException("cancel before commit") })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(before, countTestMedia())
        assertEquals(original, RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION))
        assertArrayEquals(wave(), source.readBytes())
    }

    @Test fun deniedSettingsPermissionDoesNotReadOrCreateFiles() = runBlocking {
        assumeFalse(Settings.System.canWrite(context))
        val error = runCatching {
            AudioToneInstaller(context).install(FileItem("missing.wav", root.resolve("missing.wav").path, false), AudioTone.RINGTONE)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("modify system settings"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun countTestMedia(): Int = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?", arrayOf("probe%"), null)!!.use { it.count }

    private fun wave(): ByteArray {
        val samples = 1600
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 16000) * 1000).toInt().toShort()) }
        }.array()
    }
}
