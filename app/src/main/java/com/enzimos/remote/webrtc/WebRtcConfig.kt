package com.enzimos.remote.webrtc

/**
 * WebRTC peer connection configuration.
 *
 * Provides optimal settings for remote desktop streaming:
 * - Low latency (no bundling, aggressive ICE)
 * - Video-only (no audio by default)
 * - Data channel for input events
 */
object WebRtcConfig {

    /** ICE candidate gathering timeout in milliseconds. */
    const val ICE_GATHERING_TIMEOUT_MS = 10_000L

    /** Connection timeout for initial offer/answer exchange. */
    const val OFFER_TIMEOUT_MS = 15_000L

    /** Maximum retries for ICE restart. */
    const val MAX_ICE_RESTARTS = 3

    /** Data channel label for control messages. */
    const val DATA_CHANNEL_LABEL = "enzremote-control"

    /** Data channel ID (negotiated). */
    const val DATA_CHANNEL_ID = 0

    /** Data channel maximum retransmits for unreliable mode. */
    const val DATA_CHANNEL_MAX_RETRANSMITS = 0

    /** Data channel buffer threshold for backpressure (64 KiB). */
    const val DATA_CHANNEL_BUFFER_THRESHOLD = 65_536L

    /** Minimum interval between ICE candidate sends (ms). */
    const val ICE_CANDIDATE_THROTTLE_MS = 50L

    /** Maximum video bitrate in bps. */
    const val MAX_VIDEO_BITRATE_BPS = 20_000_000 // 20 Mbps

    /** Minimum video bitrate in bps. */
    const val MIN_VIDEO_BITRATE_BPS = 128_000 // 128 kbps

    /**
     * Generate data channel init options.
     *
     * Uses ordered, reliable delivery for control messages.
     */
    fun dataChannelOrdered(): Map<String, Any> {
        return mapOf(
            "ordered" to true,
            "negotiated" to true,
            "id" to DATA_CHANNEL_ID,
        )
    }

    /**
     * Generate data channel init for unreliable (low-latency) mode.
     *
     * Used for high-frequency input events where occasional drops
     * are acceptable (e.g., pointer moves).
     */
    fun dataChannelUnreliable(): Map<String, Any> {
        return mapOf(
            "ordered" to false,
            "maxRetransmits" to DATA_CHANNEL_MAX_RETRANSMITS,
            "negotiated" to true,
            "id" to DATA_CHANNEL_ID + 1,
        )
    }

    /**
     * Check if a bitrate is within the valid range.
     */
    fun isValidBitrate(bps: Int): Boolean {
        return bps in MIN_VIDEO_BITRATE_BPS..MAX_VIDEO_BITRATE_BPS
    }

    /**
     * Clamp a bitrate to the valid range.
     */
    fun clampBitrate(bps: Int): Int {
        return bps.coerceIn(MIN_VIDEO_BITRATE_BPS, MAX_VIDEO_BITRATE_BPS)
    }
}
