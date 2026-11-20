package com.enzimos.remote.share

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.enzimos.remote.MainActivity
import com.enzimos.remote.R

/** Progress and result notifications for file transfers. */
class TransferNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val lastProgressAt = HashMap<String, Long>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.transfers_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.transfers_channel_description)
                setSound(null, null)
            },
        )
    }

    fun update(transfer: ShareManager.Transfer) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        if (transfer.phase == ShareManager.Phase.ACTIVE) {
            // Progress at most twice a second (notification rate limits).
            val last = lastProgressAt[transfer.id] ?: 0
            if (transfer.done > 0 && now - last < 500) return
            lastProgressAt[transfer.id] = now
        } else {
            lastProgressAt.remove(transfer.id)
        }
        manager.notify(transfer.id.hashCode(), build(transfer))
    }

    private fun build(t: ShareManager.Transfer): Notification {
        val sending = t.direction == ShareManager.Direction.SEND
        val title = when (t.phase) {
            ShareManager.Phase.ACTIVE -> context.getString(if (sending) R.string.transfer_sending else R.string.transfer_receiving, t.name)
            ShareManager.Phase.DONE -> context.getString(if (sending) R.string.transfer_sent else R.string.transfer_received, t.name)
            ShareManager.Phase.FAILED -> context.getString(R.string.transfer_failed, t.name)
        }
        val text = when (t.phase) {
            ShareManager.Phase.FAILED -> t.error ?: ""
            ShareManager.Phase.DONE -> context.getString(if (sending) R.string.transfer_to else R.string.transfer_from_saved, t.hostName)
            ShareManager.Phase.ACTIVE -> context.getString(if (sending) R.string.transfer_to else R.string.transfer_from, t.hostName)
        }
        val open = t.uri?.let { uri ->
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, t.id.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (t.phase == ShareManager.Phase.ACTIVE) {
            val max = 1000
            val progress = if (t.size > 0) (t.done * max / t.size).toInt() else 0
            builder.setOngoing(true).setProgress(max, progress, t.size <= 0).setCategory(Notification.CATEGORY_PROGRESS)
        } else {
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL = "enzremote_transfers"
    }
}
