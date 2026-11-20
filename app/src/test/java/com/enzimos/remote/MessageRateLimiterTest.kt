package com.enzimos.remote

import com.enzimos.remote.input.MessageRateLimiter
import org.junit.Assert.*
import org.junit.Test

class MessageRateLimiterTest {

    @Test
    fun `critical messages pass even at zero tokens`() {
        val limiter = MessageRateLimiter(maxTokens = 0, refillPerSecond = 0)
        assertTrue(limiter.tryAcquire(MessageRateLimiter.Priority.CRITICAL))
    }

    @Test
    fun `low priority requires 25 percent bucket`() {
        val limiter = MessageRateLimiter(maxTokens = 100, refillPerSecond = 0)
        // Consume 76 tokens (leaving 24, below 25%)
        for (i in 0 until 76) {
            limiter.tryAcquire()
        }
        // Low priority should be dropped (24 < 25)
        assertFalse(limiter.tryAcquire(MessageRateLimiter.Priority.LOW))
    }
}
