package com.enzimos.remote.video

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.util.Rational

/** Derives the PiP aspect ratio from the current stream and reports transitions to the activity. */
class PipHelper(
    private val activity: Activity,
    private val onPipStateChanged: (Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "PipHelper"

        /** Minimum API level for PiP support. */
        const val MIN_API = Build.VERSION_CODES.O // API 26

        /**
         * Check if PiP is supported on this device.
         * Always true for minSdk >= 26 (API O).
         */
        fun isSupported(): Boolean = true
    }

    private var sessionWidth = 0
    private var sessionHeight = 0

    /** Whether the activity is currently in PiP mode. */
    var isInPipMode = false
        private set

    /**
     * Update the session dimensions for aspect ratio calculation.
     */
    fun updateSessionSize(width: Int, height: Int) {
        sessionWidth = width
        sessionHeight = height
    }

    /**
     * Enter PiP mode with the correct aspect ratio.
     *
     * @return true if PiP was entered, false if not supported or failed
     */
    fun enterPipMode(): Boolean {
        if (!isSupported()) {
            Log.w(TAG, "PiP not supported on this API level")
            return false
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(getAspectRatio())
            .build()

        return try {
            activity.enterPictureInPictureMode(params)
            Log.i(TAG, "Entered PiP mode (${sessionWidth}x${sessionHeight})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter PiP: ${e.message}")
            false
        }
    }

    /**
     * Called when PiP mode changes.
     * Notifies the listener so the UI can adapt (e.g., disable touch in PiP).
     */
    fun onPipModeChanged(isInPip: Boolean) {
        isInPipMode = isInPip
        Log.i(TAG, "PiP mode: $isInPip")
        onPipStateChanged(isInPip)
    }

    /**
     * Compute aspect ratio as a Rational for the current session.
     *
     * @return Rational aspect ratio, or 16:9 if dimensions are unknown
     */
    fun getAspectRatio(): Rational {
        return if (sessionWidth > 0 && sessionHeight > 0) {
            val ratio = sessionWidth.toFloat() / sessionHeight.toFloat()
            if (ratio in 0.42f..2.39f) {
                Rational(sessionWidth, sessionHeight)
            } else {
                if (sessionHeight > sessionWidth) Rational(9, 16) else Rational(16, 9)
            }
        } else {
            Rational(16, 9)
        }
    }
}
