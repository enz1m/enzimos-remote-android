package com.enzimos.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

import androidx.core.app.NotificationCompat

/**
 * Manages notification channels and builds notifications for the
 * streaming foreground service.
 *
 * Android 8+ (API 26) requires notification channels. This helper
 * creates the channel once and provides builder methods for the
 * streaming session notification.
 */
object NotificationHelper {

    /** Channel ID for the streaming service. */
    const val CHANNEL_ID = "enzremote_streaming"

    /** Notification ID for the ongoing stream notification. */
    const val NOTIFICATION_ID = 1

    /** Channel name visible in system settings. */
    const val CHANNEL_NAME = "Remote Streaming"

    /** Channel description. */
    const val CHANNEL_DESCRIPTION = "Active remote desktop streaming session"

    /**
     * Create the streaming notification channel.
     *
     * Safe to call multiple times — Android ignores duplicate creation.
     *
     * @param context Application or service context
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW, // No sound, but visible
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the streaming foreground service notification.
     *
     * @param context Application or service context
     * @param sessionId Current session ID
     * @param host Host address
     * @return Notification for startForeground()
     */
    fun buildStreamingNotification(
        context: Context,
        sessionId: String,
        host: String,
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Streaming: $sessionId")
            .setContentText("Connected to $host")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Build the "reconnecting" notification variant.
     */
    fun buildReconnectingNotification(
        context: Context,
        sessionId: String,
        attempt: Int,
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Reconnecting: $sessionId")
            .setContentText("Attempt $attempt...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Format a notification title for the performance overlay toggle.
     */
    fun formatTitle(sessionId: String, fps: Double): String {
        return if (fps > 0) {
            String.format(java.util.Locale.ROOT, "Streaming: %s (%.0f fps)", sessionId, fps)
        } else {
            "Streaming: $sessionId"
        }
    }
}
