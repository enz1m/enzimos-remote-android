package com.enzimos.remote.video

import android.widget.TextView
import java.util.Locale

class PerformanceOverlay(
    private val textView: TextView,
) {
    /** Current stats for display. */
    data class Stats(
        val fps: Double = 0.0,
        val rttMs: Long = -1,
        val bitrateKbps: Int = 0,
        val qualityTier: String = "N/A",
        val packetLoss: Double = 0.0,
        val messagesSent: Long = 0,
        val messagesDropped: Long = 0,
        val framesDecoded: Long = 0,
        val framesDropped: Long = 0,
        val resolution: String = "",
        val codec: String = "",
    )

    private var visible = false
    private var lastFrameCount = 0L
    private var lastBytesReceived = 0L
    private var lastUpdateMs = 0L
    /** Server-side ABR target bitrate, updated via data channel. */
    @Volatile
    var serverBitrateKbps: Int = 0
        private set

    /** Toggle the overlay visibility. */
    fun toggle() {
        visible = !visible
        textView.visibility = if (visible) TextView.VISIBLE else TextView.GONE
    }

    /** Show the overlay. */
    fun show() {
        visible = true
        textView.visibility = TextView.VISIBLE
    }

    /** Hide the overlay. */
    fun hide() {
        visible = false
        textView.visibility = TextView.GONE
    }

    /** Whether the overlay is currently visible. */
    fun isVisible(): Boolean = visible

    /**
     * Update the overlay with fresh stats.
     * Should be called on the UI thread.
     */
    fun update(stats: Stats) {
        if (!visible) return

        val text = buildString {
            // Resolution and codec on one line
            if (stats.resolution.isNotEmpty()) {
                append(stats.resolution)
                if (stats.codec.isNotEmpty()) append(" ${stats.codec}")
                appendLine()
            }
            appendLine(String.format(Locale.US, "FPS: %.1f", stats.fps))
            // Bitrate (measured receive rate)
            if (stats.bitrateKbps > 0) {
                if (stats.bitrateKbps >= 1000) {
                    appendLine(String.format(Locale.US, "Bitrate: %.1f Mbps", stats.bitrateKbps / 1000.0))
                } else {
                    appendLine("Bitrate: ${stats.bitrateKbps} kbps")
                }
            }
            // Server ABR target (from data channel)
            val svrBr = serverBitrateKbps
            if (svrBr > 0) {
                if (svrBr >= 1000) {
                    appendLine(String.format(Locale.US, "ABR: %.1f Mbps", svrBr / 1000.0))
                } else {
                    appendLine("ABR: $svrBr kbps")
                }
            }
            if (stats.rttMs >= 0) {
                appendLine("RTT: ${stats.rttMs}ms")
            }
            if (stats.packetLoss > 0) {
                appendLine(String.format(Locale.US, "Loss: %.1f%%", stats.packetLoss * 100))
            }
            // The WebRTC jitter buffer framesDropped counter is always non-zero
            // at high FPS — it counts late/duplicate frames discarded before
            // decoding.  Only surface it when the drop *rate* is alarming
            // (>20%), which would indicate actual quality issues.
            val totalFrames = stats.framesDecoded + stats.framesDropped
            if (totalFrames > 0) {
                val dropPct = (stats.framesDropped.toDouble() / totalFrames) * 100.0
                if (dropPct > 20.0) {
                    appendLine(String.format(Locale.US, "Drop: %.0f%%", dropPct))
                }
            }
            append("Msgs: ${stats.messagesSent}")
            if (stats.messagesDropped > 0) {
                append(" (${stats.messagesDropped} dropped)")
            }
        }

        textView.text = text
    }

    /**
     * Update the server-side ABR target bitrate.
     * Called from the data channel handler when an `abr_status` message arrives.
     * Thread-safe — can be called from any thread.
     */
    fun updateServerBitrate(kbps: Int) {
        serverBitrateKbps = kbps
    }

    /**
     * Calculate FPS and bitrate from cumulative counters.
     *
     * Call this once per polling interval. Returns (fps, bitrateKbps).
     */
    fun calculateMetrics(totalFrames: Long, totalBytes: Long): Pair<Double, Int> {
        val now = System.currentTimeMillis()
        val elapsed = now - lastUpdateMs
        val frameDelta = totalFrames - lastFrameCount
        val bytesDelta = totalBytes - lastBytesReceived

        lastFrameCount = totalFrames
        lastBytesReceived = totalBytes
        lastUpdateMs = now

        val fps = if (elapsed > 0) {
            (frameDelta.toDouble() / elapsed) * 1000.0
        } else {
            0.0
        }
        val bitrateKbps = if (elapsed > 0) {
            ((bytesDelta * 8.0 / elapsed) * 1000.0 / 1000.0).toInt()
        } else {
            0
        }
        return Pair(fps, bitrateKbps)
    }

    /**
     * Calculate FPS from frame count delta.
     *
     * @param totalFrames Total frames decoded so far
     * @return FPS since last call, or 0 if this is the first call
     */
    fun calculateFps(totalFrames: Long): Double {
        return calculateMetrics(totalFrames, lastBytesReceived).first
    }
}
