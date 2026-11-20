package com.enzimos.remote

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Matching notification actions ask the live activity to drain input before releasing media. */
class StreamService : Service() {
    private var activeToken: String? = null

    companion object {
        const val ACTION_STOP = "com.enzimos.remote.STOP_STREAM"
        private const val EXTRA_HOST_NAME = "host_name"
        private const val EXTRA_CONTROL_TOKEN = "control_token"

        fun createStartIntent(context: Context, hostName: String, controlToken: String): Intent =
            Intent(context, StreamService::class.java)
                .putExtra(EXTRA_HOST_NAME, hostName)
                .putExtra(EXTRA_CONTROL_TOKEN, controlToken)

        fun createStopIntent(context: Context, controlToken: String): Intent =
            Intent(context, StreamService::class.java).apply {
                action = ACTION_STOP
                data = notificationUri(controlToken, "stop")
                putExtra(EXTRA_CONTROL_TOKEN, controlToken)
            }

        private fun notificationUri(token: String, action: String): Uri =
            Uri.Builder().scheme("enzremote").authority("notification")
                .appendPath(token).appendPath(action).build()
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_CONTROL_TOKEN)
        if (intent?.action == ACTION_STOP) {
            if (!StreamNotificationControl.requestStop(token)) stopIfUnowned(startId)
            return START_NOT_STICKY
        }
        if (!StreamNotificationControl.owns(token)) {
            stopIfUnowned(startId)
            return START_NOT_STICKY
        }
        activeToken = token
        val hostName = intent?.getStringExtra(EXTRA_HOST_NAME).orEmpty()
        val notification = buildNotification(hostName, requireNotNull(token))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NotificationHelper.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun stopIfUnowned(startId: Int) {
        if (!StreamNotificationControl.owns(activeToken) && stopSelfResult(startId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(hostName: String, token: String): Notification {
        val contentIntent = PendingIntent.getActivity(this, 0,
            Intent(this, StreamActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = notificationUri(token, "open")
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = PendingIntent.getService(this, 0, createStopIntent(this, token),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("Streaming to $hostName")
            .setContentText("Tap to return to the stream")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stopIntent).build())
            .build()
    }
}
