package com.enzimos.remote

import com.enzimos.remote.signaling.*
import com.enzimos.remote.webrtc.IceServerConfig

/**
 * Complete configuration for a streaming session.
 *
 * Bundles all parameters needed to establish a streaming session:
 * host address, session parameters, quality settings, and WebRTC config.
 */
data class StreamConfig(
    /** WebSocket URL for the signaling server. */
    val wsUrl: String,
    /** Authentication token. */
    val token: String = "",
    /** Device ID for this client. */
    val deviceId: String,
    /** Session ID to create or resume. */
    val sessionId: String,
    /** Viewport width. */
    val width: Int = 1920,
    /** Viewport height. */
    val height: Int = 1080,
    /** Scale factor. */
    val scale: Double = 1.0,
    /** Refresh rate in Hz. */
    val refreshHz: Int = 60,
    /** Application command to launch. */
    val command: List<String> = emptyList(),
    /** Whether to resume an existing session (vs create new). */
    val resume: Boolean = false,
    /** ICE servers for WebRTC. */
    val iceServers: List<IceServerConfig> = IceServerConfig.DEFAULT_SERVERS,
    /** Initial target bitrate in kbps. */
    val initialBitrateKbps: Int = DEFAULT_BITRATE_KBPS,
    /** Whether to enable adaptive bitrate. */
    val adaptiveBitrate: Boolean = true,
    /** Whether to show the performance overlay. */
    val showOverlay: Boolean = false,
    /** Whether to keep the screen on during streaming. */
    val keepScreenOn: Boolean = true,
    /** Whether to enable haptic feedback. */
    val hapticFeedback: Boolean = true,
) {
    /**
     * Validate the configuration.
     *
     * @return null if valid, error message if invalid
     */
    fun validate(): String? {
        val urlError = NetworkUtils.validateWsUrl(wsUrl)
        if (urlError != null) return "Invalid WebSocket URL: $urlError"

        if (deviceId.isBlank()) return "Device ID is required"
        if (!validateDeviceId(deviceId)) return "Invalid device ID"

        if (sessionId.isBlank()) return "Session ID is required"
        if (!validateSessionId(sessionId)) return "Invalid session ID"

        if (!resume && command.isEmpty()) return "Command is required for new sessions"

        if (width < MIN_WIDTH || width > MAX_WIDTH) return "Width out of range"
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) return "Height out of range"
        if (scale < MIN_SCALE || scale > MAX_SCALE) return "Scale out of range"
        if (refreshHz < MIN_REFRESH_HZ || refreshHz > MAX_REFRESH_HZ) return "Refresh rate out of range"

        if (initialBitrateKbps < 128 || initialBitrateKbps > 100_000) {
            return "Bitrate must be between 128 and 100000 kbps"
        }

        return null
    }

    companion object {
        /** Default initial bitrate. */
        const val DEFAULT_BITRATE_KBPS = 5000
    }
}
