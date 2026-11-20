package com.enzimos.remote.video

import android.util.Log
import com.enzimos.remote.input.ControlMessageSerializer

/** Uses packet loss and RTT to select a bitrate tier, with hysteresis before raising the bitrate. */
class QualityController(
    private val maxBitrateKbps: Int = 5000,
    private val minBitrateKbps: Int = 200,
    private val onControlMessage: (String) -> Unit,
) {
    companion object {
        private const val TAG = "QualityController"

        // Thresholds
        private const val LOSS_EXCELLENT = 0.005     // 0.5%
        private const val LOSS_GOOD = 0.02           // 2%
        private const val LOSS_FAIR = 0.05           // 5%
        private const val RTT_EXCELLENT_MS = 50L
        private const val RTT_GOOD_MS = 100L
        private const val RTT_FAIR_MS = 200L

        // Adjustment factors
        private const val INCREASE_FACTOR = 1.10     // +10%
        private const val FAIR_FACTOR = 0.75         // -25%
        private const val POOR_FACTOR = 0.50         // -50%

        // Stability: must be in "excellent" for this many consecutive polls
        // before increasing bitrate
        private const val STABLE_POLLS_FOR_INCREASE = 5
    }

    /** Current quality tier. */
    enum class Tier {
        EXCELLENT,
        GOOD,
        FAIR,
        POOR,
    }

    @Volatile private var currentBitrateKbps: Int = maxBitrateKbps
    @Volatile private var currentTier: Tier = Tier.GOOD
    @Volatile private var excellentStreak: Int = 0

    /** The current quality tier. */
    fun getCurrentTier(): Tier = currentTier

    /** The current target bitrate in kbps. */
    fun getCurrentBitrateKbps(): Int = currentBitrateKbps

    /**
     * Update with fresh network stats and adjust bitrate if needed.
     *
     * @param packetLossRate Fraction of packets lost (0.0 - 1.0)
     * @param rttMs Round-trip time in milliseconds
     * @return The new quality tier
     */
    fun update(packetLossRate: Double, rttMs: Long): Tier {
        val newTier = classifyTier(packetLossRate, rttMs)
        val previousBitrate = currentBitrateKbps

        when (newTier) {
            Tier.EXCELLENT -> {
                excellentStreak++
                if (excellentStreak >= STABLE_POLLS_FOR_INCREASE) {
                    // Gradually increase bitrate
                    val target = (currentBitrateKbps * INCREASE_FACTOR).toInt()
                        .coerceAtMost(maxBitrateKbps)
                    if (target > currentBitrateKbps) {
                        currentBitrateKbps = target
                        sendBitrateUpdate()
                    }
                }
            }
            Tier.GOOD -> {
                excellentStreak = 0
                // Stable — no change
            }
            Tier.FAIR -> {
                excellentStreak = 0
                val target = (maxBitrateKbps * FAIR_FACTOR).toInt()
                    .coerceAtLeast(minBitrateKbps)
                if (target < currentBitrateKbps) {
                    currentBitrateKbps = target
                    sendBitrateUpdate()
                }
            }
            Tier.POOR -> {
                excellentStreak = 0
                val target = (maxBitrateKbps * POOR_FACTOR).toInt()
                    .coerceAtLeast(minBitrateKbps)
                if (target < currentBitrateKbps) {
                    currentBitrateKbps = target
                    sendBitrateUpdate()
                    // Also request a keyframe to recover quickly
                    onControlMessage(ControlMessageSerializer.keyframe())
                }
            }
        }

        if (newTier != currentTier) {
            Log.i(TAG, "Quality: $currentTier → $newTier (bitrate: $previousBitrate → $currentBitrateKbps kbps)")
        }
        currentTier = newTier
        return newTier
    }

    /**
     * Reset to maximum bitrate (e.g., after reconnect).
     */
    fun reset() {
        currentBitrateKbps = maxBitrateKbps
        currentTier = Tier.GOOD
        excellentStreak = 0
        sendBitrateUpdate()
    }

    private fun classifyTier(loss: Double, rtt: Long): Tier {
        return when {
            loss >= LOSS_FAIR || rtt >= RTT_FAIR_MS -> Tier.POOR
            loss >= LOSS_GOOD || rtt >= RTT_GOOD_MS -> Tier.FAIR
            loss >= LOSS_EXCELLENT || rtt >= RTT_EXCELLENT_MS -> Tier.GOOD
            else -> Tier.EXCELLENT
        }
    }

    private fun sendBitrateUpdate() {
        onControlMessage(ControlMessageSerializer.bitrate(currentBitrateKbps))
    }
}
