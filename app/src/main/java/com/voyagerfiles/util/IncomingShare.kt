package com.voyagerfiles.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.core.content.IntentCompat

object IncomingShare {
    // shortcut: grants last for the receiving task; forward grants to the service if copying must outlive task removal.
    fun files(context: Context, intent: Intent): List<Uri> {
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return emptyList()
        }.ifEmpty {
            val clip = intent.clipData
            (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        }.distinct()
        require(streams.isNotEmpty()) { "No shared files" }
        // An external intent must not use Voyager's own access to read private files.
        require(streams.all { uri ->
            uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
                context.packageManager.resolveContentProvider(uri.authority!!, 0)?.applicationInfo?.uid != Process.myUid()
        }) { "Unsupported shared file URI" }
        return streams
    }
}
