package com.enzimos.remote.share

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.enzimos.remote.R
import com.enzimos.remote.connection.ConnectionManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process and its computer links alive while files transfer, so
 * leaving the app does not cut a transfer (Android blocks background network).
 */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, TransferNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(getString(R.string.transfers_running))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (error: Exception) {
            Log.w(TAG, "Could not keep transfers in the foreground: ${error.message}")
        }
        if (active.get() == 0) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "TransferService"
        private const val NOTIFICATION_ID = 7401
        private val active = AtomicInteger()

        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        fun hold(context: Context) {
            if (active.getAndIncrement() == 0) {
                main.post { ConnectionManager.get(context).acquireLease() }
                runCatching { context.startForegroundService(Intent(context, TransferService::class.java)) }
                    .onFailure { Log.w(TAG, "Transfer service not started: ${it.message}") }
            }
        }

        fun release(context: Context) {
            if (active.decrementAndGet() <= 0) {
                active.set(0)
                main.post { ConnectionManager.get(context).releaseLease() }
                context.stopService(Intent(context, TransferService::class.java))
            }
        }
    }
}
