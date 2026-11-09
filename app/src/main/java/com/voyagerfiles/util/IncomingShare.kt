package com.voyagerfiles.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.IntentCompat

object IncomingShare {
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
        val ownedAuthorities = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers.orEmpty().flatMap { it.authority.orEmpty().split(';') }.toSet()
        // User-qualified authorities cannot bypass the check for Voyager's private providers.
        require(streams.all { uri ->
            val authority = uri.authority.orEmpty()
            uri.scheme == "content" && authority.isNotBlank() && '@' !in authority && uri.port == -1 &&
                ownedAuthorities.none { it.equals(authority, ignoreCase = true) } &&
                context.packageManager.resolveContentProvider(authority, 0)?.applicationInfo?.uid != Process.myUid()
        }) { "Unsupported shared file URI" }
        return streams
    }
}
