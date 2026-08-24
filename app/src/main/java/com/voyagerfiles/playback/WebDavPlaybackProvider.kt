package com.voyagerfiles.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException

class WebDavPlaybackProvider : ContentProvider() {
    private val threadLock = Any()
    private var callbackThread: HandlerThread? = null

    override fun onCreate(): Boolean {
        activeProvider = this
        ensureCallbackThread()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val entry = lookup(uri) ?: return null
        val columns = (projection?.toList() ?: SUPPORTED_COLUMNS).filter { it in SUPPORTED_COLUMNS }
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> entry.displayName
                        OpenableColumns.SIZE -> entry.size
                        else -> null
                    }
                },
            )
        }
    }

    override fun getType(uri: Uri): String? = lookup(uri)?.mimeType

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("WebDAV playback is read-only")
        val token = token(uri) ?: throw FileNotFoundException("Unknown WebDAV playback URI")
        val entry = currentStore().lookup(token) ?: throw FileNotFoundException("Expired WebDAV playback URI")
        val storageManager = requireNotNull(context).getSystemService(StorageManager::class.java)
        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            PlaybackProxyCallback(token, entry),
            ensureCallbackThread(),
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("WebDAV playback provider does not support inserts")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("WebDAV playback provider does not support deletes")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("WebDAV playback provider does not support updates")

    private fun lookup(uri: Uri): PlaybackEntry? = token(uri)?.let { currentStore().lookup(it) }

    private fun token(uri: Uri): String? = uri.pathSegments.singleOrNull()

    private fun ensureCallbackThread(): Handler = synchronized(threadLock) {
        val existing = callbackThread
        val thread = if (existing?.isAlive == true) {
            existing
        } else {
            HandlerThread("webdav-playback").also {
                it.start()
                callbackThread = it
            }
        }
        Handler(thread.looper)
    }

    private fun restartCallbackThreadForTest() {
        synchronized(threadLock) {
            callbackThread?.quitSafely()
            callbackThread = null
        }
        ensureCallbackThread()
    }

    private class PlaybackProxyCallback(
        private val token: String,
        private val entry: PlaybackEntry,
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = entry.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (offset < 0 || size < 0) throw ErrnoException("onRead", OsConstants.EINVAL)
            if (size == 0 || data.isEmpty() || offset >= entry.size) return 0
            val byteCount = minOf(size.toLong(), data.size.toLong(), entry.size - offset).toInt()
            val bytes = entry.source.read(offset, byteCount).getOrElse {
                throw ErrnoException("onRead", OsConstants.EIO)
            }
            if (bytes.size > byteCount) throw ErrnoException("onRead", OsConstants.EIO)
            bytes.copyInto(data, endIndex = bytes.size)
            return bytes.size
        }

        override fun onRelease() {
            currentStore().remove(token)
        }
    }

    companion object {
        private val storeLock = Any()
        private var tokenStore = PlaybackTokenStore()

        @Volatile
        private var activeProvider: WebDavPlaybackProvider? = null

        private val SUPPORTED_COLUMNS = listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        internal fun register(context: Context, entry: PlaybackEntry): Uri {
            val token = currentStore().register(entry)
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.webdavplayback")
                .appendPath(token)
                .build()
        }

        internal fun revoke(uri: Uri) {
            uri.pathSegments.singleOrNull()?.let { currentStore().remove(it) }
        }

        internal fun setStoreForTest(store: PlaybackTokenStore) {
            synchronized(storeLock) {
                tokenStore.clear()
                tokenStore = store
            }
        }

        internal fun resetForTest() {
            synchronized(storeLock) {
                tokenStore.clear()
                tokenStore = PlaybackTokenStore()
            }
            activeProvider?.restartCallbackThreadForTest()
        }

        private fun currentStore(): PlaybackTokenStore = synchronized(storeLock) { tokenStore }
    }
}
