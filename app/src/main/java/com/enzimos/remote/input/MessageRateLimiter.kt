package com.enzimos.remote.input

/** Critical input bypasses the token bucket so releases can reach the host. */
class MessageRateLimiter(
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val refillPerSecond: Int = DEFAULT_REFILL_PER_SECOND,
) {
    /** Message priority levels. */
    enum class Priority {
        /** Always sent (key events, button presses). */
        CRITICAL,
        /** Sent when tokens available, dropped otherwise (pointer moves). */
        NORMAL,
        /** Aggressively rate-limited (scroll, cursor updates). */
        LOW,
    }

    private var tokens: Double = maxTokens.toDouble()
    private var lastRefillMs: Long = System.currentTimeMillis()
    private var totalSent: Long = 0
    private var totalDropped: Long = 0

    private val lock = Any()

    /**
     * Try to consume a token for sending a message.
     *
     * @param priority Message priority
     * @return true if the message should be sent, false if dropped
     */
    fun tryAcquire(priority: Priority = Priority.NORMAL): Boolean {
        synchronized(lock) {
            refill()

            // Critical messages always pass.
            if (priority == Priority.CRITICAL) {
                tokens = (tokens - 1).coerceAtLeast(0.0)
                totalSent++
                return true
            }

            // Low priority requires at least 25% bucket.
            if (priority == Priority.LOW && tokens < maxTokens * 0.25) {
                totalDropped++
                return false
            }

            // Normal priority requires at least 1 token.
            if (tokens >= 1.0) {
                tokens -= 1.0
                totalSent++
                return true
            }

            totalDropped++
            return false
        }
    }

    /**
     * Get the current token count (for diagnostics).
     */
    fun availableTokens(): Double {
        synchronized(lock) {
            refill()
            return tokens
        }
    }

    /**
     * Get counters for sent and dropped messages.
     */
    fun counters(): Pair<Long, Long> {
        synchronized(lock) {
            return totalSent to totalDropped
        }
    }

    /**
     * Reset the limiter to full capacity.
     */
    fun reset() {
        synchronized(lock) {
            tokens = maxTokens.toDouble()
            lastRefillMs = System.currentTimeMillis()
            totalSent = 0
            totalDropped = 0
        }
    }

    /**
     * Classify a control message type into a priority level.
     */
    companion object {
        /** Default bucket size (120 = 2 seconds of burst at 60/sec). */
        const val DEFAULT_MAX_TOKENS = 120

        /** Default refill rate (60 tokens/sec = host rate limit). */
        const val DEFAULT_REFILL_PER_SECOND = 60

        /**
         * Classify a control message type to its priority.
         *
         * @param messageType The "type" field from the control message JSON
         */
        fun classify(messageType: String): Priority {
            return when (messageType) {
                // Always send: user intentional actions
                "key", "button", "text", "keyframe" -> Priority.CRITICAL
                // Normal: important but droppable
                "pointer", "touch", "resize", "bitrate" -> Priority.NORMAL
                // Low priority: can be heavily throttled
                "scroll", "pointer_relative", "cursor_visibility" -> Priority.LOW
                // Unknown types: treat as normal
                else -> Priority.NORMAL
            }
        }
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillMs
        if (elapsed <= 0) return

        val refillAmount = (elapsed.toDouble() / 1000.0) * refillPerSecond
        tokens = (tokens + refillAmount).coerceAtMost(maxTokens.toDouble())
        lastRefillMs = now
    }
}
