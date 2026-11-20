package com.enzimos.remote.input

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View

class HapticFeedback(context: Context) {

    // Use applicationContext to avoid holding an Activity reference via the
    // Vibrator service's internal context field.
    private val vibrator: Vibrator? = run {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** Whether the device has a vibrator. */
    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    /** Whether haptic feedback is enabled. */
    var enabled: Boolean = true

    /**
     * Perform a light tap haptic feedback.
     */
    fun tap(view: View) {
        if (!enabled) return
        view.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
    }

    /**
     * Perform a click haptic feedback.
     */
    fun click(view: View) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Perform a long press haptic feedback.
     */
    fun longPress(view: View) {
        if (!enabled) return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Perform a custom vibration (for special events like connection/disconnect).
     *
     * @param durationMs Vibration duration in milliseconds
     */
    fun vibrate(durationMs: Long) {
        if (!enabled || vibrator == null || !hasVibrator) return

        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    companion object {
        /** Default vibration duration for connection events. */
        const val CONNECT_VIBRATE_MS = 50L
        /** Default vibration duration for disconnect events. */
        const val DISCONNECT_VIBRATE_MS = 100L
    }
}
