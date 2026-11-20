package com.enzimos.remote.video

/**
 * Represents the current connection quality for display in the UI.
 *
 * Maps QualityController tiers and RTT measurements to user-friendly
 * labels and colors.
 */
enum class ConnectionQuality(val label: String, val emoji: String) {
    EXCELLENT("Excellent", ""),
    GOOD("Good", ""),
    FAIR("Fair", ""),
    POOR("Poor", ""),
    DISCONNECTED("Disconnected", ""),
    UNKNOWN("Unknown", "");

    companion object {
        /**
         * Derive connection quality from RTT and quality tier.
         */
        fun fromMetrics(
            rttMs: Long,
            qualityTier: QualityController.Tier?,
            isConnected: Boolean,
        ): ConnectionQuality {
            if (!isConnected) return DISCONNECTED

            return when (qualityTier) {
                QualityController.Tier.EXCELLENT -> EXCELLENT
                QualityController.Tier.GOOD -> GOOD
                QualityController.Tier.FAIR -> FAIR
                QualityController.Tier.POOR -> POOR
                null -> {
                    // Fallback to RTT-based classification
                    when {
                        rttMs < 0 -> UNKNOWN
                        rttMs < 30 -> EXCELLENT
                        rttMs < 80 -> GOOD
                        rttMs < 150 -> FAIR
                        else -> POOR
                    }
                }
            }
        }

        /**
         * Derive from RTT alone (simpler API for quick checks).
         */
        fun fromRtt(rttMs: Long): ConnectionQuality {
            return when {
                rttMs < 0 -> UNKNOWN
                rttMs < 30 -> EXCELLENT
                rttMs < 80 -> GOOD
                rttMs < 150 -> FAIR
                else -> POOR
            }
        }
    }
}
