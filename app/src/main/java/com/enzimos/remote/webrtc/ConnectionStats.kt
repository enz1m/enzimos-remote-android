package com.enzimos.remote.webrtc

/** Keeps a synchronized sliding window of WebRTC samples. */
class ConnectionStats(
    /** Number of samples to keep in the sliding window. */
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {
    /** A single snapshot of connection statistics. */
    data class Sample(
        val timestampMs: Long,
        val rttMs: Long,
        val packetLoss: Double,
        val jitterMs: Double,
        val framesDecoded: Long,
        val framesDropped: Long,
        val bytesReceived: Long,
    )

    /** Aggregated statistics over the sliding window. */
    data class Summary(
        val avgRttMs: Long,
        val minRttMs: Long,
        val maxRttMs: Long,
        val avgPacketLoss: Double,
        val avgJitterMs: Double,
        val totalFramesDecoded: Long,
        val totalFramesDropped: Long,
        val totalBytesReceived: Long,
        val sampleCount: Int,
        val durationMs: Long,
    ) {
        /** Frame drop rate as a percentage (0-100). */
        val dropRate: Double
            get() {
                val total = totalFramesDecoded + totalFramesDropped
                return if (total > 0) (totalFramesDropped.toDouble() / total) * 100.0 else 0.0
            }

        /** Effective bitrate in kbps over the window duration. */
        val bitrateKbps: Long
            get() = if (durationMs > 0) {
                (totalBytesReceived * 8) / durationMs // bits per ms = kbps
            } else {
                0
            }
    }

    private val samples = mutableListOf<Sample>()
    private val lock = Any()

    /**
     * Record a new sample.
     *
     * If the window is full, the oldest sample is removed.
     */
    fun record(sample: Sample) {
        synchronized(lock) {
            if (samples.size >= windowSize) {
                samples.removeAt(0)
            }
            samples.add(sample)
        }
    }

    /**
     * Get the current summary over all samples in the window.
     *
     * Returns null if no samples have been recorded.
     */
    fun summary(): Summary? {
        synchronized(lock) {
            if (samples.isEmpty()) return null

            val avgRtt = samples.map { it.rttMs }.average().toLong()
            val minRtt = samples.minOf { it.rttMs }
            val maxRtt = samples.maxOf { it.rttMs }
            val avgLoss = samples.map { it.packetLoss }.average()
            val avgJitter = samples.map { it.jitterMs }.average()

            val first = samples.first()
            val last = samples.last()

            return Summary(
                avgRttMs = avgRtt,
                minRttMs = minRtt,
                maxRttMs = maxRtt,
                avgPacketLoss = avgLoss,
                avgJitterMs = avgJitter,
                totalFramesDecoded = (last.framesDecoded - first.framesDecoded).coerceAtLeast(0),
                totalFramesDropped = (last.framesDropped - first.framesDropped).coerceAtLeast(0),
                totalBytesReceived = (last.bytesReceived - first.bytesReceived).coerceAtLeast(0),
                sampleCount = samples.size,
                durationMs = last.timestampMs - first.timestampMs,
            )
        }
    }

    /**
     * Get the most recent sample, or null if none recorded.
     */
    fun latest(): Sample? {
        synchronized(lock) {
            return samples.lastOrNull()
        }
    }

    /**
     * Clear all recorded samples.
     */
    fun clear() {
        synchronized(lock) {
            samples.clear()
        }
    }

    /**
     * Get the number of samples currently in the window.
     */
    fun sampleCount(): Int {
        synchronized(lock) {
            return samples.size
        }
    }

    companion object {
        /** Default sliding window size (30 samples ≈ 30 seconds at 1s interval). */
        const val DEFAULT_WINDOW_SIZE = 30
    }
}
