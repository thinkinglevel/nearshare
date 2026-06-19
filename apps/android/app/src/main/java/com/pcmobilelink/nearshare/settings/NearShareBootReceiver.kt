package com.pcmobilelink.nearshare.settings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pcmobilelink.nearshare.MainActivity
import com.pcmobilelink.nearshare.R
import com.pcmobilelink.nearshare.receiver.AndroidReceiveForegroundService

class NearShareBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val settings = ReceiveSettingsStore(context).load()
        if (settings.bootRestoreAction() != BootRestoreAction.StartReceiver) {
            return
        }

        runCatching {
            AndroidReceiveForegroundService.startAlwaysOn(context)
        }.onFailure {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(manager)
            manager.notify(BOOT_RESUME_NOTIFICATION_ID, buildResumeNotification(context))
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Receive readiness",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Shows when Android needs a tap to resume NearShare receiving after restart."
            },
        )
    }

    private fun buildResumeNotification(context: Context): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Resume NearShare receiving")
            .setContentText(BootRestoreAction.ShowResumeNotification.userMessage)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    private companion object {
        private const val CHANNEL_ID = "nearshare_receive_readiness"
        private const val BOOT_RESUME_NOTIFICATION_ID = 1301
    }
}
