package com.voyagerfiles.playback

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal object PlaybackLifetimeKeeper {
    val store = PlaybackLifetimeStore()
    const val EXTRA_PREPARATION_ID = "preparation_id"

    suspend fun prepare(
        context: Context,
        entry: PlaybackEntry,
        token: String,
        startupTimeoutMillis: Long,
        start: (Context, Intent) -> Unit,
        finish: () -> Unit,
    ): PlaybackLifetimeStore.Preparation {
        val pending = store.prepare(entry, token, finish)
        try {
            withContext(Dispatchers.Main.immediate) {
                start(context, Intent(context, serviceClass(pending.kind)).putExtra(EXTRA_PREPARATION_ID, pending.id))
                withTimeout(startupTimeoutMillis) { pending.ready.await() }
            }
            return pending
        } catch (error: Throwable) {
            store.cancel(pending.id)
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (pending.kind !in store.kinds.value) runCatching {
                    context.stopService(Intent(context, serviceClass(pending.kind)))
                }
            }
            throw error
        }
    }

    fun serviceClass(kind: PlaybackServiceKind): Class<out WebDavPlaybackService> = when (kind) {
        PlaybackServiceKind.MEDIA -> WebDavMediaPlaybackService::class.java
        PlaybackServiceKind.DATA -> WebDavFileServingService::class.java
    }
}
