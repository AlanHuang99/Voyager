package com.voyagerfiles.playback

import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

class PreparedWebDavPlayback internal constructor(val uri: Uri, private val cancelLaunch: () -> Unit) : AutoCloseable {
    private val cancelled = AtomicBoolean(false)
    override fun close() { if (cancelled.compareAndSet(false, true)) cancelLaunch() }
}
