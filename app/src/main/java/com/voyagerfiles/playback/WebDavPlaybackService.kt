package com.voyagerfiles.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.voyagerfiles.R
import com.voyagerfiles.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WebDavMediaPlaybackService : WebDavPlaybackService() {
    override val servesMedia = true
}

class WebDavFileServingService : WebDavPlaybackService() {
    override val servesMedia = false
}

abstract class WebDavPlaybackService : Service() {
    protected abstract val servesMedia: Boolean
    private val kind get() = if (servesMedia) PlaybackServiceKind.MEDIA else PlaybackServiceKind.DATA
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var latestStartId = 0
    private var promoted = false

    override fun onCreate() {
        super.onCreate()
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.playback_service_channel), NotificationManager.IMPORTANCE_LOW),
            )
            ServiceCompat.startForeground(
                this, if (servesMedia) 42 else 43, notification(),
                if (Build.VERSION.SDK_INT < 29) 0 else if (servesMedia) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            promoted = true
            scope.launch {
                while (isActive) {
                    delay(1_000)
                    PlaybackLifetimeKeeper.store.sweep()
                    stopIfIdle()
                }
            }
        } catch (error: RuntimeException) {
            PlaybackLifetimeKeeper.store.fail(kind, error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (promoted) PlaybackLifetimeKeeper.store.started(intent?.getLongExtra(PlaybackLifetimeKeeper.EXTRA_PREPARATION_ID, -1) ?: -1, this)
        stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        PlaybackLifetimeKeeper.store.fail(kind, IllegalStateException("Remote file service reached its time limit"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        PlaybackLifetimeKeeper.store.serviceLost(this)
        super.onDestroy()
    }

    private fun stopIfIdle() {
        if (latestStartId != 0 && kind !in PlaybackLifetimeKeeper.store.kinds.value && stopSelfResult(latestStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(if (servesMedia) R.string.playback_service_media else R.string.playback_service_channel))
            .setContentText(getString(R.string.playback_service_description))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private companion object { const val CHANNEL_ID = "remote_file_playback" }
}
