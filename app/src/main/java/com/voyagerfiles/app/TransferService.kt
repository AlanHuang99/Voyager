package com.voyagerfiles.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.voyagerfiles.R
import com.voyagerfiles.ui.text.resolve
import com.voyagerfiles.viewmodel.OperationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as VoyagerApp).transfers
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(controller.state.value as? OperationState.Running),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voyager:transfer")
            .apply { acquire(MAX_WAKE_MILLIS) }
        scope.launch {
            controller.state.collect { state ->
                if (state is OperationState.Running) {
                    if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@TransferService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    }
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (latestStartId != 0) stopSelfResult(latestStartId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_CANCEL) controller.cancel(intent.getLongExtra(EXTRA_OPERATION_ID, -1))
        val operation = controller.state.value as? OperationState.Running
        if (operation == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        } else {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(operation),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(operation: OperationState.Running?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_OPERATION_ID, operation?.id ?: -1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val progress = operation?.progress
        val title = if (operation?.cancelling == true) getString(R.string.transfer_cancelling)
            else progress?.label?.resolve(resources) ?: getString(R.string.transfer_channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(progress?.detailText)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(1000, ((progress?.fraction ?: 0f) * 1000).toInt(), progress?.fraction == null)
            .apply {
                if (operation?.cancelling != true) addAction(0, getString(R.string.action_cancel), cancel)
            }
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "file_transfers"
        const val NOTIFICATION_ID = 41
        const val EXTRA_OPERATION_ID = "operation_id"
        const val ACTION_CANCEL = "com.voyagerfiles.action.CANCEL_TRANSFER"
        const val MAX_WAKE_MILLIS = 6 * 60 * 60 * 1000L
    }
}
