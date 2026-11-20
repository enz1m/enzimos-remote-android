package com.enzimos.remote.signaling

/** Measures signaling round-trip time using matching ping and pong timestamps. */
class PingTracker(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    /** Pending ping with its send timestamp. */
    private var pendingPingMs: Long? = null
    private var lastRttMs: Long = -1
    private var totalPings: Long = 0
    private var totalPongs: Long = 0
    private var totalLost: Long = 0
    private var lastPingTime: Long = 0
    private val lock = Any()

    /**
     * Record that a ping was sent.
     *
     * @param timestampMs The timestamp_ms value sent in the ping
     */
    fun pingSent(timestampMs: Long) {
        synchronized(lock) {
            if (pendingPingMs != null) {
                totalLost++ // Previous ping was never answered
            }
            pendingPingMs = timestampMs
            lastPingTime = System.currentTimeMillis()
            totalPings++
        }
    }

    /**
     * Record that a pong was received.
     *
     * @param timestampMs The timestamp_ms from the pong (should match ping)
     * @return RTT in milliseconds, or -1 if no matching ping
     */
    fun pongReceived(timestampMs: Long): Long {
        synchronized(lock) {
            val sentMs = pendingPingMs ?: return -1
            if (sentMs != timestampMs) return -1 // Mismatched pong

            val rtt = System.currentTimeMillis() - lastPingTime
            lastRttMs = rtt
            pendingPingMs = null
            totalPongs++
            return rtt
        }
    }

    /**
     * Get the last measured RTT.
     *
     * @return RTT in ms, or -1 if no pong received yet
     */
    fun lastRtt(): Long {
        synchronized(lock) {
            return lastRttMs
        }
    }

    /**
     * Check if it's time to send the next ping.
     */
    fun shouldPing(): Boolean {
        return shouldPing(System.currentTimeMillis())
    }

    /**
     * Check if it's time to send the next ping (testable).
     */
    fun shouldPing(nowMs: Long): Boolean {
        synchronized(lock) {
            return nowMs - lastPingTime >= intervalMs
        }
    }

    /**
     * Get the number of pings that received no pong.
     */
    fun lostCount(): Long {
        synchronized(lock) {
            return totalLost
        }
    }

    /**
     * Get the packet loss percentage.
     */
    fun lossPercent(): Double {
        synchronized(lock) {
            if (totalPings == 0L) return 0.0
            return (totalLost.toDouble() / totalPings) * 100.0
        }
    }

    /**
     * Get total pings sent.
     */
    fun totalPingsSent(): Long {
        synchronized(lock) {
            return totalPings
        }
    }

    /**
     * Get total pongs received.
     */
    fun totalPongsReceived(): Long {
        synchronized(lock) {
            return totalPongs
        }
    }

    /**
     * Check if a ping is currently pending (awaiting pong).
     */
    fun isPending(): Boolean {
        synchronized(lock) {
            return pendingPingMs != null
        }
    }

    /**
     * Reset all state.
     */
    fun reset() {
        synchronized(lock) {
            pendingPingMs = null
            lastRttMs = -1
            totalPings = 0
            totalPongs = 0
            totalLost = 0
            lastPingTime = 0
        }
    }

    companion object {
        /** Default ping interval: 5 seconds. */
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
