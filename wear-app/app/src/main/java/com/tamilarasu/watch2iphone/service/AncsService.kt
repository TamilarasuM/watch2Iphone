package com.tamilarasu.watch2iphone.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.tamilarasu.watch2iphone.BleLog
import com.tamilarasu.watch2iphone.LinkPhase
import com.tamilarasu.watch2iphone.R
import com.tamilarasu.watch2iphone.UiState

/**
 * Foreground service (type connectedDevice) that keeps the iPhone connection alive with the screen off.
 * START_STICKY: if the system kills the process the service is restarted and resumes the saved device.
 */
class AncsService : Service() {

    override fun onCreate() {
        super.onCreate()
        BleLog.svc("service onCreate")
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Connection status", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(UiState(LinkPhase.IDLE, "starting")),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        controller = AncsController(this) { state -> updateNotification(state) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BleLog.svc("service onStartCommand action=${intent?.action}")
        val active = controller ?: return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START_SCAN -> active.startScan()
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_ADDRESS)?.let { active.connectTo(it) }
            ACTION_START_ADVERTISE -> active.startAdvertising()
            ACTION_RESUME -> active.resumeSaved()
            ACTION_FORGET -> active.forgetSaved()
            ACTION_STOP -> {
                active.stop()
                stopSelf()
            }
            null -> active.resumeSaved() // restarted by the system after the process was killed
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BleLog.svc("service onDestroy")
        controller?.release()
        controller = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun updateNotification(state: UiState) {
        if (!com.tamilarasu.watch2iphone.AppPermissions.isGranted(this, android.Manifest.permission.POST_NOTIFICATIONS)) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: UiState): Notification =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Watch2iPhone")
            .setContentText("${state.phase.name.lowercase().replaceFirstChar { it.uppercase() }}: ${state.detail}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    companion object {
        /** Shared with [com.tamilarasu.watch2iphone.notify.ActionReceiver]; only valid while the service runs. */
        @Volatile
        var controller: AncsController? = null

        private const val CHANNEL_SERVICE = "ancs_service"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START_SCAN = "com.tamilarasu.watch2iphone.START_SCAN"
        const val ACTION_CONNECT = "com.tamilarasu.watch2iphone.CONNECT"
        const val ACTION_START_ADVERTISE = "com.tamilarasu.watch2iphone.START_ADVERTISE"
        const val ACTION_RESUME = "com.tamilarasu.watch2iphone.RESUME"
        const val ACTION_FORGET = "com.tamilarasu.watch2iphone.FORGET"
        const val ACTION_STOP = "com.tamilarasu.watch2iphone.STOP"
        const val EXTRA_ADDRESS = "address"

        fun intent(context: Context, action: String, address: String? = null): Intent =
            Intent(context, AncsService::class.java).setAction(action).apply {
                if (address != null) putExtra(EXTRA_ADDRESS, address)
            }
    }
}
