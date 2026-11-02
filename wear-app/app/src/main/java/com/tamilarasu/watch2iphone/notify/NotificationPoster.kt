package com.tamilarasu.watch2iphone.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tamilarasu.watch2iphone.AppPermissions
import com.tamilarasu.watch2iphone.BleLog
import com.tamilarasu.watch2iphone.R
import com.tamilarasu.watch2iphone.ancs.ActionId
import com.tamilarasu.watch2iphone.ancs.AncsNotification

/** Turns iPhone notifications into native Wear OS notifications and wires up their actions. */
class NotificationPoster(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DEFAULT, "iPhone notifications", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "iPhone notifications (silent)", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "iPhone calls", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    @SuppressLint("MissingPermission")
    fun post(n: AncsNotification) {
        if (!AppPermissions.isGranted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            BleLog.svc("POST_NOTIFICATIONS not granted; dropping uid=${n.uid}")
            return
        }
        val channel = when {
            n.isIncomingCall -> CHANNEL_CALLS
            n.isSilent -> CHANNEL_SILENT
            else -> CHANNEL_DEFAULT
        }
        val body = listOf(n.subtitle, n.message).filter { it.isNotBlank() }.joinToString("\n")

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(n.title.ifBlank { n.appName })
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(n.appName)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

        if (n.isSilent) builder.setSilent(true)

        if (n.isIncomingCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
            if (n.hasPositiveAction) {
                builder.addAction(0, n.positiveLabel.ifBlank { "Accept" }, actionIntent(n, ActionId.POSITIVE))
            }
            if (n.hasNegativeAction) {
                builder.addAction(0, n.negativeLabel.ifBlank { "Decline" }, actionIntent(n, ActionId.NEGATIVE))
            }
        } else if (n.hasNegativeAction) {
            // Swiping the notification away on the watch, or tapping the button, clears it on the iPhone as well.
            builder.setDeleteIntent(actionIntent(n, ActionId.NEGATIVE))
            builder.addAction(0, n.negativeLabel.ifBlank { "Dismiss" }, actionIntent(n, ActionId.NEGATIVE))
        }

        BleLog.svc(
            "posting uid=${n.uid} channel=$channel app=${n.appName} " +
                "actions(positive=${n.hasPositiveAction}, negative=${n.hasNegativeAction})",
        )
        manager.notify(TAG, n.uid.toInt(), builder.build())
    }

    fun cancel(uid: Long) {
        BleLog.svc("cancelling uid=$uid")
        manager.cancel(TAG, uid.toInt())
    }

    private fun actionIntent(n: AncsNotification, actionId: Int): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_PERFORM
            putExtra(ActionReceiver.EXTRA_SESSION, n.sessionId)
            putExtra(ActionReceiver.EXTRA_UID, n.uid)
            putExtra(ActionReceiver.EXTRA_ACTION, actionId)
        }
        return PendingIntent.getBroadcast(
            context,
            n.uid.toInt() * 2 + actionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "ancs"
        const val CHANNEL_DEFAULT = "ancs_default"
        const val CHANNEL_SILENT = "ancs_silent"
        const val CHANNEL_CALLS = "ancs_calls"
    }
}
