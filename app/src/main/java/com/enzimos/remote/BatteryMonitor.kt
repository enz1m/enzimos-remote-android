package com.enzimos.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/** Reports battery status and a suggested streaming quality multiplier. */
class BatteryMonitor(context: Context) {
    // Use applicationContext to prevent Activity leaks.
    private val context: Context = context.applicationContext

    /** Battery status snapshot. */
    data class BatteryStatus(
        val level: Int,
        val isCharging: Boolean,
        val plugType: PlugType,
    ) {
        /** Whether the battery is low (below 20%). */
        val isLow: Boolean get() = level < LOW_THRESHOLD

        /** Whether the battery is critically low (below 10%). */
        val isCritical: Boolean get() = level < CRITICAL_THRESHOLD

        /**
         * Recommend a quality adjustment based on battery state.
         *
         * @return Quality multiplier (0.5-1.0). Lower values reduce quality.
         */
        fun qualityMultiplier(): Double {
            if (isCharging) return 1.0
            return when {
                level >= 50 -> 1.0
                level >= 30 -> 0.8
                level >= 20 -> 0.7
                level >= 10 -> 0.6
                else -> 0.5
            }
        }
    }

    /** Power source type. */
    enum class PlugType {
        NONE,
        AC,
        USB,
        WIRELESS,
    }

    private var listener: ((BatteryStatus) -> Unit)? = null
    private var receiver: BroadcastReceiver? = null

    /**
     * Get the current battery status (synchronous, one-shot).
     */
    fun currentStatus(): BatteryStatus {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter)
        return parseStatus(intent)
    }

    /**
     * Start monitoring battery changes.
     *
     * @param callback Invoked on every battery status change
     */
    fun startMonitoring(callback: (BatteryStatus) -> Unit) {
        listener = callback
        val br = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                callback(parseStatus(intent))
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(
            context, br, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Assign after successful registration so stopMonitoring won't try
        // to unregister a receiver that was never registered.
        receiver = br
    }

    /**
     * Stop monitoring battery changes.
     */
    fun stopMonitoring() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
        }
        receiver = null
        listener = null
    }

    companion object {
        /** Battery level below which quality should be reduced. */
        const val LOW_THRESHOLD = 20

        /** Battery level below which streaming should warn. */
        const val CRITICAL_THRESHOLD = 10

        /**
         * Parse a battery status from an ACTION_BATTERY_CHANGED intent.
         */
        fun parseStatus(intent: Intent?): BatteryStatus {
            if (intent == null) {
                return BatteryStatus(100, false, PlugType.NONE)
            }

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val plugType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
                BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
                else -> PlugType.NONE
            }

            return BatteryStatus(pct, isCharging, plugType)
        }
    }
}
