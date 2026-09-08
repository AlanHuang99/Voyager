package com.voyagerfiles.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.voyagerfiles.R
import com.voyagerfiles.app.MainActivity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FolderShortcuts {
    const val ACTION_OPEN_FOLDER = "com.voyagerfiles.action.OPEN_FOLDER"
    private const val EXTRA_PATH = "folder_path"

    fun requestedPath(intent: Intent): String? =
        if (intent.action == ACTION_OPEN_FOLDER) intent.getStringExtra(EXTRA_PATH) else null

    fun launchIntent(context: Context, path: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_FOLDER)
        .putExtra(EXTRA_PATH, path)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun resolve(context: Context, path: String): File = FolderShortcutTarget.resolve(
        path,
        FileUtils.getStorageVolumes(context).mapNotNull { it.path?.let(::File) },
    )

    suspend fun requestPin(context: Context, path: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val folder = withContext(Dispatchers.IO) { resolve(context, path) }
        val id = MessageDigest.getInstance("SHA-256").digest(folder.path.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val shortcut = ShortcutInfoCompat.Builder(context, "folder-$id")
            .setShortLabel(folder.name.ifEmpty { context.getString(R.string.browser_root) })
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_folder_shortcut))
            .setIntent(launchIntent(context, folder.path))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }
}
