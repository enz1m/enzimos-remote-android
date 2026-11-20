package com.enzimos.remote

import com.enzimos.remote.signaling.SessionInfoItem
import java.util.Locale

/**
 * Formats session information for display in the UI.
 *
 * Provides human-readable strings for:
 * - Session status with color indicators
 * - Viewport dimensions with scale
 * - Uptime formatting
 * - Negotiation state descriptions
 */
object SessionFormatter {

    /**
     * Format the session viewport as "WxH" or "WxH @Sx" when scaled.
     */
    fun formatViewport(session: SessionInfoItem): String {
        return buildString {
            append("${session.width}x${session.height}")
            if (session.scale != 1.0) {
                append(String.format(Locale.US, " @%.1fx", session.scale))
            }
        }
    }

    /**
     * Format the session viewport with refresh rate: "WxH @60Hz".
     */
    fun formatViewportFull(session: SessionInfoItem): String {
        return buildString {
            append("${session.width}x${session.height}")
            if (session.scale != 1.0) {
                append(String.format(Locale.US, " @%.1fx", session.scale))
            }
            append(" ${session.refreshHz}Hz")
        }
    }

    /**
     * Format uptime from seconds to a human-readable string.
     *
     * Examples: "0s", "45s", "5m 30s", "2h 30m", "1d 2h"
     */
    fun formatUptime(seconds: Long): String {
        if (seconds < 0) return "N/A"
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }

    /**
     * Get a user-friendly description of the negotiation state.
     */
    fun formatNegotiationState(state: String): String {
        return when (state.lowercase(Locale.US)) {
            "idle" -> "Idle"
            "waitingforoffer" -> "Connecting..."
            "offersent" -> "Offer sent"
            "negotiating" -> "Negotiating..."
            "connected" -> "Connected"
            "" -> "Unknown"
            else -> {
                if (state.lowercase(Locale.US).startsWith("failed")) {
                    val reason = state.substringAfter('(').trimEnd(')')
                    if (reason.isNotEmpty() && reason != state) {
                        "Failed: $reason"
                    } else {
                        "Failed"
                    }
                } else {
                    state
                }
            }
        }
    }

    /**
     * Format a summary line for a session (one-liner for list views).
     *
     * Example: "1920x1080 | 5m 30s | device-a"
     */
    fun formatSummary(session: SessionInfoItem): String {
        return buildString {
            append(formatViewport(session))
            if (session.uptimeSecs > 0) {
                append(" | ${formatUptime(session.uptimeSecs)}")
            }
            if (session.ownerDeviceId.isNotEmpty()) {
                append(" | ${session.ownerDeviceId}")
            }
        }
    }

    /**
     * Determine if a session appears to be active (connected and has dimensions).
     */
    fun isActive(session: SessionInfoItem): Boolean {
        return session.width > 0 &&
            session.height > 0 &&
            session.status != "stopped" &&
            session.negotiationState != "Failed"
    }

    /**
     * Format bytes to a human-readable string.
     *
     * Examples: "0 B", "1.5 KB", "12.3 MB", "1.0 GB"
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "N/A"
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 ->
                String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 ->
                String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else ->
                String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * Format bitrate in kbps to a human-readable string.
     *
     * Examples: "0 kbps", "500 kbps", "2.5 Mbps"
     */
    fun formatBitrate(kbps: Long): String {
        return when {
            kbps < 0 -> "N/A"
            kbps < 1000 -> "$kbps kbps"
            else -> String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
        }
    }
}
