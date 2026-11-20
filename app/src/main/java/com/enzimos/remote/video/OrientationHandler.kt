package com.enzimos.remote.video

import android.content.res.Configuration
import android.util.Log
import com.enzimos.remote.input.ControlMessageSerializer
import com.enzimos.remote.signaling.SignalingClient

/** Requests a persistent viewport resize over signaling and a keyframe over the control channel. */
class OrientationHandler(
    private val signalingClient: SignalingClient?,
    private val sessionId: String,
    private val sendControl: (String) -> Unit,
) {
    companion object {
        private const val TAG = "OrientationHandler"
    }

    /** Last known dimensions to avoid redundant resizes. */
    private var lastWidth = 0
    private var lastHeight = 0

    /**
     * Process an orientation change.
     *
     * @param newWidth New viewport width in pixels
     * @param newHeight New viewport height in pixels
     * @param scale Display scale factor (e.g., 2.0 for HiDPI)
     * @param refreshHz Display refresh rate
     * @return true if a resize was sent, false if dimensions unchanged
     */
    fun onOrientationChanged(
        newWidth: Int,
        newHeight: Int,
        scale: Double = 1.0,
        refreshHz: Int = 60,
    ): Boolean {
        if (newWidth == lastWidth && newHeight == lastHeight) {
            return false
        }

        Log.i(TAG, "Orientation changed: ${lastWidth}x${lastHeight} → ${newWidth}x${newHeight}")
        lastWidth = newWidth
        lastHeight = newHeight

        // Send signaling resize (persistent, triggers compositor reconfigure)
        signalingClient?.resizeSession(
            sessionId = sessionId,
            width = newWidth,
            height = newHeight,
            scale = scale,
            refreshHz = refreshHz,
        )

        // Send in-band resize (immediate pipeline update)
        sendControl(ControlMessageSerializer.resize(newWidth, newHeight))

        // Request keyframe for clean IDR at new resolution
        sendControl(ControlMessageSerializer.keyframe())

        return true
    }

    /**
     * Determine viewport dimensions from a Configuration.
     *
     * @param config The new Configuration
     * @param displayWidth Real display width in pixels
     * @param displayHeight Real display height in pixels
     * @return Pair of (width, height)
     */
    fun dimensionsFromConfig(
        config: Configuration,
        displayWidth: Int,
        displayHeight: Int,
    ): Pair<Int, Int> {
        return if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Pair(maxOf(displayWidth, displayHeight), minOf(displayWidth, displayHeight))
        } else {
            Pair(minOf(displayWidth, displayHeight), maxOf(displayWidth, displayHeight))
        }
    }
}
