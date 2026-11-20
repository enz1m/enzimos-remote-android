package com.enzimos.remote.phone

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.PhoneStatusMessage
import com.enzimos.remote.signaling.SignalingClient
import com.enzimos.remote.signaling.encodeClientMessage

/**
 * Phone-side companion features: battery status for the computers, find my
 * phone, and routing of notification actions to [NotificationMirror].
 */
class PhoneFeatures private constructor(private val app: Application) {
    private var battery = -1
    private var charging = false
    private val connections get() = ConnectionManager.get(app)

    init {
        // Sticky broadcast: the current level arrives immediately.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = batteryChanged(intent)
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::batteryChanged)
    }

    fun onConnected(client: SignalingClient) {
        if (battery >= 0) client.sendRaw(statusJson())
    }

    fun onMessage(message: HostMessage) {
        when (message) {
            is HostMessage.Ring -> if (message.msg.ringing) FindPhone.start(app) else FindPhone.stop(app)
            is HostMessage.NotificationAction ->
                NotificationMirror.instance?.invoke(message.msg.key, message.msg.actionId, message.msg.text)
            is HostMessage.NotificationDismiss -> NotificationMirror.instance?.dismiss(message.msg.key)
            else -> Unit
        }
    }

    private fun batteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val percent = (level * 100 / scale).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (percent == battery && isCharging == charging) return
        battery = percent
        charging = isCharging
        val json = statusJson()
        connections.hosts.value.filter { it.status == ConnectionManager.Status.ONLINE }
            .forEach { connections.connection(it.hostId)?.client?.sendRaw(json) }
    }

    private fun statusJson() = encodeClientMessage(PhoneStatusMessage(battery = battery, charging = charging))

    companion object {
        @Volatile private var instance: PhoneFeatures? = null

        fun get(context: Context): PhoneFeatures = instance ?: synchronized(this) {
            instance ?: PhoneFeatures(context.applicationContext as Application).also { instance = it }
        }
    }
}
